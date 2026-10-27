package syncvault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FsckTest {

    @AfterEach
    public void tearDown() {
        Store.closeDefault();
    }

    private String initRepo(Path tempDir) throws Exception {
        Path syncvaultDir = tempDir.resolve(".syncvault");
        Files.createDirectories(syncvaultDir);
        Store.useDir(syncvaultDir.toFile());
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

    private void overwriteObject(String hash, byte[] data) throws Exception {
        try (PreparedStatement ps = Store.getDefault().getConnection().prepareStatement(
                "UPDATE object SET data = ? WHERE hash = ?")) {
            ps.setBytes(1, data);
            ps.setString(2, hash);
            assertEquals(1, ps.executeUpdate());
        }
    }

    @Test
    public void cleanRepositoryPasses(@TempDir Path tempDir) throws Exception {
        String c0 = initRepo(tempDir);
        String blob = putBlob("hello\n");
        Store.stageAdd("hello.txt", blob);
        String c1 = Commit.commitInCurrBran("add hello");
        String staged = putBlob("still staged\n");
        Store.stageAdd("staged.txt", staged);

        Store.FsckResult result = Store.fsck();
        assertTrue(result.ok(), result.errors().toString());
        assertTrue(result.errors().isEmpty());

        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            assertTrue(Repository.fsck().ok());
        } finally {
            System.setOut(orig);
        }
        String stdout = buf.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.contains("fsck: ok"), stdout);
        assertTrue(Store.hasObject(c0));
        assertTrue(Store.hasObject(c1));
        assertArrayEquals("hello\n".getBytes(StandardCharsets.UTF_8), Store.getBlob(blob));
    }

    @Test
    public void reportsMissingBlobAndLeavesRepositoryUnchanged(@TempDir Path tempDir) throws Exception {
        initRepo(tempDir);
        String blob = putBlob("hello blob\n");
        Store.stageAdd("hello.txt", blob);
        String commit = Commit.commitInCurrBran("add hello");
        assertTrue(Store.fsck().ok());

        Store.deleteObjects(List.of(blob));
        Store.FsckResult result = Store.fsck();

        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e ->
                        e.contains("missing blob") && e.contains(blob) && e.contains(commit)),
                result.errors().toString());
        assertFalse(Store.hasObject(blob));
        assertNotNull(Store.getCommit(commit));
        Store.FsckResult again = Store.fsck();
        assertEquals(result.errors(), again.errors());
    }

    @Test
    public void reportsMissingParentAndDanglingRef(@TempDir Path tempDir) throws Exception {
        String parent = initRepo(tempDir);
        String blob = putBlob("x\n");
        Store.stageAdd("x.txt", blob);
        String child = Commit.commitInCurrBran("child");
        Store.setRef("gone", "deadbeef");
        Store.deleteObjects(List.of(parent));

        Store.FsckResult result = Store.fsck();
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e ->
                        e.contains("missing parent") && e.contains(parent) && e.contains(child)),
                result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e ->
                        e.contains("ref gone") && e.contains("points to missing commit") && e.contains("deadbeef")),
                result.errors().toString());
        assertNull(Store.getCommit(parent));
        assertEquals(child, Store.getRef("master"));
    }

    @Test
    public void reportsCorruptedContentAndDoesNotRepair(@TempDir Path tempDir) throws Exception {
        initRepo(tempDir);
        String blob = putBlob("hello blob\n");
        Store.stageAdd("hello.txt", blob);
        String commit = Commit.commitInCurrBran("add hello");

        byte[] corruptBlob = "corrupted".getBytes(StandardCharsets.UTF_8);
        overwriteObject(blob, corruptBlob);
        Store.FsckResult blobResult = Store.fsck();
        assertFalse(blobResult.ok());
        assertTrue(blobResult.errors().stream().anyMatch(e ->
                        e.contains(blob) && e.contains("content hash mismatch") && e.contains("blob")),
                blobResult.errors().toString());
        assertArrayEquals(corruptBlob, Store.getBlob(blob));

        byte[] corruptCommit = new byte[] {'S', 'V', 'C', '1', 0, 1};
        overwriteObject(commit, corruptCommit);
        Store.FsckResult commitResult = Store.fsck();
        assertFalse(commitResult.ok());
        assertTrue(commitResult.errors().stream().anyMatch(e ->
                        e.contains(commit) && e.contains("content hash mismatch") && e.contains("commit")),
                commitResult.errors().toString());
        assertArrayEquals(corruptCommit, Store.getBlob(commit));
        assertArrayEquals(corruptBlob, Store.getBlob(blob));
    }

    @Test
    public void cliExitsNonZeroOnCorruptionAndRejectsFix(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        Cli clean = run(repo, "init");
        assertEquals(0, clean.exit, clean.output);
        Files.writeString(repo.resolve("hello.txt"), "hello\n");
        assertEquals(0, run(repo, "add", "hello.txt").exit);
        assertEquals(0, run(repo, "commit", "add hello").exit);

        Cli ok = run(repo, "fsck");
        assertEquals(0, ok.exit, ok.output);
        assertTrue(ok.output.contains("fsck: ok"), ok.output);

        Store.useDir(repo.resolve(".syncvault").toFile());
        String blobHash = null;
        for (Store.ObjectStat obj : Store.allObjects()) {
            if ("blob".equals(obj.type())) {
                blobHash = obj.hash();
                break;
            }
        }
        assertNotNull(blobHash);
        byte[] corrupt = new byte[] {9, 9, 9};
        overwriteObject(blobHash, corrupt);
        Store.closeDefault();

        Cli bad = run(repo, "fsck");
        assertEquals(1, bad.exit, bad.output);
        assertTrue(bad.output.contains(blobHash), bad.output);
        assertTrue(bad.output.contains("content hash mismatch"), bad.output);
        assertTrue(bad.output.contains("fsck: found "), bad.output);

        Cli fix = run(repo, "fsck", "--fix");
        assertEquals(1, fix.exit, fix.output);
        assertTrue(fix.output.contains("Incorrect operands."), fix.output);

        Store.useDir(repo.resolve(".syncvault").toFile());
        assertArrayEquals(corrupt, Store.getBlob(blobHash));
    }

    private Cli run(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("syncvault.Main");
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new Cli(exit, output);
    }

    private record Cli(int exit, String output) {}
}
