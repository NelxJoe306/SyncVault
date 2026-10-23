package gitlet;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP client for Gitlet push and fetch operations against a remote server.
 */
public class NettyClient {

    private static final int TIMEOUT_SECONDS = 30;

    public static void fetch(String remoteName, String remotePath, String branchName) {
        HostPort hp = parseHostPort(remotePath);
        if (hp == null) {
            System.out.println("Could not connect to remote.");
            System.exit(0);
            return;
        }

        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel ch = null;
        String exitMessage = null;
        try {
            ClientHandler handler = new ClientHandler();
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) {
                            socketChannel.pipeline().addLast(
                                    new LengthFieldBasedFrameDecoder(NettyServer.MAX_FRAME_BYTES, 0, 4, 0, 4),
                                    new LengthFieldPrepender(4),
                                    new SyncMessage.Codec(),
                                    handler
                            );
                        }
                    });

            try {
                ch = b.connect(hp.host, hp.port).sync().channel();
            } catch (Exception e) {
                exitMessage = "Could not connect to remote.";
                return;
            }

            SyncMessage helloResp = handler.request(ch, SyncMessage.hello());
            if (helloResp == null || helloResp.type != SyncMessage.Type.HELLO_OK) {
                exitMessage = "Network error.";
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
            if (objResp.commits != null) {
                for (byte[] commitBytes : objResp.commits) {
                    Commit c = SyncMessage.deserializeCommit(commitBytes);
                    if (!Store.hasObject(c.getSelfHash())) {
                        Store.putCommit(c);
                    }
                }
            }
            if (objResp.blobs != null) {
                for (Map.Entry<String, byte[]> entry : objResp.blobs.entrySet()) {
                    if (!Store.hasObject(entry.getKey())) {
                        Store.putBlob(entry.getKey(), entry.getValue());
                    }
                }
            }
            Store.setRef(remoteName + "/" + branchName, remoteHead);
            Store.commitTx();

            ch.writeAndFlush(SyncMessage.ack()).syncUninterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitMessage = "Network error.";
        } finally {
            if (ch != null) {
                ch.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }

        if (exitMessage != null) {
            System.out.println(exitMessage);
            System.exit(0);
        }
    }

    public static void push(String remotePath, String branchName, String localHead) {
        HostPort hp = parseHostPort(remotePath);
        if (hp == null) {
            System.out.println("Could not connect to remote.");
            System.exit(0);
            return;
        }

        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel ch = null;
        String exitMessage = null;
        try {
            ClientHandler handler = new ClientHandler();
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) {
                            socketChannel.pipeline().addLast(
                                    new LengthFieldBasedFrameDecoder(NettyServer.MAX_FRAME_BYTES, 0, 4, 0, 4),
                                    new LengthFieldPrepender(4),
                                    new SyncMessage.Codec(),
                                    handler
                            );
                        }
                    });

            try {
                ch = b.connect(hp.host, hp.port).sync().channel();
            } catch (Exception e) {
                exitMessage = "Could not connect to remote.";
                return;
            }

            SyncMessage helloResp = handler.request(ch, SyncMessage.hello());
            if (helloResp == null || helloResp.type != SyncMessage.Type.HELLO_OK) {
                exitMessage = "Network error.";
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
                        commits.add(Utils.serialize(c));
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
                    SyncMessage.pushObjects(branchName, localHead, commits, blobs));
            if (pushResp == null || pushResp.type != SyncMessage.Type.ACK) {
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
        }

        if (exitMessage != null) {
            System.out.println(exitMessage);
            System.exit(0);
        }
    }

    static HostPort parseHostPort(String remotePath) {
        if (remotePath == null) {
            return null;
        }
        String addr = remotePath;
        if (addr.startsWith("netty://")) {
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
        try {
            int port = Integer.parseInt(addr.substring(colonIdx + 1));
            if (port < 1 || port > 65535) {
                return null;
            }
            return new HostPort(host, port);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static class ClientHandler extends SimpleChannelInboundHandler<SyncMessage> {
        private final BlockingQueue<SyncMessage> inbox = new LinkedBlockingQueue<>();

        SyncMessage request(Channel ch, SyncMessage req) throws InterruptedException {
            ch.writeAndFlush(req).sync();
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
            inbox.offer(SyncMessage.error(cause.getMessage()));
            ctx.close();
        }
    }
}
