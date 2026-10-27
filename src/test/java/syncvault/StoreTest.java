package syncvault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

public class StoreTest {

    @Test
    public void testBlobStorage(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        byte[] data = "test blob content".getBytes(StandardCharsets.UTF_8);
        String hash = Utils.hash(data);

        assertFalse(store.hasObject(hash));
        assertNull(store.getBlob(hash));

        store.putBlob(hash, data);

        assertTrue(store.hasObject(hash));
        assertArrayEquals(data, store.getBlob(hash));

        // Duplicate putBlob is idempotent (INSERT OR IGNORE)
        store.putBlob(hash, data);
        assertArrayEquals(data, store.getBlob(hash));

        store.close();
    }

    @Test
    public void testCommitStorageAndQueries(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        // 1. Initial commit (no parents)
        Commit c0 = new Commit("initial commit", new TreeMap<>(), null);
        c0.setSelfHash("c0");
        store.putCommit(c0);

        Commit fetched0 = store.getCommit("c0");
        assertNotNull(fetched0);
        assertEquals("initial commit", fetched0.getMessage());
        assertNull(fetched0.getParent());
        assertNull(fetched0.getSecondParent());
        assertEquals("c0", fetched0.getSelfHash());

        // 2. Commit with single parent
        Commit c1 = new Commit("commit 1", new TreeMap<>(), "c0");
        c1.setSelfHash("c1");
        store.putCommit(c1);

        Commit fetched1 = store.getCommit("c1");
        assertNotNull(fetched1);
        assertEquals("commit 1", fetched1.getMessage());
        assertEquals("c0", fetched1.getParent());
        assertNull(fetched1.getSecondParent());

        // 3. Commit with two parents
        Commit c2 = new Commit("merge commit", new TreeMap<>(), "c1", "c0");
        c2.setSelfHash("c2");
        store.putCommit(c2);

        Commit fetched2 = store.getCommit("c2");
        assertNotNull(fetched2);
        assertEquals("merge commit", fetched2.getMessage());
        assertEquals("c1", fetched2.getParent());
        assertEquals("c0", fetched2.getSecondParent());

        // 4. Query commits
        assertEquals(List.of("c0", "c1", "c2"), store.allCommitIds());
        assertEquals(List.of("c1"), store.commitIdsWithMessage("commit 1"));
        assertEquals(List.of("c1"), store.commitIdsWithPrefix("c1"));
        assertEquals(List.of("c0", "c1", "c2"), store.commitIdsWithPrefix("c"));
        assertTrue(store.commitIdsWithMessage("unknown").isEmpty());

        store.close();
    }

    @Test
    public void testRefOperations(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        assertNull(store.getRef("master"));
        store.setRef("master", "commit_a");
        assertEquals("commit_a", store.getRef("master"));

        // Update existing ref
        store.setRef("master", "commit_b");
        assertEquals("commit_b", store.getRef("master"));

        // Remote tracking ref
        store.setRef("origin/master", "commit_b");
        assertEquals("commit_b", store.getRef("origin/master"));

        assertEquals(List.of("master", "origin/master"), store.allRefNames());

        store.deleteRef("origin/master");
        assertNull(store.getRef("origin/master"));
        assertEquals(List.of("master"), store.allRefNames());

        store.close();
    }

    @Test
    public void testHeadOperations(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        assertNull(store.getHead());
        store.setHead("master");
        assertEquals("master", store.getHead());

        store.setHead("feature");
        assertEquals("feature", store.getHead());

        store.close();
    }

    @Test
    public void testStageTables(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        assertTrue(store.stagedAdditions().isEmpty());
        assertTrue(store.stagedRemovals().isEmpty());

        store.stageAdd("file1.txt", "hash1");
        store.stageAdd("file2.txt", "hash2");

        TreeMap<String, String> additions = store.stagedAdditions();
        assertEquals(2, additions.size());
        assertEquals("hash1", additions.get("file1.txt"));
        assertEquals("hash2", additions.get("file2.txt"));

        store.unstageAdd("file1.txt");
        assertEquals(1, store.stagedAdditions().size());
        assertFalse(store.stagedAdditions().containsKey("file1.txt"));

        store.stageRemove("file3.txt");
        store.stageRemove("file4.txt");
        assertEquals(List.of("file3.txt", "file4.txt"), store.stagedRemovals());

        store.unstageRemove("file3.txt");
        assertEquals(List.of("file4.txt"), store.stagedRemovals());

        store.clearStage();
        assertTrue(store.stagedAdditions().isEmpty());
        assertTrue(store.stagedRemovals().isEmpty());

        store.close();
    }

    @Test
    public void testAncestorsAndFirstParentChain(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        // c0 -> c1 -> c2
        Commit c0 = new Commit("c0", new TreeMap<>(), null);
        c0.setSelfHash("c0");
        store.putCommit(c0);

        Commit c1 = new Commit("c1", new TreeMap<>(), "c0");
        c1.setSelfHash("c1");
        store.putCommit(c1);

        Commit c2 = new Commit("c2", new TreeMap<>(), "c1");
        c2.setSelfHash("c2");
        store.putCommit(c2);

        Map<String, Integer> anc = store.ancestors("c2");
        assertEquals(3, anc.size());
        assertEquals(0, anc.get("c2"));
        assertEquals(1, anc.get("c1"));
        assertEquals(2, anc.get("c0"));

        List<String> chain = store.firstParentChain("c2");
        assertEquals(List.of("c2", "c1", "c0"), chain);

        store.close();
    }

    @Test
    public void testSplitPointLinear(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        // A -> B -> C
        Commit a = new Commit("a", new TreeMap<>(), null);
        a.setSelfHash("a");
        store.putCommit(a);

        Commit b = new Commit("b", new TreeMap<>(), "a");
        b.setSelfHash("b");
        store.putCommit(b);

        Commit c = new Commit("c", new TreeMap<>(), "b");
        c.setSelfHash("c");
        store.putCommit(c);

        assertEquals("b", store.splitPoint("b", "c"));
        assertEquals("b", store.splitPoint("c", "b"));
        assertEquals("a", store.splitPoint("a", "c"));
        assertEquals("c", store.splitPoint("c", "c"));

        store.close();
    }

    @Test
    public void testSplitPointFork(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        // A -> B, A -> C
        Commit a = new Commit("a", new TreeMap<>(), null);
        a.setSelfHash("a");
        store.putCommit(a);

        Commit b = new Commit("b", new TreeMap<>(), "a");
        b.setSelfHash("b");
        store.putCommit(b);

        Commit c = new Commit("c", new TreeMap<>(), "a");
        c.setSelfHash("c");
        store.putCommit(c);

        assertEquals("a", store.splitPoint("b", "c"));
        assertEquals("a", store.splitPoint("c", "b"));

        store.close();
    }

    @Test
    public void testSplitPointCrissCross(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        // Root commit A
        Commit a = new Commit("a", new TreeMap<>(), null);
        a.setSelfHash("a");
        store.putCommit(a);

        // Branch 1: B
        Commit b = new Commit("b", new TreeMap<>(), "a");
        b.setSelfHash("b");
        store.putCommit(b);

        // Branch 2: C
        Commit c = new Commit("c", new TreeMap<>(), "a");
        c.setSelfHash("c");
        store.putCommit(c);

        // Criss-cross merges:
        // D merges (B, C)
        Commit d = new Commit("d", new TreeMap<>(), "b", "c");
        d.setSelfHash("d");
        store.putCommit(d);

        // E merges (C, B)
        Commit e = new Commit("e", new TreeMap<>(), "c", "b");
        e.setSelfHash("e");
        store.putCommit(e);

        // For D and E, common ancestors are B and C (A is eliminated as it is below B and C).
        // Distance from D to B is 1, D to C is 1.
        // Tie-breaker: smaller hash ("b" < "c") -> "b"
        assertEquals("b", store.splitPoint("d", "e"));
        assertEquals("b", store.splitPoint("e", "d"));

        // Asymmetric criss-cross:
        // A -> b1 -> b2
        // A -> c1
        // d2 merges (b2, c1) -> dist(d2, b1) = 2, dist(d2, c1) = 1
        // e2 merges (c1, b1) -> dist(e2, b1) = 1, dist(e2, c1) = 1
        Commit b1 = new Commit("b1", new TreeMap<>(), "a");
        b1.setSelfHash("b1");
        store.putCommit(b1);

        Commit b2 = new Commit("b2", new TreeMap<>(), "b1");
        b2.setSelfHash("b2");
        store.putCommit(b2);

        Commit c1 = new Commit("c1", new TreeMap<>(), "a");
        c1.setSelfHash("c1");
        store.putCommit(c1);

        Commit d2 = new Commit("d2", new TreeMap<>(), "b2", "c1");
        d2.setSelfHash("d2");
        store.putCommit(d2);

        Commit e2 = new Commit("e2", new TreeMap<>(), "c1", "b1");
        e2.setSelfHash("e2");
        store.putCommit(e2);

        // From d2: c1 has distance 1, b1 has distance 2.
        // Minimum distance to d2 wins -> c1
        assertEquals("c1", store.splitPoint("d2", "e2"));

        store.close();
    }

    @Test
    public void testGraphMetadataQueries(@TempDir Path tempDir) {
        Store.Session store = Store.open(tempDir.toFile());
        store.createSchema();

        Commit a = new Commit("root", new TreeMap<>(), null);
        a.setSelfHash("a");
        store.putCommit(a);

        Commit b = new Commit("branch1", new TreeMap<>(), "a");
        b.setSelfHash("b");
        store.putCommit(b);

        Commit m = new Commit("merge", new TreeMap<>(), "b", "a");
        m.setSelfHash("m");
        store.putCommit(m);

        store.setRef("master", "m");
        store.setRef("dev", "b");

        List<Store.CommitMeta> metas = store.allCommitMetas();
        assertEquals(3, metas.size());

        List<Store.CommitParent> parents = store.allCommitParents();
        assertEquals(3, parents.size());

        Map<String, String> refs = store.allRefs();
        assertEquals(Map.of("dev", "b", "master", "m"), refs);

        store.close();
    }
}
