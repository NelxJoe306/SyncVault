package gitlet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class NettySyncTest {

    private static Path tlsTempDir;
    private static File serverCertFile;
    private static File serverKeyFile;
    private static File otherCaCertFile;

    private NettyServer server;

    @BeforeAll
    public static void setUpTlsCerts() throws Exception {
        tlsTempDir = Files.createTempDirectory("syncvault-tls-test");
        CertKeyPair serverPair = generateSelfSignedPem(tlsTempDir, "server", "CN=localhost");
        serverCertFile = serverPair.certFile;
        serverKeyFile = serverPair.keyFile;

        CertKeyPair otherPair = generateSelfSignedPem(tlsTempDir, "other-ca", "CN=other.example");
        otherCaCertFile = otherPair.certFile;
    }

    @AfterAll
    public static void cleanUpTlsCerts() throws Exception {
        if (tlsTempDir != null && Files.exists(tlsTempDir)) {
            Files.walk(tlsTempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> p.toFile().delete());
        }
    }

    private record CertKeyPair(File certFile, File keyFile) {}

    private static CertKeyPair generateSelfSignedPem(Path dir, String prefix, String dname) throws Exception {
        File p12File = dir.resolve(prefix + ".p12").toFile();
        String keytoolBin = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        ProcessBuilder pb = new ProcessBuilder(
                keytoolBin,
                "-genkeypair",
                "-alias", "syncvault",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-dname", dname,
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "365",
                "-keystore", p12File.getAbsolutePath(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        int code = proc.waitFor();
        assertEquals(0, code, "keytool failed to generate test certificate");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(p12File)) {
            ks.load(fis, "changeit".toCharArray());
        }
        Certificate cert = ks.getCertificate("syncvault");
        Key key = ks.getKey("syncvault", "changeit".toCharArray());

        Base64.Encoder mime = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        String certPem = "-----BEGIN CERTIFICATE-----\n"
                + mime.encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        String keyPem = "-----BEGIN PRIVATE KEY-----\n"
                + mime.encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        File certFile = dir.resolve(prefix + "-cert.pem").toFile();
        File keyFile = dir.resolve(prefix + "-key.pem").toFile();
        Files.writeString(certFile.toPath(), certPem, StandardCharsets.US_ASCII);
        Files.writeString(keyFile.toPath(), keyPem, StandardCharsets.US_ASCII);
        return new CertKeyPair(certFile, keyFile);
    }

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
        assertFalse(server.isTlsEnabled());

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
    public void testIsNetworkRemoteAndParseHostPort() {
        assertTrue(Repository.isNetworkRemote("127.0.0.1:9418"));
        assertTrue(Repository.isNetworkRemote("localhost:9418"));
        assertTrue(Repository.isNetworkRemote("netty://localhost:9418"));
        assertTrue(Repository.isNetworkRemote("nettys://localhost:9418"));
        assertFalse(Repository.isNetworkRemote("../D1/.gitlet"));
        assertFalse(Repository.isNetworkRemote("/tmp/repo/.gitlet"));
        assertFalse(Repository.isNetworkRemote("C:\\Users\\test\\.gitlet"));

        NettyClient.HostPort plain = NettyClient.parseHostPort("netty://127.0.0.1:9418");
        assertNotNull(plain);
        assertEquals("127.0.0.1", plain.host);
        assertEquals(9418, plain.port);
        assertFalse(plain.tls);

        NettyClient.HostPort tls = NettyClient.parseHostPort("nettys://localhost:9419/");
        assertNotNull(tls);
        assertEquals("localhost", tls.host);
        assertEquals(9419, tls.port);
        assertTrue(tls.tls);
    }

    @Test
    public void testTokenAuthRejectsMissingAndWrongTokenAndAcceptsMatchingToken(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server/.gitlet").toFile();
        File clientDir = tempDir.resolve("client/.gitlet").toFile();

        String serverInitId = initRepo(serverDir);
        // Configure server token via repo-local .gitlet/sync-token file
        Files.writeString(serverDir.toPath().resolve(Repository.SYNC_TOKEN_FILE),
                "vault-shared-secret-2026\n", StandardCharsets.UTF_8);

        server = Repository.serve(0);
        int port = server.getPort();

        initRepo(clientDir);
        Repository.add_remote("origin", "127.0.0.1:" + port);

        byte[] content = "protected payload\n".getBytes(StandardCharsets.UTF_8);
        String blobHash = Utils.hash(content);
        Store.putBlob(blobHash, content);
        Store.stageAdd("secret.txt", blobHash);
        String clientCommitId = Commit.commitInCurrBran("add secret.txt");

        // 1. Missing token -> rejected on both push and fetch
        GitletException missingPushEx = assertThrows(
                GitletException.class,
                () -> Repository.push("origin", "master")
        );
        assertEquals(NettyServer.AUTH_FAILED_MESSAGE, missingPushEx.getMessage());

        GitletException missingFetchEx = assertThrows(
                GitletException.class,
                () -> Repository.fetch("origin", "master")
        );
        assertEquals(NettyServer.AUTH_FAILED_MESSAGE, missingFetchEx.getMessage());

        // Verify server state untouched after rejected push
        try (Store.Session serverStore = Store.open(serverDir)) {
            assertEquals(serverInitId, serverStore.getRef("master"));
            assertFalse(serverStore.hasObject(clientCommitId));
        }

        // 2. Wrong token in client .gitlet/sync-token -> rejected
        Files.writeString(clientDir.toPath().resolve(Repository.SYNC_TOKEN_FILE),
                "wrong-token\n", StandardCharsets.UTF_8);
        GitletException wrongPushEx = assertThrows(
                GitletException.class,
                () -> Repository.push("origin", "master")
        );
        assertEquals(NettyServer.AUTH_FAILED_MESSAGE, wrongPushEx.getMessage());

        try (Store.Session serverStore = Store.open(serverDir)) {
            assertEquals(serverInitId, serverStore.getRef("master"));
            assertFalse(serverStore.hasObject(clientCommitId));
        }

        // 3. Matching token in client .gitlet/sync-token -> push and fetch succeed
        Files.writeString(clientDir.toPath().resolve(Repository.SYNC_TOKEN_FILE),
                "vault-shared-secret-2026\n", StandardCharsets.UTF_8);
        Repository.push("origin", "master");

        try (Store.Session serverStore = Store.open(serverDir)) {
            assertEquals(clientCommitId, serverStore.getRef("master"));
            assertArrayEquals(content, serverStore.getBlob(blobHash));
        }

        Repository.fetch("origin", "master");
        assertEquals(clientCommitId, Store.getRef("origin/master"));
    }

    @Test
    public void testTokenResolutionPrecedence(@TempDir Path tempDir) throws Exception {
        File gitletDir = tempDir.resolve(".gitlet").toFile();
        gitletDir.mkdirs();

        // None configured -> null
        assertNull(Repository.resolveSyncToken(null, null, gitletDir));

        // File configured -> file value (trimmed)
        Files.writeString(gitletDir.toPath().resolve(Repository.SYNC_TOKEN_FILE),
                "  file-token-123 \n", StandardCharsets.UTF_8);
        assertEquals("file-token-123", Repository.resolveSyncToken(null, null, gitletDir));

        // Env wins over file
        assertEquals("env-token-456", Repository.resolveSyncToken(null, " env-token-456 ", gitletDir));

        // Explicit CLI flag wins over env and file
        assertEquals("cli-token-789", Repository.resolveSyncToken("cli-token-789", "env-token-456", gitletDir));
    }

    @Test
    public void testPathRemoteUntouchedWhenSyncTokenSet(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote/.gitlet").toFile();
        File clientDir = tempDir.resolve("client/.gitlet").toFile();

        initRepo(remoteDir);
        Files.writeString(remoteDir.toPath().resolve(Repository.SYNC_TOKEN_FILE),
                "server-only-token", StandardCharsets.UTF_8);

        initRepo(clientDir);
        Repository.add_remote("localR", remoteDir.getAbsolutePath());

        byte[] content = "local path remote sync\n".getBytes(StandardCharsets.UTF_8);
        String blobHash = Utils.hash(content);
        Store.putBlob(blobHash, content);
        Store.stageAdd("local.txt", blobHash);
        String commitId = Commit.commitInCurrBran("local commit");

        // Path remote does not use Netty or require token
        Repository.push("localR", "master");
        Repository.fetch("localR", "master");
        assertEquals(commitId, Store.getRef("localR/master"));
    }

    @Test
    public void testTlsPushAndFetchWithTrustedCaAndToken(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server/.gitlet").toFile();
        File clientDir = tempDir.resolve("client/.gitlet").toFile();

        initRepo(serverDir);
        server = Repository.serve(0, "tls-token-abc", serverCertFile, serverKeyFile);
        assertTrue(server.isTlsEnabled());
        int port = server.getPort();

        initRepo(clientDir);
        Repository.add_remote("origin", "nettys://localhost:" + port);

        byte[] content = "encrypted over nettys with CA + token\n".getBytes(StandardCharsets.UTF_8);
        String blobHash = Utils.hash(content);
        Store.putBlob(blobHash, content);
        Store.stageAdd("tls.txt", blobHash);
        String commitId = Commit.commitInCurrBran("tls commit");

        // Wrong token over valid TLS still fails with auth error
        GitletException badTokenEx = assertThrows(
                GitletException.class,
                () -> Repository.push("origin", "master", "wrong-token", serverCertFile, false)
        );
        assertEquals(NettyServer.AUTH_FAILED_MESSAGE, badTokenEx.getMessage());

        // Valid CA + matching token -> push and fetch succeed over TLS
        Repository.push("origin", "master", "tls-token-abc", serverCertFile, false);
        Repository.fetch("origin", "master", "tls-token-abc", serverCertFile, false);
        assertEquals(commitId, Store.getRef("origin/master"));

        // Repo-local .gitlet/tls-ca.pem + .gitlet/sync-token also work without explicit arguments
        Files.copy(serverCertFile.toPath(), clientDir.toPath().resolve(Repository.TLS_CA_FILE));
        Files.writeString(clientDir.toPath().resolve(Repository.SYNC_TOKEN_FILE),
                "tls-token-abc\n", StandardCharsets.UTF_8);
        Repository.fetch("origin", "master");
        assertEquals(commitId, Store.getRef("origin/master"));

        try (Store.Session serverStore = Store.open(serverDir)) {
            assertEquals(commitId, serverStore.getRef("master"));
            assertArrayEquals(content, serverStore.getBlob(blobHash));
        }
    }

    @Test
    public void testTlsPushAndFetchWithInsecureTrustManager(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server/.gitlet").toFile();
        File clientDir = tempDir.resolve("client/.gitlet").toFile();

        initRepo(serverDir);
        server = Repository.serve(0, null, serverCertFile, serverKeyFile);
        int port = server.getPort();

        initRepo(clientDir);
        Repository.add_remote("origin", "nettys://127.0.0.1:" + port);

        byte[] content = "insecure trust manager test\n".getBytes(StandardCharsets.UTF_8);
        String blobHash = Utils.hash(content);
        Store.putBlob(blobHash, content);
        Store.stageAdd("insecure.txt", blobHash);
        String commitId = Commit.commitInCurrBran("insecure tls commit");

        Repository.push("origin", "master", null, null, true);
        Repository.fetch("origin", "master", null, null, true);
        assertEquals(commitId, Store.getRef("origin/master"));
    }

    @Test
    public void testTlsHandshakeFailsWithUntrustedOrWrongCa(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server/.gitlet").toFile();
        File clientDir = tempDir.resolve("client/.gitlet").toFile();

        initRepo(serverDir);
        server = Repository.serve(0, null, serverCertFile, serverKeyFile);
        int port = server.getPort();

        initRepo(clientDir);
        Repository.add_remote("origin", "nettys://localhost:" + port);

        // 1. Default system trust store rejects self-signed server cert
        GitletException defaultTrustEx = assertThrows(
                GitletException.class,
                () -> Repository.fetch("origin", "master")
        );
        assertTrue(defaultTrustEx.getMessage().startsWith("TLS handshake failed:"),
                "Expected TLS handshake failure but got: " + defaultTrustEx.getMessage());

        // 2. Wrong CA cert rejects server cert
        GitletException wrongCaEx = assertThrows(
                GitletException.class,
                () -> Repository.fetch("origin", "master", null, otherCaCertFile, false)
        );
        assertTrue(wrongCaEx.getMessage().startsWith("TLS handshake failed:"),
                "Expected TLS handshake failure but got: " + wrongCaEx.getMessage());
    }

    @Test
    public void testMissingTlsCertKeyAndCaFilesProduceClearErrors(@TempDir Path tempDir) throws Exception {
        File serverDir = tempDir.resolve("server/.gitlet").toFile();
        initRepo(serverDir);

        File missingFile = tempDir.resolve("nonexistent.pem").toFile();

        // Missing key when cert is provided
        GitletException onlyCertEx = assertThrows(
                GitletException.class,
                () -> Repository.serve(0, null, serverCertFile, null)
        );
        assertTrue(onlyCertEx.getMessage().contains("both certificate and private key"));

        // Nonexistent cert file
        GitletException missingCertEx = assertThrows(
                GitletException.class,
                () -> Repository.serve(0, null, missingFile, serverKeyFile)
        );
        assertTrue(missingCertEx.getMessage().startsWith("TLS certificate file not found:"));

        // Nonexistent key file
        GitletException missingKeyEx = assertThrows(
                GitletException.class,
                () -> Repository.serve(0, null, serverCertFile, missingFile)
        );
        assertTrue(missingKeyEx.getMessage().startsWith("TLS private key file not found:"));

        // Nonexistent client CA file
        GitletException missingCaEx = assertThrows(
                GitletException.class,
                () -> NettyClient.buildClientSslContext(true, missingFile, false)
        );
        assertTrue(missingCaEx.getMessage().startsWith("TLS CA certificate file not found:"));
    }
}
