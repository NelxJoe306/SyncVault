package gitlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class GcTest {

    @AfterEach
    public void tearDown() {
        Store.closeDefault();
    }

    private String initRepo(File gitletDir) throws Exception {
        gitletDir.mkdirs();
        Store.useDir(gitletDir);
        Store.createSchema();
        String initId = Commit.first_commit();
        Store.setHead("master");
        Repository.currHead = initId;
        return initId;
    }

    private String putBlob(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = Utils.hash(bytes);
        Store.putBlob(hash, bytes);
        return hash;
    }

    @Test
    public void testGcRemovesUnreachableCommitsAndBlobsAndIsIdempotent(@TempDir Path tempDir) throws Exception {
        File gitletDir = tempDir.resolve(".gitlet").toFile();
        String c0 = initRepo(gitletDir);

        // Commit c1 on master with shared.txt and master.txt
        String sharedHash = putBlob("shared content across commits\n");
        String masterHash = putBlob("master only content\n");
        Store.stageAdd("shared.txt", sharedHash);
        Store.stageAdd("master.txt", masterHash);
        String c1 = Commit.commitInCurrBran("c1 on master");

        // Create temp branch and make c2 -> c3
        Commit.branch("temp");
        Store.setHead("temp");
        Repository.currHead = c1;

        String orphanBlob1 = putBlob("orphan blob 1 on temp\n");
        Store.stageAdd("temp1.txt", orphanBlob1);
        String c2 = Commit.commitInCurrBran("c2 on temp");

        String orphanBlob2 = putBlob("orphan blob 2 on temp\n");
        Store.stageAdd("temp2.txt", orphanBlob2);
        String c3 = Commit.commitInCurrBran("c3 on temp");

        // Switch back to master and delete temp branch, leaving c2, c3, orphanBlob1, orphanBlob2 unreachable
        Store.setHead("master");
        Repository.currHead = c1;
        Commit.rm_branch("temp");

        // Also create an unreferenced blob (staged then replaced in stage_add)
        String overwrittenStagedBlob = putBlob("staged once then replaced\n");
        String activeStagedBlob = putBlob("currently staged content\n");
        Store.stageAdd("staged.txt", overwrittenStagedBlob);
        Store.stageAdd("staged.txt", activeStagedBlob);

        // Verify all objects exist before GC
        assertTrue(Store.hasObject(c0));
        assertTrue(Store.hasObject(c1));
        assertTrue(Store.hasObject(c2));
        assertTrue(Store.hasObject(c3));
        assertTrue(Store.hasObject(sharedHash));
        assertTrue(Store.hasObject(masterHash));
        assertTrue(Store.hasObject(orphanBlob1));
        assertTrue(Store.hasObject(orphanBlob2));
        assertTrue(Store.hasObject(overwrittenStagedBlob));
        assertTrue(Store.hasObject(activeStagedBlob));

        // Capture stdout from Repository.gc()
        PrintStream origOut = System.out;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        Store.GcResult res1;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            res1 = Repository.gc();
        } finally {
            System.setOut(origOut);
        }

        String stdout = outBuf.toString(StandardCharsets.UTF_8).trim();
        assertTrue(stdout.contains("removed 5 unreachable objects (2 commits, 3 blobs)"),
                "Unexpected gc output: " + stdout);

        assertEquals(2, res1.deletedCommits());
        assertEquals(3, res1.deletedBlobs());
        assertEquals(5, res1.deletedObjects());
        assertEquals(2, res1.keptCommits()); // c0, c1
        assertEquals(3, res1.keptBlobs());   // sharedHash, masterHash, activeStagedBlob
        assertTrue(res1.reclaimedObjectBytes() > 0);

        // Unreachable commits and blobs must be gone from object, commit_meta, and commit_parent
        assertFalse(Store.hasObject(c2));
        assertFalse(Store.hasObject(c3));
        assertNull(Store.getCommit(c2));
        assertNull(Store.getCommit(c3));
        assertFalse(Store.hasObject(orphanBlob1));
        assertFalse(Store.hasObject(orphanBlob2));
        assertFalse(Store.hasObject(overwrittenStagedBlob));

        List<String> remainingCommitIds = Store.allCommitIds();
        assertTrue(remainingCommitIds.contains(c0));
        assertTrue(remainingCommitIds.contains(c1));
        assertFalse(remainingCommitIds.contains(c2));
        assertFalse(remainingCommitIds.contains(c3));

        Set<String> parentChildren = Store.allCommitParents().stream()
                .map(Store.CommitParent::child)
                .collect(Collectors.toSet());
        assertFalse(parentChildren.contains(c2));
        assertFalse(parentChildren.contains(c3));

        // Reachable commits and blobs (including sharedHash and staged blob) must remain
        assertTrue(Store.hasObject(c0));
        assertTrue(Store.hasObject(c1));
        assertTrue(Store.hasObject(sharedHash));
        assertTrue(Store.hasObject(masterHash));
        assertTrue(Store.hasObject(activeStagedBlob));

        // Second GC must be a no-op (idempotent)
        Store.GcResult res2 = Repository.gc();
        assertEquals(0, res2.deletedCommits());
        assertEquals(0, res2.deletedBlobs());
        assertEquals(0, res2.deletedObjects());
        assertEquals(0L, res2.reclaimedObjectBytes());
        assertEquals(2, res2.keptCommits());
        assertEquals(3, res2.keptBlobs());
    }

    @Test
    public void testGcPreservesSecondParentMergeAndRemoteRefsAndDetachedHead(@TempDir Path tempDir) throws Exception {
        File gitletDir = tempDir.resolve(".gitlet").toFile();
        String c0 = initRepo(gitletDir);

        // Create feature branch with commit cFeature
        Commit.branch("feature");
        Store.setHead("feature");
        Repository.currHead = c0;
        String featureBlob = putBlob("feature branch blob\n");
        Store.stageAdd("feature.txt", featureBlob);
        String cFeature = Commit.commitInCurrBran("feature commit");

        // Switch to master, make cMaster, then create a merge commit with secondParent = cFeature
        Store.setHead("master");
        Repository.currHead = c0;
        String masterBlob = putBlob("master branch blob\n");
        Store.stageAdd("master.txt", masterBlob);
        String cMaster = Commit.commitInCurrBran("master commit");

        TreeMap<String, String> mergedMap = new TreeMap<>();
        mergedMap.put("feature.txt", featureBlob);
        mergedMap.put("master.txt", masterBlob);
        Commit mergeCommit = new Commit("Merged feature into master.", mergedMap, cMaster, cFeature);
        mergeCommit.setSelfHash(Utils.hash(Utils.serialize(mergeCommit)));
        Store.putCommit(mergeCommit);
        String cMerge = mergeCommit.getSelfHash();
        Store.setRef("master", cMerge);
        Repository.currHead = cMerge;

        // Remove feature branch ref; cFeature is now only reachable via secondParent of cMerge
        Commit.rm_branch("feature");

        // Add a remote-tracking ref origin/release pointing to a commit
        String remoteBlob = putBlob("remote tracking blob\n");
        TreeMap<String, String> remoteMap = new TreeMap<>();
        remoteMap.put("release.txt", remoteBlob);
        Commit remoteCommit = new Commit("release commit", remoteMap, c0);
        remoteCommit.setSelfHash(Utils.hash(Utils.serialize(remoteCommit)));
        Store.putCommit(remoteCommit);
        Store.setRef("origin/release", remoteCommit.getSelfHash());

        // Add an unreachable commit by resetting master back? Wait: let's test detached HEAD too!
        String detachedBlob = putBlob("detached head blob\n");
        TreeMap<String, String> detachedMap = new TreeMap<>();
        detachedMap.put("detached.txt", detachedBlob);
        Commit detachedCommit = new Commit("detached commit", detachedMap, c0);
        detachedCommit.setSelfHash(Utils.hash(Utils.serialize(detachedCommit)));
        Store.putCommit(detachedCommit);
        Store.setHead(detachedCommit.getSelfHash()); // detached HEAD pointing directly to commit hash

        // And one truly unreachable commit + blob
        String deadBlob = putBlob("dead blob\n");
        TreeMap<String, String> deadMap = new TreeMap<>();
        deadMap.put("dead.txt", deadBlob);
        Commit deadCommit = new Commit("dead commit", deadMap, c0);
        deadCommit.setSelfHash(Utils.hash(Utils.serialize(deadCommit)));
        Store.putCommit(deadCommit);

        Store.GcResult res = Store.gc();
        assertEquals(1, res.deletedCommits());
        assertEquals(1, res.deletedBlobs());

        // Second parent commit & blob preserved
        assertTrue(Store.hasObject(cFeature));
        assertTrue(Store.hasObject(featureBlob));
        // Remote ref commit & blob preserved
        assertTrue(Store.hasObject(remoteCommit.getSelfHash()));
        assertTrue(Store.hasObject(remoteBlob));
        // Detached HEAD commit & blob preserved
        assertTrue(Store.hasObject(detachedCommit.getSelfHash()));
        assertTrue(Store.hasObject(detachedBlob));
        // Dead commit & blob removed
        assertFalse(Store.hasObject(deadCommit.getSelfHash()));
        assertFalse(Store.hasObject(deadBlob));
    }

    @Test
    public void testGcShrinksDatabaseFileWithVacuumAndCleansOrphanMeta(@TempDir Path tempDir) throws Exception {
        File gitletDir = tempDir.resolve(".gitlet").toFile();
        initRepo(gitletDir);

        // Insert multiple large unreachable blobs (~200 KB total) so SQLite allocates pages
        byte[] largePayload = new byte[64 * 1024];
        for (int i = 0; i < 3; i++) {
            largePayload[0] = (byte) (i + 1);
            largePayload[largePayload.length - 1] = (byte) (i + 10);
            String h = Utils.hash(largePayload);
            Store.putBlob(h, largePayload);
        }

        // Insert dangling rows in commit_meta and commit_parent that have no object row
        try (PreparedStatement psMeta = Store.getDefault().getConnection().prepareStatement(
                "INSERT INTO commit_meta (hash, message, timestamp) VALUES ('dangling_commit', 'dangling', 123)");
             PreparedStatement psParent = Store.getDefault().getConnection().prepareStatement(
                     "INSERT INTO commit_parent (child, parent, idx) VALUES ('dangling_commit', 'nonexistent_parent', 0)")) {
            psMeta.executeUpdate();
            psParent.executeUpdate();
        }
        assertTrue(Store.allCommitIds().contains("dangling_commit"));

        // Ensure working-tree file in tempDir is not touched by GC
        Path workingFile = tempDir.resolve("untracked_work.txt");
        Files.writeString(workingFile, "do not touch working tree\n", StandardCharsets.UTF_8);

        Store.GcResult res = Store.gc();
        assertEquals(3, res.deletedBlobs());
        assertEquals(3L * 64 * 1024, res.reclaimedObjectBytes());
        assertTrue(res.dbBytesBefore() > res.dbBytesAfter(),
                "Expected VACUUM to shrink gitlet.db: before=" + res.dbBytesBefore() + ", after=" + res.dbBytesAfter());

        // Dangling metadata rows must be cleaned up
        assertFalse(Store.allCommitIds().contains("dangling_commit"));
        assertTrue(Store.allCommitParents().isEmpty());

        // Working tree file remains intact
        assertTrue(Files.exists(workingFile));
        assertEquals("do not touch working tree\n", Files.readString(workingFile, StandardCharsets.UTF_8));
    }
}
