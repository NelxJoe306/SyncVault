package syncvault;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP server exposing a SyncVault repository for push/fetch synchronization.
 */
public class NettyServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 9418;
    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    public static final String AUTH_FAILED_MESSAGE = "Authentication failed: invalid or missing sync token.";

    private final Store.Session session;
    private final boolean ownsSession;
    private final int requestedPort;
    private final String explicitToken;
    private final File tlsCertFile;
    private final File tlsKeyFile;
    private SslContext sslContext;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int boundPort;

    public NettyServer(int port) {
        this(Store.getDefault().getDbFile(), port);
    }

    public NettyServer(File syncvaultDir, int port) {
        this(Store.open(syncvaultDir), port, true, null, null, null);
    }

    public NettyServer(File syncvaultDir, int port, String token, File tlsCertFile, File tlsKeyFile) {
        this(Store.open(syncvaultDir), port, true, token, tlsCertFile, tlsKeyFile);
    }

    public NettyServer(Store.Session session, int port) {
        this(session, port, false, null, null, null);
    }

    public NettyServer(Store.Session session, int port, String token, File tlsCertFile, File tlsKeyFile) {
        this(session, port, false, token, tlsCertFile, tlsKeyFile);
    }

    private NettyServer(Store.Session session, int port, boolean ownsSession,
                        String explicitToken, File tlsCertFile, File tlsKeyFile) {
        this.session = session;
        this.ownsSession = ownsSession;
        this.requestedPort = port;
        this.explicitToken = explicitToken;
        this.tlsCertFile = tlsCertFile;
        this.tlsKeyFile = tlsKeyFile;
    }

    static SslContext buildServerSslContext(File certFile, File keyFile) {
        if (certFile == null && keyFile == null) {
            return null;
        }
        if (certFile == null || keyFile == null) {
            throw new SyncVaultException("TLS configuration error: both certificate and private key files are required.");
        }
        if (!certFile.exists() || !certFile.isFile()) {
            throw new SyncVaultException("TLS certificate file not found: " + certFile.getPath());
        }
        if (!keyFile.exists() || !keyFile.isFile()) {
            throw new SyncVaultException("TLS private key file not found: " + keyFile.getPath());
        }
        try {
            return SslContextBuilder.forServer(certFile, keyFile).build();
        } catch (Exception e) {
            throw new SyncVaultException("Failed to initialize server TLS context: " + e.getMessage());
        }
    }

    private String resolveExpectedToken() {
        return Repository.resolveSyncToken(explicitToken, Repository.syncvaultDirForSession(session));
    }

    private SslContext resolveSslContext() {
        File cert = (tlsCertFile != null) ? tlsCertFile : Repository.envFile(Repository.ENV_TLS_CERT);
        File key = (tlsKeyFile != null) ? tlsKeyFile : Repository.envFile(Repository.ENV_TLS_KEY);
        return buildServerSslContext(cert, key);
    }

    public boolean isTlsEnabled() {
        return sslContext != null;
    }

    public NettyServer start() {
        try {
            this.sslContext = resolveSslContext();
        } catch (RuntimeException e) {
            if (ownsSession && session != null) {
                session.close();
            }
            throw e;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        try {
            final SslContext activeSsl = this.sslContext;
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            if (activeSsl != null) {
                                pipeline.addLast(activeSsl.newHandler(ch.alloc()));
                            }
                            pipeline.addLast(
                                    new LengthFieldBasedFrameDecoder(MAX_FRAME_BYTES, 0, 4, 0, 4),
                                    new LengthFieldPrepender(4),
                                    new SyncMessage.Codec(),
                                    new ServerHandler(session, resolveExpectedToken())
                            );
                        }
                    });
            ChannelFuture f = b.bind(requestedPort).sync();
            serverChannel = f.channel();
            boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            return this;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new SyncVaultException(e.getMessage());
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
        if (serverChannel != null) {
            serverChannel.closeFuture().syncUninterruptibly();
        }
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        if (ownsSession && session != null) {
            session.close();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static class ServerHandler extends SimpleChannelInboundHandler<SyncMessage> {
        private final Store.Session session;
        private final String expectedToken;
        private boolean authenticated = false;

        ServerHandler(Store.Session session, String expectedToken) {
            this.session = session;
            this.expectedToken = expectedToken;
        }

        private boolean verifyToken(String clientToken) {
            if (expectedToken == null || expectedToken.isEmpty()) {
                return true;
            }
            if (clientToken == null || clientToken.isEmpty()) {
                return false;
            }
            return MessageDigest.isEqual(
                    expectedToken.getBytes(StandardCharsets.UTF_8),
                    clientToken.getBytes(StandardCharsets.UTF_8)
            );
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SyncMessage msg) {
            synchronized (session) {
                switch (msg.type) {
                    case HELLO:
                        if (!verifyToken(msg.token)) {
                            authenticated = false;
                            ctx.writeAndFlush(SyncMessage.error(AUTH_FAILED_MESSAGE));
                        } else {
                            authenticated = true;
                            ctx.writeAndFlush(SyncMessage.helloOk());
                        }
                        break;
                    case FETCH_REQ:
                        if (!authenticated) {
                            ctx.writeAndFlush(SyncMessage.error(AUTH_FAILED_MESSAGE));
                            break;
                        }
                        handleFetchReq(ctx, msg);
                        break;
                    case FETCH_HAVE:
                        if (!authenticated) {
                            ctx.writeAndFlush(SyncMessage.error(AUTH_FAILED_MESSAGE));
                            break;
                        }
                        handleFetchHave(ctx, msg);
                        break;
                    case PUSH_REQ:
                        if (!authenticated) {
                            ctx.writeAndFlush(SyncMessage.error(AUTH_FAILED_MESSAGE));
                            break;
                        }
                        handlePushReq(ctx, msg);
                        break;
                    case PUSH_OBJECTS:
                        if (!authenticated) {
                            ctx.writeAndFlush(SyncMessage.error(AUTH_FAILED_MESSAGE));
                            break;
                        }
                        handlePushObjects(ctx, msg);
                        break;
                    case ACK:
                        ctx.close();
                        break;
                    default:
                        ctx.writeAndFlush(SyncMessage.error("Unexpected message type."));
                        break;
                }
            }
        }

        private void handleFetchReq(ChannelHandlerContext ctx, SyncMessage msg) {
            String tip = session.getRef(msg.branch);
            if (tip == null) {
                ctx.writeAndFlush(SyncMessage.error("That remote does not have that branch."));
            } else {
                ctx.writeAndFlush(SyncMessage.fetchTip(tip));
            }
        }

        private void handleFetchHave(ChannelHandlerContext ctx, SyncMessage msg) {
            String tip = msg.tipHash;
            Set<String> have = (msg.hashes != null) ? msg.hashes : Collections.emptySet();
            List<byte[]> commits = new ArrayList<>();
            Map<String, byte[]> blobs = new LinkedHashMap<>();

            if (tip != null && session.hasObject(tip)) {
                Queue<String> q = new ArrayDeque<>();
                Set<String> visited = new HashSet<>();
                q.add(tip);
                visited.add(tip);

                while (!q.isEmpty()) {
                    String id = q.poll();
                    if (session.hasObject(id)) {
                        Commit c = session.getCommit(id);
                        if (c != null) {
                            if (!have.contains(id)) {
                                commits.add(c.encode());
                                for (String blobHash : c.getNameToHash().values()) {
                                    if (!blobs.containsKey(blobHash)) {
                                        byte[] remoteBlob = session.getBlob(blobHash);
                                        if (remoteBlob != null) {
                                            blobs.put(blobHash, remoteBlob);
                                        }
                                    }
                                }
                            }
                            String p1 = c.getParent();
                            if (p1 != null && !visited.contains(p1)) {
                                visited.add(p1);
                                q.add(p1);
                            }
                            String p2 = c.getSecondParent();
                            if (p2 != null && !visited.contains(p2)) {
                                visited.add(p2);
                                q.add(p2);
                            }
                        }
                    }
                }
            }
            ctx.writeAndFlush(SyncMessage.fetchObjects(tip, commits, blobs));
        }

        private void handlePushReq(ChannelHandlerContext ctx, SyncMessage msg) {
            String remoteTip = session.getRef(msg.branch);
            Set<String> have = new HashSet<>(session.allCommitIds());
            ctx.writeAndFlush(SyncMessage.pushTip(remoteTip, have));
        }

        private void handlePushObjects(ChannelHandlerContext ctx, SyncMessage msg) {
            try {
                session.runInFlightPush(() -> {
                    if (msg.branch == null || msg.branch.isEmpty()) {
                        throw new SyncVaultException("Push rejected: branch name cannot be empty.");
                    }
                    // PUSH_REQ and PUSH_OBJECTS are separate messages. The tip the
                    // client observed is carried here and compared under this lock.
                    String current = normalizeTip(session.getRef(msg.branch));
                    String expected = normalizeTip(msg.expectedOldTip);
                    if (!Objects.equals(current, expected)) {
                        throw new SyncVaultException(
                                "Push rejected: expected tip " + displayTip(expected)
                                        + " but branch " + msg.branch + " is at " + displayTip(current) + ".");
                    }

                    Map<String, Commit> incoming = new LinkedHashMap<>();
                    if (msg.commits != null) {
                        for (byte[] commitBytes : msg.commits) {
                            Commit c = commitFromWire(commitBytes, "Push");
                            incoming.put(c.getSelfHash(), c);
                        }
                    }
                    if (msg.blobs != null) {
                        for (Map.Entry<String, byte[]> entry : msg.blobs.entrySet()) {
                            verifyBlobBytes(entry.getKey(), entry.getValue(), "Push");
                        }
                    }
                    if (current != null && !fastForwards(session, msg.tipHash, current, incoming)) {
                        throw new SyncVaultException("Push rejected: not a fast-forward.");
                    }
                    for (Commit c : incoming.values()) {
                        ingestCommit(session, c, "Push");
                    }
                    if (msg.blobs != null) {
                        for (Map.Entry<String, byte[]> entry : msg.blobs.entrySet()) {
                            ingestBlob(session, entry.getKey(), entry.getValue(), "Push");
                        }
                    }
                    verifyPushIntegrity(session, msg.tipHash);
                    session.setRef(msg.branch, msg.tipHash);
                });
                ctx.writeAndFlush(SyncMessage.ack());
            } catch (Throwable t) {
                ctx.writeAndFlush(SyncMessage.error(t.getMessage()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /**
     * Null and empty both mean "no tip" (a branch that does not exist yet).
     */
    static String normalizeTip(String tip) {
        if (tip == null || tip.isEmpty()) {
            return null;
        }
        return tip;
    }

    private static String displayTip(String tip) {
        return tip == null ? "absent" : tip;
    }

    /**
     * Reject a blob whose bytes do not hash to the id the peer claimed.
     * {@code Store.putBlob} is {@code INSERT OR IGNORE}, so writing a mismatched
     * body would keep the wrong bytes under that id forever.
     */
    static void verifyBlobBytes(String claimedId, byte[] body, String action) {
        if (claimedId == null || claimedId.isEmpty()) {
            throw new SyncVaultException(action + " rejected: missing blob id.");
        }
        if (body == null || !Utils.hash(body).equals(claimedId)) {
            throw new SyncVaultException(action + " rejected: blob " + claimedId + " content hash mismatch.");
        }
    }

    /**
     * Decode wire commit bytes and require {@code hash(bytes) == selfHash == contentId}.
     */
    static Commit commitFromWire(byte[] commitBytes, String action) {
        if (commitBytes == null || commitBytes.length == 0) {
            throw new SyncVaultException(action + " rejected: missing commit bytes.");
        }
        String actual = Utils.hash(commitBytes);
        Commit commit = SyncMessage.deserializeCommit(commitBytes);
        if (commit.getSelfHash() == null || !actual.equals(commit.getSelfHash())
                || !actual.equals(commit.contentId())) {
            throw new SyncVaultException(action + " rejected: commit " + commit.getSelfHash()
                    + " content hash mismatch.");
        }
        return commit;
    }

    static void ingestBlob(Store.Session session, String claimedId, byte[] body, String action) {
        verifyBlobBytes(claimedId, body, action);
        if (session.hasObject(claimedId)) {
            byte[] existing = session.getBlob(claimedId);
            if (existing == null || !Utils.hash(existing).equals(claimedId)) {
                throw new SyncVaultException(action + " rejected: blob " + claimedId + " content hash mismatch.");
            }
            return;
        }
        session.putBlob(claimedId, body);
    }

    static void ingestCommit(Store.Session session, byte[] commitBytes, String action) {
        ingestCommit(session, commitFromWire(commitBytes, action), action);
    }

    private static void ingestCommit(Store.Session session, Commit commit, String action) {
        String id = commit.getSelfHash();
        if (session.hasObject(id)) {
            byte[] existing = session.getBlob(id);
            if (existing == null || !Utils.hash(existing).equals(id)) {
                throw new SyncVaultException(action + " rejected: commit " + id + " content hash mismatch.");
            }
            return;
        }
        session.putCommit(commit);
    }

    /**
     * True when {@code oldTip} is {@code newTip} or can be reached by walking
     * parents. Commits in {@code incoming} are visible before they are written.
     */
    static boolean fastForwards(Store.Session session, String newTip, String oldTip,
                                Map<String, Commit> incoming) {
        if (oldTip == null || oldTip.isEmpty()) {
            return true;
        }
        if (newTip == null || newTip.isEmpty()) {
            return false;
        }
        Map<String, Commit> known = (incoming != null) ? incoming : Collections.emptyMap();
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(newTip);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            if (id.equals(oldTip)) {
                return true;
            }
            Commit commit = known.get(id);
            if (commit == null && session != null) {
                commit = session.getCommit(id);
            }
            if (commit == null) {
                continue;
            }
            if (commit.getParent() != null && !commit.getParent().isEmpty()) {
                queue.add(commit.getParent());
            }
            if (commit.getSecondParent() != null && !commit.getSecondParent().isEmpty()) {
                queue.add(commit.getSecondParent());
            }
        }
        return false;
    }

    static void verifyPushIntegrity(Store.Session session, String tipHash) {
        if (tipHash == null || tipHash.isEmpty()) {
            throw new SyncVaultException("Push rejected: missing tip commit.");
        }
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visitedCommits = new HashSet<>();
        Set<String> verifiedBlobs = new HashSet<>();

        queue.add(tipHash);
        visitedCommits.add(tipHash);

        while (!queue.isEmpty()) {
            String commitHash = queue.poll();
            byte[] raw = session.getBlob(commitHash);
            if (raw == null) {
                throw new SyncVaultException("Push rejected: missing commit " + commitHash);
            }
            if (!Utils.hash(raw).equals(commitHash)) {
                throw new SyncVaultException("Push rejected: commit " + commitHash + " content hash mismatch.");
            }
            Commit commit = session.getCommit(commitHash);
            if (commit == null) {
                throw new SyncVaultException("Push rejected: missing commit " + commitHash);
            }
            if (commit.getNameToHash() != null) {
                for (String blobHash : commit.getNameToHash().values()) {
                    if (blobHash == null || blobHash.isEmpty()) {
                        throw new SyncVaultException("Push rejected: missing blob " + blobHash);
                    }
                    if (verifiedBlobs.add(blobHash)) {
                        byte[] blob = session.getBlob(blobHash);
                        if (blob == null) {
                            throw new SyncVaultException("Push rejected: missing blob " + blobHash);
                        }
                        if (!Utils.hash(blob).equals(blobHash)) {
                            throw new SyncVaultException("Push rejected: blob " + blobHash + " content hash mismatch.");
                        }
                    }
                }
            }
            String p1 = commit.getParent();
            if (p1 != null && !p1.isEmpty() && visitedCommits.add(p1)) {
                queue.add(p1);
            }
            String p2 = commit.getSecondParent();
            if (p2 != null && !p2.isEmpty() && visitedCommits.add(p2)) {
                queue.add(p2);
            }
        }
    }
}
