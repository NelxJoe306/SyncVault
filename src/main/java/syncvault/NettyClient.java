package syncvault;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP client for SyncVault push and fetch operations against a remote server.
 */
public class NettyClient {

    private static final int TIMEOUT_SECONDS = 30;

    public static void fetch(String remoteName, String remotePath, String branchName) {
        fetch(remoteName, remotePath, branchName, null, null, false, false);
    }

    public static void fetch(String remoteName, String remotePath, String branchName,
                             String explicitToken, File explicitTlsCa, boolean explicitTlsInsecure) {
        fetch(remoteName, remotePath, branchName, explicitToken, explicitTlsCa, explicitTlsInsecure, false);
    }

    public static void fetch(String remoteName, String remotePath, String branchName,
                             String explicitToken, File explicitTlsCa,
                             boolean explicitTlsInsecure, boolean explicitTls) {
        HostPort hp = parseHostPort(remotePath);
        if (hp == null) {
            throw new SyncVaultException("Could not connect to remote.");
        }

        File syncvaultDir = Repository.syncvaultDirForSession(Store.getDefault());
        String token = Repository.resolveSyncToken(explicitToken, syncvaultDir);
        SslContext sslContext = resolveClientSslContext(
                hp, explicitTlsCa, explicitTlsInsecure, explicitTls, syncvaultDir);

        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel ch = null;
        String exitMessage = null;
        try {
            ClientHandler handler = new ClientHandler();
            Bootstrap b = buildBootstrap(group, hp, sslContext, handler);

            try {
                ch = b.connect(hp.host, hp.port).sync().channel();
            } catch (Exception e) {
                exitMessage = "Could not connect to remote.";
                return;
            }

            String hsError = awaitTlsHandshake(ch);
            if (hsError != null) {
                exitMessage = hsError;
                return;
            }

            SyncMessage helloResp = handler.request(ch, SyncMessage.hello(token));
            String authError = checkHelloResponse(helloResp, sslContext != null);
            if (authError != null) {
                exitMessage = authError;
                return;
            }

            SyncMessage tipResp = handler.request(ch, SyncMessage.fetchReq(branchName));
            if (tipResp == null) {
                exitMessage = "Network error.";
                return;
            }
            if (tipResp.type == SyncMessage.Type.ERROR || tipResp.tipHash == null) {
                exitMessage = "That remote does not have that branch.";
                return;
            }

            String remoteHead = tipResp.tipHash;
            Set<String> have = new HashSet<>(Store.allCommitIds());
            SyncMessage objResp = handler.request(ch, SyncMessage.fetchHave(branchName, remoteHead, have));
            if (objResp == null || objResp.type != SyncMessage.Type.FETCH_OBJECTS) {
                exitMessage = "Network error.";
                return;
            }

            Store.begin();
            try {
                if (objResp.commits != null) {
                    for (byte[] commitBytes : objResp.commits) {
                        NettyServer.ingestCommit(Store.getDefault(), commitBytes, "Fetch");
                    }
                }
                if (objResp.blobs != null) {
                    for (Map.Entry<String, byte[]> entry : objResp.blobs.entrySet()) {
                        NettyServer.ingestBlob(Store.getDefault(), entry.getKey(), entry.getValue(), "Fetch");
                    }
                }
                byte[] tipBytes = Store.getBlob(remoteHead);
                if (tipBytes == null || !Utils.hash(tipBytes).equals(remoteHead)) {
                    throw new SyncVaultException(
                            "Fetch rejected: tip " + remoteHead + " content hash mismatch.");
                }
                Store.setRef(remoteName + "/" + branchName, remoteHead);
                Store.commitTx();
            } catch (Throwable ingestError) {
                Store.rollback();
                if (ingestError instanceof RuntimeException) {
                    throw (RuntimeException) ingestError;
                }
                if (ingestError instanceof Error) {
                    throw (Error) ingestError;
                }
                throw new SyncVaultException(ingestError.getMessage());
            }

            ch.writeAndFlush(SyncMessage.ack()).syncUninterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitMessage = "Network error.";
        } finally {
            if (ch != null) {
                ch.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            if (exitMessage != null) {
                throw new SyncVaultException(exitMessage);
            }
        }
    }

    public static void push(String remotePath, String branchName, String localHead) {
        push(remotePath, branchName, localHead, null, null, false, false);
    }

    public static void push(String remotePath, String branchName, String localHead,
                            String explicitToken, File explicitTlsCa, boolean explicitTlsInsecure) {
        push(remotePath, branchName, localHead, explicitToken, explicitTlsCa, explicitTlsInsecure, false);
    }

    public static void push(String remotePath, String branchName, String localHead,
                            String explicitToken, File explicitTlsCa,
                            boolean explicitTlsInsecure, boolean explicitTls) {
        HostPort hp = parseHostPort(remotePath);
        if (hp == null) {
            throw new SyncVaultException("Could not connect to remote.");
        }

        File syncvaultDir = Repository.syncvaultDirForSession(Store.getDefault());
        String token = Repository.resolveSyncToken(explicitToken, syncvaultDir);
        SslContext sslContext = resolveClientSslContext(
                hp, explicitTlsCa, explicitTlsInsecure, explicitTls, syncvaultDir);

        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel ch = null;
        String exitMessage = null;
        try {
            ClientHandler handler = new ClientHandler();
            Bootstrap b = buildBootstrap(group, hp, sslContext, handler);

            try {
                ch = b.connect(hp.host, hp.port).sync().channel();
            } catch (Exception e) {
                exitMessage = "Could not connect to remote.";
                return;
            }

            String hsError = awaitTlsHandshake(ch);
            if (hsError != null) {
                exitMessage = hsError;
                return;
            }

            SyncMessage helloResp = handler.request(ch, SyncMessage.hello(token));
            String authError = checkHelloResponse(helloResp, sslContext != null);
            if (authError != null) {
                exitMessage = authError;
                return;
            }

            SyncMessage tipResp = handler.request(ch, SyncMessage.pushReq(branchName, localHead));
            if (tipResp == null || tipResp.type != SyncMessage.Type.PUSH_TIP) {
                exitMessage = "Network error.";
                return;
            }

            String remoteHead = tipResp.tipHash;
            if (remoteHead != null && !Repository.isAncestor(localHead, remoteHead)) {
                exitMessage = "Please pull down remote changes before pushing.";
                return;
            }

            Set<String> remoteHave = (tipResp.hashes != null) ? tipResp.hashes : Collections.emptySet();
            List<byte[]> commits = new ArrayList<>();
            Map<String, byte[]> blobs = new LinkedHashMap<>();

            if (localHead != null) {
                Queue<String> qSync = new ArrayDeque<>();
                Set<String> visitedSync = new HashSet<>();
                qSync.add(localHead);
                visitedSync.add(localHead);
                while (!qSync.isEmpty()) {
                    String id = qSync.poll();
                    Commit c = Store.getCommit(id);
                    if (c == null) {
                        continue;
                    }
                    if (!remoteHave.contains(id)) {
                        commits.add(c.encode());
                        for (String blobHash : c.getNameToHash().values()) {
                            if (!blobs.containsKey(blobHash)) {
                                byte[] localBlob = Store.getBlob(blobHash);
                                if (localBlob != null) {
                                    blobs.put(blobHash, localBlob);
                                }
                            }
                        }
                    }
                    for (String p : Commit.return_parents(id)) {
                        if (p != null && !visitedSync.contains(p)) {
                            visitedSync.add(p);
                            qSync.add(p);
                        }
                    }
                }
            }

            SyncMessage pushResp = handler.request(ch,
                    SyncMessage.pushObjects(branchName, localHead, commits, blobs, remoteHead));
            if (pushResp == null) {
                exitMessage = "Network error.";
            } else if (pushResp.type == SyncMessage.Type.ERROR) {
                exitMessage = (pushResp.error != null && !pushResp.error.isEmpty())
                        ? pushResp.error
                        : "Push rejected by remote.";
            } else if (pushResp.type != SyncMessage.Type.ACK) {
                exitMessage = "Network error.";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitMessage = "Network error.";
        } finally {
            if (ch != null) {
                ch.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            if (exitMessage != null) {
                throw new SyncVaultException(exitMessage);
            }
        }
    }

    private static Bootstrap buildBootstrap(EventLoopGroup group, HostPort hp,
                                            SslContext sslContext, ClientHandler handler) {
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        if (sslContext != null) {
                            pipeline.addLast(sslContext.newHandler(socketChannel.alloc(), hp.host, hp.port));
                        }
                        pipeline.addLast(
                                new LengthFieldBasedFrameDecoder(NettyServer.MAX_FRAME_BYTES, 0, 4, 0, 4),
                                new LengthFieldPrepender(4),
                                new SyncMessage.Codec(),
                                handler
                        );
                    }
                });
        return b;
    }

    private static String awaitTlsHandshake(Channel ch) {
        SslHandler sslHandler = ch.pipeline().get(SslHandler.class);
        if (sslHandler == null) {
            return null;
        }
        try {
            if (!sslHandler.handshakeFuture().await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return "TLS handshake failed: timed out.";
            }
            if (!sslHandler.handshakeFuture().isSuccess()) {
                Throwable cause = sslHandler.handshakeFuture().cause();
                return "TLS handshake failed: " + rootMessage(cause);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "TLS handshake failed: interrupted.";
        }
    }

    private static String checkHelloResponse(SyncMessage helloResp, boolean tlsActive) {
        if (helloResp == null) {
            return "Network error.";
        }
        if (helloResp.type == SyncMessage.Type.HELLO_OK) {
            return null;
        }
        if (helloResp.type == SyncMessage.Type.ERROR && helloResp.error != null
                && !helloResp.error.equals("Connection closed.")) {
            return helloResp.error;
        }
        if (tlsActive) {
            return "TLS handshake failed: connection closed by remote.";
        }
        return "Network error.";
    }

    static SslContext resolveClientSslContext(HostPort hp, File explicitTlsCa,
                                              boolean explicitTlsInsecure, boolean explicitTls,
                                              File syncvaultDir) {
        File caFile = (explicitTlsCa != null) ? explicitTlsCa : Repository.envFile(Repository.ENV_TLS_CA);
        boolean insecure = explicitTlsInsecure || Repository.envBool(Repository.ENV_TLS_INSECURE);
        boolean forceTls = explicitTls || Repository.envBool(Repository.ENV_TLS);
        boolean useTls = hp.tls || forceTls || insecure || (caFile != null);

        if (useTls && caFile == null && !insecure && syncvaultDir != null) {
            File localCa = new File(syncvaultDir, Repository.TLS_CA_FILE);
            if (localCa.exists() && localCa.isFile()) {
                caFile = localCa;
            }
        }
        return buildClientSslContext(useTls, caFile, insecure);
    }

    static SslContext buildClientSslContext(boolean useTls, File caFile, boolean insecure) {
        if (!useTls) {
            return null;
        }
        if (caFile != null && (!caFile.exists() || !caFile.isFile())) {
            throw new SyncVaultException("TLS CA certificate file not found: " + caFile.getPath());
        }
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();
            if (caFile != null) {
                builder.trustManager(caFile);
            } else if (insecure) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            // Netty leaves this null, which disables hostname checks, so any
            // certificate the trust store accepts would authenticate every host.
            // Skip identification only when the insecure trust manager is used.
            if (caFile != null || !insecure) {
                builder.endpointIdentificationAlgorithm("HTTPS");
            }
            return builder.build();
        } catch (Exception e) {
            throw new SyncVaultException("Failed to initialize client TLS context: " + e.getMessage());
        }
    }

    static HostPort parseHostPort(String remotePath) {
        if (remotePath == null) {
            return null;
        }
        String addr = remotePath;
        boolean tls = false;
        if (addr.startsWith("nettys://")) {
            addr = addr.substring("nettys://".length());
            tls = true;
        } else if (addr.startsWith("netty://")) {
            addr = addr.substring("netty://".length());
        }
        if (addr.endsWith("/")) {
            addr = addr.substring(0, addr.length() - 1);
        }
        int colonIdx = addr.lastIndexOf(':');
        if (colonIdx <= 0 || colonIdx == addr.length() - 1) {
            return null;
        }
        String host = addr.substring(0, colonIdx);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || host.contains("/") || host.contains("\\")) {
            return null;
        }
        try {
            int port = Integer.parseInt(addr.substring(colonIdx + 1));
            if (port < 1 || port > 65535) {
                return null;
            }
            return new HostPort(host, port, tls);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static class HostPort {
        final String host;
        final int port;
        final boolean tls;

        HostPort(String host, int port) {
            this(host, port, false);
        }

        HostPort(String host, int port, boolean tls) {
            this.host = host;
            this.port = port;
            this.tls = tls;
        }
    }

    private static boolean isSslFailure(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof SSLException) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) {
            return "handshake rejected";
        }
        Throwable curr = t;
        while (curr.getCause() != null) {
            curr = curr.getCause();
        }
        return (curr.getMessage() != null) ? curr.getMessage() : curr.getClass().getSimpleName();
    }

    static class ClientHandler extends SimpleChannelInboundHandler<SyncMessage> {
        private final BlockingQueue<SyncMessage> inbox = new LinkedBlockingQueue<>();

        SyncMessage request(Channel ch, SyncMessage req) throws InterruptedException {
            ChannelFuture f = ch.writeAndFlush(req).await();
            if (!f.isSuccess() && isSslFailure(f.cause())) {
                return SyncMessage.error("TLS handshake failed: " + rootMessage(f.cause()));
            }
            return inbox.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SyncMessage msg) {
            inbox.offer(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            inbox.offer(SyncMessage.error("Connection closed."));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (isSslFailure(cause)) {
                inbox.offer(SyncMessage.error("TLS handshake failed: " + rootMessage(cause)));
            } else {
                inbox.offer(SyncMessage.error(cause.getMessage()));
            }
            ctx.close();
        }
    }
}
