package gitlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

public class WebServerTest {

    private WebServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        Store.closeDefault();
    }

    @Test
    public void testWebServerStaticAndApiEndpoints(@TempDir Path tempDir) throws Exception {
        File gitletDir = tempDir.resolve(".gitlet").toFile();
        gitletDir.mkdirs();
        Store.useDir(gitletDir);
        Store.createSchema();

        String c0 = Commit.first_commit();
        Store.setHead("master");
        Repository.currHead = c0;

        byte[] wugBytes = "This is a wug.\n".getBytes(StandardCharsets.UTF_8);
        String wugHash = Utils.hash(wugBytes);
        Store.putBlob(wugHash, wugBytes);
        Store.stageAdd("wug.txt", wugHash);

        byte[] guideBytes = "# Guide\nHello SyncVault\n".getBytes(StandardCharsets.UTF_8);
        String guideHash = Utils.hash(guideBytes);
        Store.putBlob(guideHash, guideBytes);
        Store.stageAdd("docs/guide.md", guideHash);

        String c1 = Commit.commitInCurrBran("add wug and guide");

        Commit.branch("feature");
        Store.setHead("feature");
        Repository.currHead = c1;

        byte[] binBytes = new byte[]{0x00, 0x01, 0x02, (byte) 0xff, 0x42};
        String binHash = Utils.hash(binBytes);
        Store.putBlob(binHash, binBytes);
        Store.stageAdd("bin.dat", binHash);
        String c2 = Commit.commitInCurrBran("add binary asset on feature");

        Store.setHead("master");
        Repository.currHead = c1;
        byte[] masterBytes = "master branch note\n".getBytes(StandardCharsets.UTF_8);
        String masterHash = Utils.hash(masterBytes);
        Store.putBlob(masterHash, masterBytes);
        Store.stageAdd("master.txt", masterHash);
        String c3 = Commit.commitInCurrBran("add master note");

        TreeMap<String, String> mergedFiles = new TreeMap<>(Commit.getCommitFiles_map(c3));
        mergedFiles.put("bin.dat", binHash);
        Commit mergeCommit = new Commit("Merged feature into master.", mergedFiles, c3, c2);
        mergeCommit.setSelfHash(Utils.hash(Utils.serialize(mergeCommit)));
        Store.putCommit(mergeCommit);
        String c4 = mergeCommit.getSelfHash();
        Store.setRef("master", c4);
        Repository.currHead = c4;

        server = Repository.web(0);
        int port = server.getPort();
        assertTrue(port > 0);

        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = "http://127.0.0.1:" + port;

        // 1. Static HTML check (including branding rules)
        HttpResponse<String> indexRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, indexRes.statusCode());
        String html = indexRes.body();
        assertTrue(html.contains("SyncVault"));
        assertTrue(html.contains("id=\"theme-btn\""));
        assertFalse(html.contains("Joye"));
        assertFalse(html.contains("joyehuang"));
        assertFalse(html.contains("转化成 notes"));

        // 2. Static CSS & JS check
        HttpResponse<String> cssRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/style.css")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, cssRes.statusCode());
        assertTrue(cssRes.body().contains("--accent:#4f7a8c"));
        assertTrue(cssRes.body().contains(":root[data-theme=dark]"));

        HttpResponse<String> jsRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/app.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, jsRes.statusCode());
        assertTrue(jsRes.body().contains("/api/graph"));

        // 3. Graph endpoint check
        HttpResponse<String> graphRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/graph")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, graphRes.statusCode());
        String graphJson = graphRes.body();
        assertTrue(graphJson.contains("\"head\":\"master\""));
        assertTrue(graphJson.contains("\"headCommit\":\"" + c4 + "\""));
        assertTrue(graphJson.contains("\"name\":\"master\""));
        assertTrue(graphJson.contains("\"name\":\"feature\""));
        assertTrue(graphJson.contains(c0));
        assertTrue(graphJson.contains(c1));
        assertTrue(graphJson.contains(c2));
        assertTrue(graphJson.contains(c3));
        assertTrue(graphJson.contains(c4));
        assertTrue(graphJson.contains("\"idx\":1"));

        // 4. Commit detail endpoint (full hash and short hash prefix)
        String shortC4 = c4.substring(0, 7);
        HttpResponse<String> detailRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/commits/" + shortC4)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, detailRes.statusCode());
        String detailJson = detailRes.body();
        assertTrue(detailJson.contains("\"hash\":\"" + c4 + "\""));
        assertTrue(detailJson.contains("\"shortHash\":\"" + shortC4 + "\""));
        assertTrue(detailJson.contains("\"message\":\"Merged feature into master.\""));
        assertTrue(detailJson.contains("\"" + c3 + "\""));
        assertTrue(detailJson.contains("\"" + c2 + "\""));
        assertTrue(detailJson.contains("\"path\":\"docs/guide.md\""));
        assertTrue(detailJson.contains("\"path\":\"bin.dat\""));

        // 5. Text blob preview endpoint
        String encodedPath = URLEncoder.encode("docs/guide.md", StandardCharsets.UTF_8);
        HttpResponse<String> textBlobRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/commits/" + c4 + "/blob?path=" + encodedPath)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, textBlobRes.statusCode());
        String textBlobJson = textBlobRes.body();
        assertTrue(textBlobJson.contains("\"binary\":false"));
        assertTrue(textBlobJson.contains("# Guide\\nHello SyncVault\\n"));

        // 6. Binary blob preview endpoint
        HttpResponse<String> binBlobRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/commits/" + c4 + "/blob?path=bin.dat")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, binBlobRes.statusCode());
        String binBlobJson = binBlobRes.body();
        assertTrue(binBlobJson.contains("\"binary\":true"));
        assertTrue(binBlobJson.contains("00 01 02 ff 42"));

        // 7. 404 cases
        HttpResponse<String> missingCommitRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/commits/deadbeef0000")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(404, missingCommitRes.statusCode());

        HttpResponse<String> missingFileRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/commits/" + c4 + "/blob?path=nope.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(404, missingFileRes.statusCode());
    }
}
