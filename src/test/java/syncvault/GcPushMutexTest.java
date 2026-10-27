package syncvault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class GcPushMutexTest {

    private NettyServer nettyServer;

    @AfterEach
    public void tearDown() {
        Store.gcReachabilityHook = null;
        if (nettyServer != null) {
            nettyServer.close();
            nettyServer = null;
        }
        Store.closeDefault();
    }

    private String initRepo(File syncvaultDir) throws Exception {
        syncvaultDir.mkdirs();
        Store.useDir(syncvaultDir);
        Store.createSchema();
        String initId = Commit.first_commit();
        Store.setHead("master");
        Repository.currHead = initId;
        return initId;
    }

    private String putBlob(Store.Session session, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = Utils.hash(bytes);
        session.putBlob(hash, bytes);
        return hash;
    }

    /**
     * Requirement 4: Test that would fail without the repository mutex.
     *
     * <p>When GC computes its reachability snapshot, only commit C0 is visible.
     * If a concurrent push were allowed to interleave and insert C1 + blob1 before GC sweeps,
     * GC would see C1 in allObjects() but absent from the precomputed reachable set,
     * incorrectly deleting C1 even though branch tip points to C1.
     *
     * <p>With the repository mutex:
     * When push attempts to run while GC is between reachability computation and sweep,
     * push BLOCKS on RepoLock. GC completes its sweep (finding nothing new to delete),
     * releases RepoLock, and only then does push proceed to write C1 and update the ref.
     * Thus C1 is never deleted!
     */
    @Test
    public void testGcPushMutexPreventsRaceDuringReachabilityComputation(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        String c0 = initRepo(syncvaultDir);

        Store.Session session = Store.getDefault();

        // Prepare commit C1 and blob1 to be pushed
        byte[] blob1Bytes = "blob content for commit c1\n".getBytes(StandardCharsets.UTF_8);
        String blob1Hash = Utils.hash(blob1Bytes);
        TreeMap<String, String> map1 = new TreeMap<>();
        map1.put("file1.txt", blob1Hash);
        Commit c1 = new Commit("commit c1", map1, c0, null);
        c1.setSelfHash(Utils.hash(Utils.serialize(c1)));

        CountDownLatch pushAttempted = new CountDownLatch(1);
        AtomicBoolean pushFinished = new AtomicBoolean(false);
        AtomicReference<Throwable> pushError = new AtomicReference<>();

        Thread pushThread = new Thread(() -> {
            try {
                // Simulate push path (acquiring RepoLock, then writing objects and advancing ref)
                pushAttempted.countDown();
                try (RepoLock lock = session.acquireLock()) {
                    session.startInFlightPush();
                    try {
                        session.begin();
                        session.putBlob(blob1Hash, blob1Bytes);
                        session.putCommit(c1);
                        session.setRef("master", c1.getSelfHash());
                        session.commitTx();
                    } finally {
                        session.endInFlightPush();
                    }
                }
                pushFinished.set(true);
            } catch (Throwable t) {
                pushError.set(t);
            }
        });

        // Set hook: right after GC computes reachability (which only includes c0)
        Store.gcReachabilityHook = () -> {
            pushThread.start();
            try {
                assertTrue(pushAttempted.await(5, TimeUnit.SECONDS), "Push thread failed to start");
                // Give pushThread time to attempt lock acquisition
                Thread.sleep(80);
                // The push thread MUST be blocked by RepoLock held by GC
                assertFalse(pushFinished.get(), "Push thread should be blocked by RepoLock while GC holds it");
                assertFalse(session.hasObject(c1.getSelfHash()), "Commit c1 should not be written while push is blocked");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Run GC (0 grace period)
        Store.GcResult gcResult = session.gc(0);
        assertNotNull(gcResult);

        // Wait for push to finish after GC released RepoLock
        pushThread.join(5000);
        assertFalse(pushThread.isAlive(), "Push thread should have finished after GC released lock");
        assertNull(pushError.get(), "Push thread encountered an unexpected error");
        assertTrue(pushFinished.get(), "Push thread should have successfully finished");

        // Verification: C1 and blob1 must exist in store and master points to C1
        assertEquals(c1.getSelfHash(), session.getRef("master"));
        assertTrue(session.hasObject(c1.getSelfHash()), "Commit c1 must exist and not be deleted by GC");
        assertTrue(session.hasObject(blob1Hash), "Blob1 must exist and not be deleted by GC");
    }

    /**
     * Requirement 4: Demonstrates that WITHOUT the mutex, the semantic race causes GC
     * to delete newly pushed objects that arrived after the snapshot.
     */
    @Test
    public void testDemonstrateRaceWithoutMutexDeletesPushedObjects(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        String c0 = initRepo(syncvaultDir);
        Store.Session session = Store.getDefault();

        // 1. Snapshot phase: GC computes reachability based on current master (c0)
        Set<String> staleReachableCommits = new HashSet<>();
        staleReachableCommits.add(c0);
        Set<String> staleReachableBlobs = new HashSet<>();

        // 2. Interleaved push WITHOUT mutex: inserts c1 and advances master ref
        byte[] blobBytes = "interleaved push blob\n".getBytes(StandardCharsets.UTF_8);
        String blobHash = Utils.hash(blobBytes);
        TreeMap<String, String> map1 = new TreeMap<>();
        map1.put("data.txt", blobHash);
        Commit c1 = new Commit("interleaved commit", map1, c0, null);
        c1.setSelfHash(Utils.hash(Utils.serialize(c1)));

        session.putBlob(blobHash, blobBytes);
        session.putCommit(c1);
        session.setRef("master", c1.getSelfHash());

        assertTrue(session.hasObject(c1.getSelfHash()));
        assertTrue(session.hasObject(blobHash));

        // 3. Stale sweep phase: GC queries all objects and deletes anything not in stale reachable set
        List<Store.ObjectStat> allObjs = session.allObjects();
        List<String> unreachables = new ArrayList<>();
        for (Store.ObjectStat obj : allObjs) {
            if ("commit".equals(obj.type())) {
                if (!staleReachableCommits.contains(obj.hash())) {
                    unreachables.add(obj.hash());
                }
            } else if ("blob".equals(obj.type())) {
                if (!staleReachableBlobs.contains(obj.hash())) {
                    unreachables.add(obj.hash());
                }
            }
        }

        session.deleteObjects(unreachables);

        // 4. Result without mutex: master points to c1, but c1 was deleted!
        assertEquals(c1.getSelfHash(), session.getRef("master"));
        assertFalse(session.hasObject(c1.getSelfHash()), "Demonstrates corruption: c1 was deleted by stale GC sweep!");
        assertFalse(session.hasObject(blobHash), "Demonstrates corruption: blobHash was deleted by stale GC sweep!");
    }

    /**
     * Requirement 2: Grace period preserves recently written objects even if unreachable from refs.
     */
    @Test
    public void testGraceProtectsRecentUnreachableObjects(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        String c0 = initRepo(syncvaultDir);
        Store.Session session = Store.getDefault();

        // Write an orphan blob and orphan commit that are not referenced by any ref
        String orphanBlob = putBlob(session, "orphan content protected by grace\n");
        TreeMap<String, String> map = new TreeMap<>();
        map.put("orphan.txt", orphanBlob);
        Commit orphanCommit = new Commit("orphan commit", map, c0, null);
        orphanCommit.setSelfHash(Utils.hash(Utils.serialize(orphanCommit)));
        session.putCommit(orphanCommit);

        // Run GC with 60 second grace period
        Store.GcResult graceResult = session.gc(60_000);
        assertEquals(0, graceResult.deletedCommits());
        assertEquals(0, graceResult.deletedBlobs());
        assertTrue(session.hasObject(orphanCommit.getSelfHash()), "Commit should be protected by grace period");
        assertTrue(session.hasObject(orphanBlob), "Blob should be protected by grace period");

        // Now run GC with 0 grace period (immediate purge)
        Store.GcResult purgeResult = session.gc(0);
        assertEquals(1, purgeResult.deletedCommits());
        assertEquals(1, purgeResult.deletedBlobs());
        assertFalse(session.hasObject(orphanCommit.getSelfHash()), "Commit should be deleted with 0 grace");
        assertFalse(session.hasObject(orphanBlob), "Blob should be deleted with 0 grace");
    }

    /**
     * Requirement 2: In-flight push tracking protects objects during active push sessions.
     */
    @Test
    public void testInFlightPushProtectsObjects(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);
        Store.Session session = Store.getDefault();

        session.startInFlightPush();
        String inFlightBlob = putBlob(session, "in-flight object data\n");
        assertTrue(session.isObjectInFlight(inFlightBlob));

        // GC with 0 grace while in-flight push is active
        Store.GcResult res = session.gc(0);
        assertEquals(0, res.deletedBlobs());
        assertTrue(session.hasObject(inFlightBlob), "In-flight object should be preserved by GC");

        session.endInFlightPush();
        assertFalse(session.isObjectInFlight(inFlightBlob));

        // GC after in-flight push ended
        Store.GcResult purgeRes = session.gc(0);
        assertEquals(1, purgeRes.deletedBlobs());
        assertFalse(session.hasObject(inFlightBlob), "Object should be deleted once in-flight push ends");
    }

    /**
     * Requirement 1: Stress test with concurrent pushes and GC running simultaneously.
     */
    @Test
    public void testConcurrentPushAndGcHighConcurrency(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        String c0 = initRepo(syncvaultDir);
        Store.Session session = Store.getDefault();

        int pushThreadsCount = 4;
        int operationsPerThread = 15;
        ExecutorService executor = Executors.newFixedThreadPool(pushThreadsCount + 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        List<Future<?>> futures = new ArrayList<>();

        // Start 2 GC worker threads running in loops
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                while (running.get()) {
                    session.gc();
                    Thread.sleep(10);
                }
                return null;
            }));
        }

        // Start push worker threads
        for (int t = 0; t < pushThreadsCount; t++) {
            final int threadId = t;
            final String branchName = "branch-" + threadId;
            futures.add(executor.submit(() -> {
                startLatch.await();
                String parentId = c0;
                for (int op = 0; op < operationsPerThread; op++) {
                    byte[] data = ("thread-" + threadId + "-op-" + op).getBytes(StandardCharsets.UTF_8);
                    String blobHash = Utils.hash(data);
                    TreeMap<String, String> map = new TreeMap<>();
                    map.put("file-" + threadId + ".txt", blobHash);
                    Commit c = new Commit("c-" + threadId + "-" + op, map, parentId, null);
                    c.setSelfHash(Utils.hash(Utils.serialize(c)));

                    try (RepoLock lock = session.acquireLock()) {
                        session.startInFlightPush();
                        try {
                            session.begin();
                            session.putBlob(blobHash, data);
                            session.putCommit(c);
                            session.setRef(branchName, c.getSelfHash());
                            session.commitTx();
                        } finally {
                            session.endInFlightPush();
                        }
                    }
                    parentId = c.getSelfHash();
                }
                return null;
            }));
        }

        // Release all threads to run concurrently
        startLatch.countDown();

        // Wait for push threads to complete
        for (int i = 2; i < futures.size(); i++) {
            futures.get(i).get(30, TimeUnit.SECONDS);
        }

        running.set(false);
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Verify repository integrity for all branch tips
        for (int t = 0; t < pushThreadsCount; t++) {
            String branchName = "branch-" + t;
            String tip = session.getRef(branchName);
            assertNotNull(tip, "Branch " + branchName + " must have a tip");
            assertTrue(session.hasObject(tip), "Tip commit must exist in store: " + tip);

            // Walk back through the commit chain
            String curr = tip;
            int chainLen = 0;
            while (curr != null && !curr.equals(c0)) {
                Commit commit = session.getCommit(curr);
                assertNotNull(commit, "Commit in history must not be missing: " + curr);
                for (String blob : commit.getNameToHash().values()) {
                    assertTrue(session.hasObject(blob), "Blob " + blob + " must exist");
                }
                curr = commit.getParent();
                chainLen++;
            }
            assertEquals(operationsPerThread, chainLen, "Branch chain length mismatch");
        }
    }

    /**
     * Requirement 1 & Netty Server: Concurrent Netty pushes and local GC do not corrupt objects.
     */
    @Test
    public void testNettyServerConcurrentPushAndGc(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server").resolve(".syncvault").toFile();
        File clientDir = tempDir.resolve("client").resolve(".syncvault").toFile();

        // Initialize server repo and start Netty server
        String serverInit = initRepo(serverDir);
        nettyServer = Repository.serve(0);
        int port = nettyServer.getPort();
        assertTrue(port > 0);

        // Initialize client repo
        String clientInit = initRepo(clientDir);
        Repository.add_remote("origin", "127.0.0.1:" + port);

        // Open server session for GC
        Store.Session serverSession = Store.open(serverDir);

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Background GC on server
        Future<?> gcFuture = executor.submit(() -> {
            while (running.get()) {
                serverSession.gc();
                Thread.sleep(15);
            }
            return null;
        });

        // Perform pushes from client to server
        String parent = clientInit;
        for (int i = 0; i < 5; i++) {
            byte[] data = ("content-" + i).getBytes(StandardCharsets.UTF_8);
            String blobHash = Utils.hash(data);
            Store.putBlob(blobHash, data);

            TreeMap<String, String> map = new TreeMap<>();
            map.put("test" + i + ".txt", blobHash);
            Commit c = new Commit("commit " + i, map, parent, null);
            c.setSelfHash(Utils.hash(Utils.serialize(c)));
            Store.putCommit(c);
            Store.setRef("master", c.getSelfHash());
            Repository.currHead = c.getSelfHash();
            parent = c.getSelfHash();

            Repository.push("origin", "master");
        }

        running.set(false);
        gcFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify server has the latest pushed tip and all its objects
        String remoteTip = serverSession.getRef("master");
        assertEquals(parent, remoteTip, "Remote tip should match client latest commit");
        assertTrue(serverSession.hasObject(remoteTip), "Remote tip commit must exist on server");
        Commit tipCommit = serverSession.getCommit(remoteTip);
        assertNotNull(tipCommit);
        for (String blob : tipCommit.getNameToHash().values()) {
            assertTrue(serverSession.hasObject(blob), "Remote blob must exist on server");
        }
    }
}
