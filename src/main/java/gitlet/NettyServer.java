package gitlet;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP server exposing a Gitlet repository for push/fetch synchronization.
 */
public class NettyServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 9418;
    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private final Store.Session session;
    private final boolean ownsSession;
    private final int requestedPort;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int boundPort;

    public NettyServer(int port) {
        this(Store.getDefault().getDbFile(), port);
    }

    public NettyServer(File gitletDir, int port) {
        this(Store.open(gitletDir), port, true);
    }

    public NettyServer(Store.Session session, int port) {
        this(session, port, false);
    }

    private NettyServer(Store.Session session, int port, boolean ownsSession) {
        this.session = session;
        this.ownsSession = ownsSession;
        this.requestedPort = port;
    }

    public NettyServer start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new LengthFieldBasedFrameDecoder(MAX_FRAME_BYTES, 0, 4, 0, 4),
                                    new LengthFieldPrepender(4),
                                    new SyncMessage.Codec(),
                                    new ServerHandler(session)
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

        ServerHandler(Store.Session session) {
            this.session = session;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SyncMessage msg) {
            synchronized (session) {
                switch (msg.type) {
                    case HELLO:
                        ctx.writeAndFlush(SyncMessage.helloOk());
                        break;
                    case FETCH_REQ:
                        handleFetchReq(ctx, msg);
                        break;
                    case FETCH_HAVE:
                        handleFetchHave(ctx, msg);
                        break;
                    case PUSH_REQ:
                        handlePushReq(ctx, msg);
                        break;
                    case PUSH_OBJECTS:
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
                                commits.add(Utils.serialize(c));
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
            session.begin();
            try {
                if (msg.commits != null) {
                    for (byte[] commitBytes : msg.commits) {
                        Commit c = SyncMessage.deserializeCommit(commitBytes);
                        if (!session.hasObject(c.getSelfHash())) {
                            session.putCommit(c);
                        }
                    }
                }
                if (msg.blobs != null) {
                    for (Map.Entry<String, byte[]> entry : msg.blobs.entrySet()) {
                        if (!session.hasObject(entry.getKey())) {
                            session.putBlob(entry.getKey(), entry.getValue());
                        }
                    }
                }
                session.setRef(msg.branch, msg.tipHash);
                session.commitTx();
                ctx.writeAndFlush(SyncMessage.ack());
            } catch (Throwable t) {
                session.rollback();
                ctx.writeAndFlush(SyncMessage.error(t.getMessage()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
