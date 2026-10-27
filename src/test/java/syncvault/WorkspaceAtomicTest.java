package syncvault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WorkspaceAtomicTest {

    @AfterEach
    public void tearDown() {
        WorkspaceJournal.testInterruptHook = null;
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

    private void commitFile(String fileName, String content, String message) throws Exception {
        File f = new File(Repository.getCwd(), fileName);
        Utils.writeContents(f, content.getBytes(StandardCharsets.UTF_8));
        Repository.add(fileName);
        Commit.commitInCurrBran(message);
    }

    @Test
    public void testAtomicWriteNeverTruncatesTargetOnFailure(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        File target = tempDir.resolve("important.txt").toFile();
        String initialContent = "Original intact data";
        Utils.writeContents(target, initialContent.getBytes(StandardCharsets.UTF_8));

        // Verify initial content
        assertEquals(initialContent, Utils.readContentsAsString(target));

        // Intentionally throw an exception during byte writing to simulate an interrupt/failure
        Object badObject = new Object() {
            @Override
            public String toString() {
                throw new RuntimeException("Simulated IO failure mid-write");
            }
        };

        assertThrows(RuntimeException.class, () -> Utils.writeContents(target, badObject));

        // Crucial acceptance check: target file was NOT truncated or corrupted
        assertTrue(target.exists(), "Target file must still exist");
        assertEquals(initialContent, Utils.readContentsAsString(target),
                "Target file content must remain completely intact and uncorrupted");
    }

    @Test
    public void testCheckoutBranchInterruptedCanAbort(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        // Commit on master
        commitFile("master.txt", "master content\n", "c1 on master");
        commitFile("common.txt", "v1 common\n", "c2 on master");

        // Create feature branch
        Commit.branch("feature");
        Repository.check_branch("feature");

        // Commit on feature: modify common.txt, add feature.txt, delete master.txt
        commitFile("common.txt", "v2 common from feature\n", "c3 on feature");
        commitFile("feature.txt", "feature content\n", "c4 on feature");
        Repository.rm("master.txt");
        Commit.commitInCurrBran("remove master.txt on feature");

        // Switch back to master
        Repository.check_branch("master");
        assertEquals("master", Store.getHead());
        assertEquals("v1 common\n", Utils.readContentsAsString(new File(Repository.getCwd(), "common.txt")));
        assertTrue(new File(Repository.getCwd(), "master.txt").exists());
        assertFalse(new File(Repository.getCwd(), "feature.txt").exists());

        // Now simulate an interrupt during checkout of feature branch
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash right after worktree update before DB commit");
            }
        };

        RuntimeException ex = assertThrows(RuntimeException.class, () -> Repository.check_branch("feature"));
        assertEquals("Simulated crash right after worktree update before DB commit", ex.getMessage());

        // Verify journal is recorded and pending
        assertTrue(WorkspaceJournal.hasPending(), "WorkspaceJournal must be pending");

        // Verify status detects the interrupted state
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            Repository.status();
        } finally {
            System.setOut(origOut);
        }
        assertTrue(outBuf.toString().contains("An interrupted workspace update was detected"),
                "status must report explicit recoverable state");

        // Reset hook and test abort
        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.abort();

        assertFalse(WorkspaceJournal.hasPending(), "Journal should be deleted after abort");
        assertEquals("master", Store.getHead(), "Branch should remain master after abort");
        assertEquals("v1 common\n", Utils.readContentsAsString(new File(Repository.getCwd(), "common.txt")));
        assertTrue(new File(Repository.getCwd(), "master.txt").exists(), "master.txt should be restored");
        assertFalse(new File(Repository.getCwd(), "feature.txt").exists(), "feature.txt should not exist");
    }

    @Test
    public void testCheckoutBranchInterruptedCanContinue(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("common.txt", "v1 common\n", "c1 on master");
        commitFile("file_a.txt", "content a\n", "c2 on master");

        Commit.branch("feature");
        Repository.check_branch("feature");
        commitFile("common.txt", "v2 common on feature\n", "c3 on feature");
        commitFile("file_b.txt", "content b\n", "c4 on feature");
        Repository.rm("file_a.txt");
        Commit.commitInCurrBran("c5 remove file_a on feature");

        Repository.check_branch("master");

        // Simulate interrupt right before DB commit
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash before DB update");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.check_branch("feature"));
        assertTrue(WorkspaceJournal.hasPending());

        // Continue the operation
        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.continueOp();

        assertFalse(WorkspaceJournal.hasPending(), "Journal should be deleted after continue");
        assertEquals("feature", Store.getHead(), "Branch should be advanced to feature");
        assertEquals("v2 common on feature\n", Utils.readContentsAsString(new File(Repository.getCwd(), "common.txt")));
        assertTrue(new File(Repository.getCwd(), "file_b.txt").exists(), "file_b.txt must exist");
        assertFalse(new File(Repository.getCwd(), "file_a.txt").exists(), "file_a.txt must be deleted");
    }

    @Test
    public void testResetInterruptedCanAbortAndContinue(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("f1.txt", "initial f1\n", "commit 1");
        String c1 = Repository.currHead;

        commitFile("f1.txt", "updated f1\n", "commit 2");
        commitFile("f2.txt", "new f2\n", "commit 2 add f2");
        String c2 = Repository.currHead;

        // 1. Simulate interrupt during reset to c1, then abort
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during reset");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.reset(c1));
        assertTrue(WorkspaceJournal.hasPending());

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.abort();

        assertFalse(WorkspaceJournal.hasPending());
        assertEquals(c2, Store.getRef("master"), "Ref should still be c2 after abort");
        assertEquals("updated f1\n", Utils.readContentsAsString(new File(Repository.getCwd(), "f1.txt")));
        assertTrue(new File(Repository.getCwd(), "f2.txt").exists());

        // 2. Simulate interrupt during reset to c1, then continue
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during reset 2");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.reset(c1));
        assertTrue(WorkspaceJournal.hasPending());

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.continueOp();

        assertFalse(WorkspaceJournal.hasPending());
        assertEquals(c1, Store.getRef("master"), "Ref should be c1 after continue");
        assertEquals("initial f1\n", Utils.readContentsAsString(new File(Repository.getCwd(), "f1.txt")));
        assertFalse(new File(Repository.getCwd(), "f2.txt").exists(), "f2.txt should be deleted in c1");
    }

    @Test
    public void testMergeInterruptedCanAbortAndContinue(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("base.txt", "base data\n", "base commit");
        String baseCommit = Repository.currHead;

        Commit.branch("other");
        commitFile("master_file.txt", "master data\n", "master branch commit");
        String masterTip = Repository.currHead;

        Repository.check_branch("other");
        commitFile("other_file.txt", "other data\n", "other branch commit");
        String otherTip = Repository.currHead;

        Repository.check_branch("master");

        // 1. Interrupt merge before DB commit, then abort
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during merge");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.merge("other"));
        assertTrue(WorkspaceJournal.hasPending());

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.abort();

        assertFalse(WorkspaceJournal.hasPending());
        assertEquals(masterTip, Store.getRef("master"), "master should remain at pre-merge commit");
        assertTrue(new File(Repository.getCwd(), "master_file.txt").exists());
        assertFalse(new File(Repository.getCwd(), "other_file.txt").exists());

        // 2. Interrupt merge again, then continue
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during merge 2");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.merge("other"));
        assertTrue(WorkspaceJournal.hasPending());

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.continueOp();

        assertFalse(WorkspaceJournal.hasPending());
        assertNotEquals(masterTip, Store.getRef("master"), "master should be advanced to merge commit");
        assertTrue(new File(Repository.getCwd(), "master_file.txt").exists());
        assertTrue(new File(Repository.getCwd(), "other_file.txt").exists());
        assertTrue(new File(Repository.getCwd(), "base.txt").exists());
    }

    @Test
    public void testCleanOperationsLeaveNoJournalOrTempFiles(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("file1.txt", "content 1\n", "c1");
        commitFile("file2.txt", "content 2\n", "c2");

        Commit.branch("feature");
        Repository.check_branch("feature");
        commitFile("file3.txt", "content 3\n", "c3");

        Repository.check_branch("master");

        assertFalse(WorkspaceJournal.hasPending(), "No pending journal after clean checkout");
        File tmpDir = new File(syncvaultDir, WorkspaceJournal.TMP_DIR_NAME);
        if (tmpDir.exists()) {
            File[] files = tmpDir.listFiles();
            assertTrue(files == null || files.length == 0, "tmp dir should be empty after clean checkout");
        }
    }

    @Test
    public void testCliAbortAndContinueCommands(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("doc.txt", "version 1\n", "v1");
        Commit.branch("feat");
        Repository.check_branch("feat");
        commitFile("doc.txt", "version 2\n", "v2");
        Repository.check_branch("master");

        // Interrupt checkout
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-journal-created".equals(point)) {
                throw new RuntimeException("Simulated crash");
            }
        };
        assertThrows(RuntimeException.class, () -> Repository.check_branch("feat"));
        assertTrue(WorkspaceJournal.hasPending());

        // Test Main.main(["abort"])
        WorkspaceJournal.testInterruptHook = null;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            Main.main(new String[]{"abort"});
        } finally {
            System.setOut(origOut);
        }

        assertTrue(outBuf.toString().contains("Interrupted workspace update aborted."));
        assertFalse(WorkspaceJournal.hasPending());

        // Interrupt again
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-journal-created".equals(point)) {
                throw new RuntimeException("Simulated crash 2");
            }
        };
        assertThrows(RuntimeException.class, () -> Repository.check_branch("feat"));
        assertTrue(WorkspaceJournal.hasPending());

        // Test Main.main(["recover", "--continue"])
        WorkspaceJournal.testInterruptHook = null;
        outBuf.reset();
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            Main.main(new String[]{"recover", "--continue"});
        } finally {
            System.setOut(origOut);
        }

        assertTrue(outBuf.toString().contains("Interrupted workspace update continued."));
        assertFalse(WorkspaceJournal.hasPending());
        assertEquals("feat", Store.getHead());
    }

    @Test
    public void testSimulatedInterruptDuringTempFileWriteLeavesWorktreeUntouched(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("doc.txt", "v1\n", "v1");
        Commit.branch("feat");
        Repository.check_branch("feat");
        commitFile("doc.txt", "v2\n", "v2");
        Repository.check_branch("master");

        // Interrupt before journal is even created
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-temp-prepared".equals(point)) {
                throw new RuntimeException("Simulated crash while preparing temp files");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.check_branch("feat"));

        // No journal should exist because crash happened before journal creation
        assertFalse(WorkspaceJournal.hasPending());
        // Master worktree should be completely untouched
        assertEquals("v1\n", Utils.readContentsAsString(new File(Repository.getCwd(), "doc.txt")));
        assertEquals("master", Store.getHead());
    }

    @Test
    public void testFastForwardMergeInterruptedCanAbortAndContinue(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("common.txt", "common v1\n", "c1");
        Commit.branch("feature");
        Repository.check_branch("feature");
        commitFile("feature.txt", "feature v1\n", "c2 on feature");
        String featureTip = Repository.currHead;

        Repository.check_branch("master");

        // In master, merging feature is a fast-forward!
        // Simulate interrupt during the fast-forward update
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during fast-forward merge");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.merge("feature"));
        assertTrue(WorkspaceJournal.hasPending());

        // Abort rolls back
        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.abort();
        assertFalse(WorkspaceJournal.hasPending());
        assertFalse(new File(Repository.getCwd(), "feature.txt").exists());

        // Now interrupt again and continue
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during fast-forward merge 2");
            }
        };
        assertThrows(RuntimeException.class, () -> Repository.merge("feature"));
        assertTrue(WorkspaceJournal.hasPending());

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.continueOp();
        assertFalse(WorkspaceJournal.hasPending());
        assertTrue(new File(Repository.getCwd(), "feature.txt").exists());
        assertEquals(featureTip, Store.getRef("master"));
    }

    @Test
    public void testCheckAndRecoverApi(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("file.txt", "v1\n", "v1");
        Commit.branch("b1");
        Repository.check_branch("b1");
        commitFile("file.txt", "v2\n", "v2");
        Repository.check_branch("master");

        // checkAndRecover returns false when no pending journal
        assertFalse(WorkspaceJournal.checkAndRecover(false));
        assertFalse(WorkspaceJournal.checkAndRecover(true));

        // Create pending journal
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-journal-created".equals(point)) {
                throw new RuntimeException("crash");
            }
        };
        assertThrows(RuntimeException.class, () -> Repository.check_branch("b1"));
        assertTrue(WorkspaceJournal.hasPending());

        WorkspaceJournal.testInterruptHook = null;
        // checkAndRecover(false) should abort
        assertTrue(WorkspaceJournal.checkAndRecover(false));
        assertFalse(WorkspaceJournal.hasPending());
        assertEquals("master", Store.getHead());
    }

    @Test
    public void testCliNoPendingMessagesAndUsage(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            Main.main(new String[]{"abort"});
            assertTrue(outBuf.toString().contains("No interrupted workspace update to abort."));

            outBuf.reset();
            Main.main(new String[]{"continue"});
            assertTrue(outBuf.toString().contains("No interrupted workspace update to continue."));

            outBuf.reset();
            Main.main(new String[]{"recover"});
            assertTrue(outBuf.toString().contains("No interrupted workspace update to recover."));
        } finally {
            System.setOut(origOut);
        }
    }

    @Test
    public void testInterruptAfterDbCommitAbortDoesNotUndoSuccessState(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("base.txt", "v1\n", "base commit");
        Commit.branch("feature");
        Repository.check_branch("feature");
        commitFile("feature.txt", "feature data\n", "feature commit");
        Repository.check_branch("master");

        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-db-commit".equals(point)) {
                throw new RuntimeException("Simulated crash right after DB commit before journal cleanup");
            }
        };

        RuntimeException ex = assertThrows(RuntimeException.class, () -> Repository.check_branch("feature"));
        assertEquals("Simulated crash right after DB commit before journal cleanup", ex.getMessage());

        assertTrue(WorkspaceJournal.hasPending(), "Journal should be pending when interrupted at after-db-commit");

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.abort();

        assertFalse(WorkspaceJournal.hasPending(), "Journal must be cleaned up after abort");
        assertEquals("feature", Store.getHead(), "abort after successful DB commit must NOT undo branch switch");
        assertTrue(new File(Repository.getCwd(), "feature.txt").exists(), "feature.txt must still exist");
    }

    @Test
    public void testInterruptAfterDbCommitContinueLeavesSuccessState(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("base.txt", "v1\n", "base commit");
        Commit.branch("feature");
        Repository.check_branch("feature");
        commitFile("feature.txt", "feature data\n", "feature commit");
        Repository.check_branch("master");

        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-db-commit".equals(point)) {
                throw new RuntimeException("Simulated crash right after DB commit before journal cleanup");
            }
        };

        RuntimeException ex = assertThrows(RuntimeException.class, () -> Repository.check_branch("feature"));
        assertEquals("Simulated crash right after DB commit before journal cleanup", ex.getMessage());

        assertTrue(WorkspaceJournal.hasPending(), "Journal should be pending when interrupted at after-db-commit");

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.continueOp();

        assertFalse(WorkspaceJournal.hasPending(), "Journal must be cleaned up after continue");
        assertEquals("feature", Store.getHead(), "continue after successful DB commit must leave target branch");
        assertTrue(new File(Repository.getCwd(), "feature.txt").exists(), "feature.txt must still exist");
    }

    @Test
    public void testMergeGcRaceAndPendingRecovery(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("common.txt", "line1\nline2\n", "base commit");
        Commit.branch("other");
        commitFile("common.txt", "master line\nline2\n", "master commit");
        String masterTip = Repository.currHead;

        Repository.check_branch("other");
        commitFile("common.txt", "other line\nline2\n", "other commit");

        Repository.check_branch("master");

        // Interrupt merge during conflict resolution after worktree applied
        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during merge conflict apply");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.merge("other"));
        assertTrue(WorkspaceJournal.hasPending());

        // Concurrent GC with 0 grace period runs while operation is pending
        Store.getDefault().gc(0L);

        // Continue must successfully complete the merge without missing blobs
        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.continueOp();

        assertFalse(WorkspaceJournal.hasPending(), "Journal must be cleared after continue");
        assertNotEquals(masterTip, Store.getRef("master"), "master must be updated to merge commit");

        String content = Utils.readContentsAsString(new File(Repository.getCwd(), "common.txt"));
        assertTrue(content.contains("<<<<<<< HEAD"), "Conflict markers must be present");
        assertTrue(content.contains("master line"));
        assertTrue(content.contains("other line"));
    }

    @Test
    public void testMergeInterruptedBeforeDbCommitAbortLeavesNoOrphan(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("base.txt", "base data\n", "base commit");
        Commit.branch("b1");
        commitFile("file1.txt", "data 1\n", "commit 1");
        String masterTip = Repository.currHead;

        Repository.check_branch("b1");
        commitFile("file2.txt", "data 2\n", "commit 2");

        Repository.check_branch("master");

        WorkspaceJournal.testInterruptHook = point -> {
            if ("after-worktree-applied".equals(point)) {
                throw new RuntimeException("Simulated crash during merge");
            }
        };

        assertThrows(RuntimeException.class, () -> Repository.merge("b1"));
        assertTrue(WorkspaceJournal.hasPending());

        WorkspaceJournal.testInterruptHook = null;
        WorkspaceJournal.abort();

        assertFalse(WorkspaceJournal.hasPending());
        assertEquals(masterTip, Store.getRef("master"), "master should remain at pre-merge commit");

        // GC with 0 grace period: should find 0 unreachable commits because mergeCommit was never inserted
        Store.GcResult gcRes = Store.getDefault().gc(0L);
        assertEquals(0, gcRes.deletedCommits(), "No orphan merge commit should have been left behind");
    }

    @Test
    public void testCheckoutOverwritesDirtyFileWhenHashMatches(@TempDir Path tempDir) throws Exception {
        File syncvaultDir = tempDir.resolve(".syncvault").toFile();
        initRepo(syncvaultDir);

        commitFile("shared.txt", "clean content\n", "base commit");
        Commit.branch("feature");
        Repository.check_branch("feature");
        commitFile("feature_only.txt", "feat\n", "feat commit");
        Repository.check_branch("master");

        // Dirty the tracked shared.txt on disk without committing
        File diskFile = new File(Repository.getCwd(), "shared.txt");
        Utils.writeContents(diskFile, "dirty modified content\n".getBytes(StandardCharsets.UTF_8));

        // Checkout feature: targetHash == currHash, but on-disk content differs. Must overwrite from blob!
        Repository.check_branch("feature");

        assertEquals("clean content\n", Utils.readContentsAsString(diskFile),
                "checkout must overwrite dirty file from blob even when targetHash equals currHash");
    }
}
