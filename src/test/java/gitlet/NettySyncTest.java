package gitlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class NettySyncTest {

    private NettyServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
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

    @Test
    public void testPushOverNetty(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server/.gitlet").toFile();
        File clientDir = tempDir.resolve("client/.gitlet").toFile();

        initRepo(serverDir);
        server = Repository.serve(0);
        int port = server.getPort();
        assertTrue(port > 0);

        initRepo(clientDir);
        Repository.add_remote("origin", "127.0.0.1:" + port);

        byte[] content = "hello over netty\n".getBytes(StandardCharsets.UTF_8);
        String blobHash = Utils.hash(content);
        Store.putBlob(blobHash, content);
        Store.stageAdd("hello.txt", blobHash);
        String clientCommitId = Commit.commitInCurrBran("add hello.txt");

        Repository.push("origin", "master");

        try (Store.Session serverStore = Store.open(serverDir)) {
            assertEquals(clientCommitId, serverStore.getRef("master"));
            Commit pushedCommit = serverStore.getCommit(clientCommitId);
            assertNotNull(pushedCommit);
            assertEquals("add hello.txt", pushedCommit.getMessage());
            String pushedBlobHash = pushedCommit.getNameToHash().get("hello.txt");
            assertEquals(blobHash, pushedBlobHash);
            assertArrayEquals(content, serverStore.getBlob(pushedBlobHash));
        }
    }

    @Test
    public void testFetchOverNetty(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server/.gitlet").toFile();
        File clientDir = tempDir.resolve("client/.gitlet").toFile();

        initRepo(serverDir);
        byte[] data = "synced from server".getBytes(StandardCharsets.UTF_8);
        String blobHash = Utils.hash(data);
        Store.putBlob(blobHash, data);
        Store.stageAdd("remote_file.txt", blobHash);
        String serverCommitId = Commit.commitInCurrBran("server commit 1");

        server = Repository.serve(0);
        int port = server.getPort();

        initRepo(clientDir);
        Repository.add_remote("origin", "localhost:" + port);
        Repository.fetch("origin", "master");

        assertEquals(serverCommitId, Store.getRef("origin/master"));
        Commit fetchedCommit = Store.getCommit(serverCommitId);
        assertNotNull(fetchedCommit);
        assertEquals("server commit 1", fetchedCommit.getMessage());
        assertArrayEquals(data, Store.getBlob(fetchedCommit.getNameToHash().get("remote_file.txt")));
    }

    @Test
    public void testIsNetworkRemote() {
        assertTrue(Repository.isNetworkRemote("127.0.0.1:9418"));
        assertTrue(Repository.isNetworkRemote("localhost:9418"));
        assertTrue(Repository.isNetworkRemote("netty://localhost:9418"));
        assertFalse(Repository.isNetworkRemote("../D1/.gitlet"));
        assertFalse(Repository.isNetworkRemote("/tmp/repo/.gitlet"));
        assertFalse(Repository.isNetworkRemote("C:\\Users\\test\\.gitlet"));
    }
}
