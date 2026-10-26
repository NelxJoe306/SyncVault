package gitlet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local read-only HTTP server serving the SyncVault Web UI and JSON API
 * backed by the current repository's SQLite Store.
 */
public class WebServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 8080;

    private final Store.Session session;
    private final boolean ownsSession;
    private final int requestedPort;
    private HttpServer httpServer;
    private ExecutorService executor;
    private int boundPort;
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    public WebServer(int port) {
        this(Store.getDefault().getDbFile(), port);
    }

    public WebServer(File gitletDir, int port) {
        this(Store.open(gitletDir), port, true);
    }

    public WebServer(Store.Session session, int port) {
        this(session, port, false);
    }

    private WebServer(Store.Session session, int port, boolean ownsSession) {
        this.session = session;
        this.ownsSession = ownsSession;
        this.requestedPort = port;
    }

    public WebServer start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(requestedPort), 0);
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "syncvault-web");
                t.setDaemon(true);
                return t;
            });
            httpServer.setExecutor(executor);
            httpServer.createContext("/", this::handleRequest);
            httpServer.start();
            boundPort = httpServer.getAddress().getPort();
            return this;
        } catch (IOException e) {
            stop();
            throw new GitletException(e.getMessage());
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public int getPort() {
        return boundPort;
    }

    public int port() {
        return boundPort;
    }

    public void await() {
        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (ownsSession && session != null) {
            session.close();
        }
        stopLatch.countDown();
    }

    @Override
    public void close() {
        stop();
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed."));
                return;
            }

            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path) || "/index.html".equals(path)) {
                serveResource(exchange, "/web/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("/style.css".equals(path)) {
                serveResource(exchange, "/web/style.css", "text/css; charset=utf-8");
                return;
            }
            if ("/app.js".equals(path)) {
                serveResource(exchange, "/web/app.js", "application/javascript; charset=utf-8");
                return;
            }
            if ("/favicon.ico".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            if ("/api/graph".equals(path)) {
                handleGraph(exchange);
                return;
            }

            Map<String, String> query = parseQuery(uri.getRawQuery());

            if ("/api/commit".equals(path)) {
                String id = query.get("id");
                handleCommitDetail(exchange, id);
                return;
            }

            if ("/api/blob".equals(path)) {
                String commitId = query.get("commit");
                String filePath = query.get("path");
                String blobHash = query.get("hash");
                if (commitId != null && filePath != null) {
                    handleCommitBlob(exchange, commitId, filePath);
                } else if (blobHash != null) {
                    handleRawBlob(exchange, blobHash, filePath);
                } else {
                    sendJson(exchange, 400, Map.of("error", "Missing commit/path or hash parameter."));
                }
                return;
            }

            if (path.startsWith("/api/commits/")) {
                String rest = path.substring("/api/commits/".length());
                int slashIdx = rest.indexOf('/');
                if (slashIdx < 0) {
                    String commitId = urlDecode(rest);
                    handleCommitDetail(exchange, commitId);
                    return;
                }
                String commitId = urlDecode(rest.substring(0, slashIdx));
                String subRoute = rest.substring(slashIdx);
                if ("/blob".equals(subRoute) || "/file".equals(subRoute)) {
                    String filePath = query.get("path");
                    handleCommitBlob(exchange, commitId, filePath);
                    return;
                }
            }

            if (path.startsWith("/api/blobs/")) {
                String blobHash = urlDecode(path.substring("/api/blobs/".length()));
                handleRawBlob(exchange, blobHash, query.get("path"));
                return;
            }

            sendJson(exchange, 404, Map.of("error", "Not found."));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error."));
        }
    }

    private void handleGraph(HttpExchange exchange) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        synchronized (session) {
            String headBranch = session.getHead();
            Map<String, String> refsMap = session.allRefs();
            String headCommit = (headBranch != null) ? refsMap.get(headBranch) : null;

            List<Store.CommitMeta> metas = session.allCommitMetas();
            List<Store.CommitParent> parentRows = session.allCommitParents();

            Map<String, List<String>> parentsByChild = new HashMap<>();
            List<Map<String, Object>> edges = new ArrayList<>();
            for (Store.CommitParent row : parentRows) {
                parentsByChild.computeIfAbsent(row.child(), k -> new ArrayList<>()).add(row.parent());
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("from", row.child());
                edge.put("to", row.parent());
                edge.put("child", row.child());
                edge.put("parent", row.parent());
                edge.put("idx", row.idx());
                edges.add(edge);
            }

            Map<String, List<String>> refsByCommit = new HashMap<>();
            List<Map<String, Object>> refList = new ArrayList<>();
            for (Map.Entry<String, String> entry : refsMap.entrySet()) {
                String name = entry.getKey();
                String hash = entry.getValue();
                refsByCommit.computeIfAbsent(hash, k -> new ArrayList<>()).add(name);

                Map<String, Object> refItem = new LinkedHashMap<>();
                refItem.put("name", name);
                refItem.put("hash", hash);
                refItem.put("shortHash", shortHash(hash));
                refItem.put("isHead", Objects.equals(name, headBranch));
                refItem.put("isRemote", name.contains("/"));
                refList.add(refItem);
            }

            List<Map<String, Object>> commits = new ArrayList<>();
            for (Store.CommitMeta meta : metas) {
                String hash = meta.hash();
                List<String> parents = parentsByChild.getOrDefault(hash, Collections.emptyList());
                List<String> commitRefs = refsByCommit.getOrDefault(hash, Collections.emptyList());
                boolean isHead = Objects.equals(hash, headCommit);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("hash", hash);
                item.put("shortHash", shortHash(hash));
                item.put("message", meta.message());
                item.put("timestamp", meta.timestamp());
                item.put("date", formatTimestamp(meta.timestamp()));
                item.put("parents", parents);
                item.put("refs", commitRefs);
                item.put("isHead", isHead);
                commits.add(item);
            }

            response.put("head", headBranch);
            response.put("headCommit", headCommit);
            response.put("refs", refList);
            response.put("commits", commits);
            response.put("edges", edges);
        }
        sendJson(exchange, 200, response);
    }

    private void handleCommitDetail(HttpExchange exchange, String rawId) throws IOException {
        if (rawId == null || rawId.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Commit id is required."));
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        synchronized (session) {
            String fullHash = resolveCommitId(rawId.trim());
            if (fullHash == null) {
                sendJson(exchange, 404, Map.of("error", "No commit with that id exists."));
                return;
            }
            Commit commit = session.getCommit(fullHash);
            if (commit == null) {
                sendJson(exchange, 404, Map.of("error", "No commit with that id exists."));
                return;
            }

            String headBranch = session.getHead();
            Map<String, String> refsMap = session.allRefs();
            String headCommit = (headBranch != null) ? refsMap.get(headBranch) : null;

            List<String> commitRefs = new ArrayList<>();
            for (Map.Entry<String, String> entry : refsMap.entrySet()) {
                if (Objects.equals(entry.getValue(), fullHash)) {
                    commitRefs.add(entry.getKey());
                }
            }

            List<String> parents = new ArrayList<>();
            if (commit.getParent() != null) {
                parents.add(commit.getParent());
            }
            if (commit.getSecondParent() != null) {
                parents.add(commit.getSecondParent());
            }

            Commit firstParentCommit = (commit.getParent() != null) ? session.getCommit(commit.getParent()) : null;
            Commit secondParentCommit = (commit.getSecondParent() != null) ? session.getCommit(commit.getSecondParent()) : null;
            boolean isMerge = firstParentCommit != null && secondParentCommit != null;

            int conflictFileCount = 0;
            List<Map<String, Object>> files = new ArrayList<>();
            TreeMap<String, String> nameToHash = commit.getNameToHash();
            if (nameToHash != null) {
                for (Map.Entry<String, String> entry : nameToHash.entrySet()) {
                    String filePath = entry.getKey();
                    String blobHash = entry.getValue();
                    byte[] blobBytes = session.getBlob(blobHash);
                    int size = (blobBytes != null) ? blobBytes.length : 0;
                    boolean hasConflict = blobBytes != null && Diff3.hasConflictMarkers(blobBytes);
                    if (hasConflict) {
                        conflictFileCount++;
                    }

                    Map<String, Object> fileInfo = new LinkedHashMap<>();
                    fileInfo.put("path", filePath);
                    fileInfo.put("blobHash", blobHash);
                    fileInfo.put("shortBlobHash", shortHash(blobHash));
                    fileInfo.put("size", size);
                    fileInfo.put("conflict", hasConflict);
                    if (isMerge) {
                        String p1Blob = firstParentCommit.getNameToHash() != null
                                ? firstParentCommit.getNameToHash().get(filePath) : null;
                        String p2Blob = secondParentCommit.getNameToHash() != null
                                ? secondParentCommit.getNameToHash().get(filePath) : null;
                        boolean parentsDiffer = !Objects.equals(p1Blob, p2Blob);
                        fileInfo.put("parentsDiffer", parentsDiffer);
                        if (hasConflict && parentsDiffer) {
                            fileInfo.put("oursParent", commit.getParent());
                            fileInfo.put("theirsParent", commit.getSecondParent());
                        }
                    }
                    files.add(fileInfo);
                }
            }

            response.put("hash", fullHash);
            response.put("shortHash", shortHash(fullHash));
            response.put("message", commit.getMessage());
            response.put("timestamp", commit.getTimestamp());
            response.put("date", formatTimestamp(commit.getTimestamp()));
            response.put("parents", parents);
            if (isMerge) {
                response.put("oursParent", commit.getParent());
                response.put("theirsParent", commit.getSecondParent());
            }
            response.put("refs", commitRefs);
            response.put("isHead", Objects.equals(fullHash, headCommit));
            response.put("hasConflicts", conflictFileCount > 0);
            response.put("conflictCount", conflictFileCount);
            response.put("files", files);
        }
        sendJson(exchange, 200, response);
    }

    private void handleCommitBlob(HttpExchange exchange, String rawCommitId, String filePath) throws IOException {
        if (rawCommitId == null || rawCommitId.isBlank() || filePath == null || filePath.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "Both commit id and file path are required."));
            return;
        }
        Map<String, Object> response;
        synchronized (session) {
            String fullHash = resolveCommitId(rawCommitId.trim());
            if (fullHash == null) {
                sendJson(exchange, 404, Map.of("error", "No commit with that id exists."));
                return;
            }
            Commit commit = session.getCommit(fullHash);
            if (commit == null || commit.getNameToHash() == null) {
                sendJson(exchange, 404, Map.of("error", "No commit with that id exists."));
                return;
            }
            String blobHash = commit.getNameToHash().get(filePath);
            if (blobHash == null) {
                sendJson(exchange, 404, Map.of("error", "File does not exist in that commit."));
                return;
            }
            byte[] bytes = session.getBlob(blobHash);
            if (bytes == null) {
                sendJson(exchange, 404, Map.of("error", "Blob data not found."));
                return;
            }
            response = buildBlobPayload(filePath, fullHash, blobHash, bytes);
            if (commit.getParent() != null && commit.getSecondParent() != null) {
                Commit p1 = session.getCommit(commit.getParent());
                Commit p2 = session.getCommit(commit.getSecondParent());
                String p1Blob = (p1 != null && p1.getNameToHash() != null) ? p1.getNameToHash().get(filePath) : null;
                String p2Blob = (p2 != null && p2.getNameToHash() != null) ? p2.getNameToHash().get(filePath) : null;
                boolean parentsDiffer = !Objects.equals(p1Blob, p2Blob);
                response.put("oursParent", commit.getParent());
                response.put("oursShortParent", shortHash(commit.getParent()));
                response.put("theirsParent", commit.getSecondParent());
                response.put("theirsShortParent", shortHash(commit.getSecondParent()));
                response.put("oursBlobHash", p1Blob);
                response.put("theirsBlobHash", p2Blob);
                response.put("parentsDiffer", parentsDiffer);
            }
        }
        sendJson(exchange, 200, response);
    }

    private void handleRawBlob(HttpExchange exchange, String blobHash, String filePath) throws IOException {
        if (blobHash == null || blobHash.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Blob hash is required."));
            return;
        }
        Map<String, Object> response;
        synchronized (session) {
            byte[] bytes = session.getBlob(blobHash.trim());
            if (bytes == null) {
                sendJson(exchange, 404, Map.of("error", "Blob not found."));
                return;
            }
            response = buildBlobPayload(filePath != null ? filePath : blobHash, null, blobHash.trim(), bytes);
        }
        sendJson(exchange, 200, response);
    }

    private String resolveCommitId(String id) {
        Commit direct = session.getCommit(id);
        if (direct != null) {
            return id;
        }
        List<String> matches = session.commitIdsWithPrefix(id);
        if (matches != null && matches.size() == 1) {
            return matches.get(0);
        }
        return null;
    }

    private static Map<String, Object> buildBlobPayload(String path, String commitHash, String blobHash, byte[] bytes) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", path);
        map.put("commitHash", commitHash);
        map.put("blobHash", blobHash);
        map.put("shortBlobHash", shortHash(blobHash));
        map.put("size", bytes.length);

        String decoded = tryDecodeUtf8Text(bytes);
        if (decoded != null) {
            List<Diff3.Section> parsed = Diff3.parseSections(decoded);
            List<Map<String, Object>> sectionMaps = new ArrayList<>();
            int conflictCount = 0;
            for (Diff3.Section s : parsed) {
                if (s.isConflict()) {
                    conflictCount++;
                }
                sectionMaps.add(s.toMap());
            }
            map.put("binary", false);
            map.put("conflict", conflictCount > 0);
            map.put("conflictCount", conflictCount);
            map.put("content", decoded);
            map.put("sections", sectionMaps);
            map.put("hexPreview", null);
        } else {
            map.put("binary", true);
            map.put("conflict", false);
            map.put("conflictCount", 0);
            map.put("content", null);
            map.put("sections", Collections.emptyList());
            map.put("hexPreview", formatHexPreview(bytes, 256));
        }
        return map;
    }

    private static String tryDecodeUtf8Text(byte[] bytes) {
        return Diff3.tryDecodeUtf8Text(bytes);
    }

    private static String formatHexPreview(byte[] bytes, int maxBytes) {
        int limit = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder();
        for (int offset = 0; offset < limit; offset += 16) {
            sb.append(String.format("%04x  ", offset));
            int lineLen = Math.min(16, limit - offset);
            for (int i = 0; i < 16; i++) {
                if (i < lineLen) {
                    sb.append(String.format("%02x ", bytes[offset + i] & 0xff));
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" |");
            for (int i = 0; i < lineLen; i++) {
                int b = bytes[offset + i] & 0xff;
                sb.append((b >= 32 && b <= 126) ? (char) b : '.');
            }
            sb.append('|');
            if (offset + 16 < limit) {
                sb.append('\n');
            }
        }
        if (bytes.length > maxBytes) {
            sb.append("\n... (").append(bytes.length - maxBytes).append(" more bytes)");
        }
        return sb.toString();
    }

    private static String shortHash(String hash) {
        if (hash == null) {
            return "";
        }
        return hash.length() <= 7 ? hash : hash.substring(0, 7);
    }

    private static String formatTimestamp(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx >= 0) {
                String key = urlDecode(pair.substring(0, idx));
                String val = urlDecode(pair.substring(idx + 1));
                map.put(key, val);
            } else {
                map.put(urlDecode(pair), "");
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private void serveResource(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendJson(exchange, 404, Map.of("error", "Resource not found: " + resourcePath));
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof String s) {
            writeJsonString(sb, s);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeJson(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> col) {
            sb.append('[');
            boolean first = true;
            for (Object item : col) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
        } else {
            writeJsonString(sb, String.valueOf(value));
        }
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
