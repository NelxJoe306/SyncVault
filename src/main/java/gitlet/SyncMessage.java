package gitlet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializable protocol messages for Netty push/fetch synchronization.
 */
public class SyncMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        HELLO,
        HELLO_OK,
        FETCH_REQ,
        FETCH_TIP,
        FETCH_HAVE,
        FETCH_OBJECTS,
        PUSH_REQ,
        PUSH_TIP,
        PUSH_OBJECTS,
        ACK,
        ERROR
    }

    public final Type type;
    public final String branch;
    public final String tipHash;
    public final Set<String> hashes;
    public final List<byte[]> commits;
    public final Map<String, byte[]> blobs;
    public final String error;

    private SyncMessage(Type type, String branch, String tipHash, Set<String> hashes,
                        List<byte[]> commits, Map<String, byte[]> blobs, String error) {
        this.type = type;
        this.branch = branch;
        this.tipHash = tipHash;
        this.hashes = hashes;
        this.commits = commits;
        this.blobs = blobs;
        this.error = error;
    }

    public static SyncMessage hello() {
        return new SyncMessage(Type.HELLO, null, null, null, null, null, null);
    }

    public static SyncMessage helloOk() {
        return new SyncMessage(Type.HELLO_OK, null, null, null, null, null, null);
    }

    public static SyncMessage fetchReq(String branch) {
        return new SyncMessage(Type.FETCH_REQ, branch, null, null, null, null, null);
    }

    public static SyncMessage fetchTip(String tipHash) {
        return new SyncMessage(Type.FETCH_TIP, null, tipHash, null, null, null, null);
    }

    public static SyncMessage fetchHave(String branch, String tipHash, Set<String> hashes) {
        return new SyncMessage(Type.FETCH_HAVE, branch, tipHash, hashes, null, null, null);
    }

    public static SyncMessage fetchObjects(String tipHash, List<byte[]> commits, Map<String, byte[]> blobs) {
        return new SyncMessage(Type.FETCH_OBJECTS, null, tipHash, null, commits, blobs, null);
    }

    public static SyncMessage pushReq(String branch, String tipHash) {
        return new SyncMessage(Type.PUSH_REQ, branch, tipHash, null, null, null, null);
    }

    public static SyncMessage pushTip(String tipHash, Set<String> hashes) {
        return new SyncMessage(Type.PUSH_TIP, null, tipHash, hashes, null, null, null);
    }

    public static SyncMessage pushObjects(String branch, String tipHash,
                                          List<byte[]> commits, Map<String, byte[]> blobs) {
        return new SyncMessage(Type.PUSH_OBJECTS, branch, tipHash, null, commits, blobs, null);
    }

    public static SyncMessage ack() {
        return new SyncMessage(Type.ACK, null, null, null, null, null, null);
    }

    public static SyncMessage error(String error) {
        return new SyncMessage(Type.ERROR, null, null, null, null, null, error);
    }

    public static Commit deserializeCommit(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Commit) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new GitletException(e.getMessage());
        }
    }

    /**
     * Codec converting between length-framed ByteBufs and SyncMessage instances.
     */
    public static class Codec extends MessageToMessageCodec<ByteBuf, SyncMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, SyncMessage msg, List<Object> out) {
            byte[] bytes = Utils.serialize(msg);
            ByteBuf buf = ctx.alloc().buffer(bytes.length);
            buf.writeBytes(bytes);
            out.add(buf);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            byte[] bytes = new byte[in.readableBytes()];
            in.readBytes(bytes);
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                out.add(ois.readObject());
            }
        }
    }
}
