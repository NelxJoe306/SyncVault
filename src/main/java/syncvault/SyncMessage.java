package syncvault;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Explicitly encoded protocol messages for Netty push/fetch synchronization.
 *
 * <p>SVM1 layout (big-endian), framed by Netty's 4-byte length prefix:
 * <pre>
 * magic     4 bytes  'S' 'V' 'M' '1'
 * version   uint16   1
 * type      uint8    1 HELLO, 2 HELLO_OK, 3 FETCH_REQ, 4 FETCH_TIP,
 *                    5 FETCH_HAVE, 6 FETCH_OBJECTS, 7 PUSH_REQ, 8 PUSH_TIP,
 *                    9 PUSH_OBJECTS, 10 ACK, 11 ERROR
 * branch    nullable string
 * tipHash   nullable string
 * error     nullable string
 * token     nullable string
 * hashes    nullable list of strings (encoded in sorted order)
 * commits   nullable list of opaque byte arrays (SVC1 commit encodings)
 * blobs     nullable list of (string id, byte array) pairs
 * </pre>
 * A nullable value is a uint8 flag (0 = null, 1 = present). Lists then have a
 * uint32 count. Null and empty are distinct. Commit payloads are not Java
 * objects; {@link #deserializeCommit} only accepts SVC1.
 */
public class SyncMessage {
    private static final byte[] MAGIC = new byte[] {'S', 'V', 'M', '1'};
    private static final int VERSION = 1;

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
    public final String token;

    private SyncMessage(Type type, String branch, String tipHash, Set<String> hashes,
                        List<byte[]> commits, Map<String, byte[]> blobs, String error) {
        this(type, branch, tipHash, hashes, commits, blobs, error, null);
    }

    private SyncMessage(Type type, String branch, String tipHash, Set<String> hashes,
                        List<byte[]> commits, Map<String, byte[]> blobs, String error, String token) {
        this.type = type;
        this.branch = branch;
        this.tipHash = tipHash;
        this.hashes = hashes;
        this.commits = commits;
        this.blobs = blobs;
        this.error = error;
        this.token = token;
    }

    public static SyncMessage hello() {
        return hello(null);
    }

    public static SyncMessage hello(String token) {
        return new SyncMessage(Type.HELLO, null, null, null, null, null, null, token);
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

    /**
     * Decodes one SVC1 commit payload from a push or fetch message and sets
     * {@code self_hash} to the hash of those canonical bytes.
     */
    public static Commit deserializeCommit(byte[] data) {
        Commit commit = Commit.decode(data);
        commit.setSelfHash(commit.contentId());
        return commit;
    }

    /** SVM1 bytes for this message. */
    public byte[] encode() {
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            out.write(MAGIC);
            out.writeShort(VERSION);
            out.writeByte(typeCode(type));
            BinaryFields.writeNullableString(out, branch);
            BinaryFields.writeNullableString(out, tipHash);
            BinaryFields.writeNullableString(out, error);
            BinaryFields.writeNullableString(out, token);
            writeHashes(out, hashes);
            writeCommits(out, commits);
            writeBlobs(out, blobs);
            out.flush();
            return raw.toByteArray();
        } catch (IOException e) {
            throw new SyncVaultException("failed to encode sync message");
        }
    }

    /** Decodes SVM1 bytes. Rejects Java serialization ({@code 0xACED}). */
    public static SyncMessage decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new SyncVaultException("missing sync message");
        }
        BinaryFields.rejectJavaSerialization(data, "sync message");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new SyncVaultException("unrecognized sync message encoding");
            }
            int version = BinaryFields.readUnsignedShort(in);
            if (version != VERSION) {
                throw new SyncVaultException("unsupported sync message version: " + version);
            }
            Type type = typeFromCode(BinaryFields.readUnsignedByte(in));
            String branch = BinaryFields.readNullableString(in);
            String tipHash = BinaryFields.readNullableString(in);
            String error = BinaryFields.readNullableString(in);
            String token = BinaryFields.readNullableString(in);
            Set<String> hashes = readHashes(in);
            List<byte[]> commits = readCommits(in);
            Map<String, byte[]> blobs = readBlobs(in);
            if (in.available() != 0) {
                throw new SyncVaultException("trailing bytes in sync message");
            }
            return new SyncMessage(type, branch, tipHash, hashes, commits, blobs, error, token);
        } catch (SyncVaultException e) {
            throw e;
        } catch (IOException e) {
            throw new SyncVaultException("invalid sync message encoding");
        }
    }

    private static void writeHashes(DataOutputStream out, Set<String> hashes) throws IOException {
        if (hashes == null) {
            out.writeByte(0);
            return;
        }
        out.writeByte(1);
        TreeSet<String> sorted = new TreeSet<>();
        for (String hash : hashes) {
            if (hash == null) {
                throw new SyncVaultException("sync hash is null");
            }
            sorted.add(hash);
        }
        out.writeInt(sorted.size());
        for (String hash : sorted) {
            BinaryFields.writeString(out, hash);
        }
    }

    private static Set<String> readHashes(DataInputStream in) {
        int flag = BinaryFields.readUnsignedByte(in);
        if (flag == 0) {
            return null;
        }
        if (flag != 1) {
            throw new SyncVaultException("invalid hash list flag");
        }
        int count = BinaryFields.readCount(in);
        LinkedHashSet<String> hashes = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            if (!hashes.add(BinaryFields.readString(in))) {
                throw new SyncVaultException("duplicate sync hash");
            }
        }
        return hashes;
    }

    private static void writeCommits(DataOutputStream out, List<byte[]> commits) throws IOException {
        if (commits == null) {
            out.writeByte(0);
            return;
        }
        out.writeByte(1);
        out.writeInt(commits.size());
        for (byte[] commit : commits) {
            BinaryFields.writeByteArray(out, commit);
        }
    }

    private static List<byte[]> readCommits(DataInputStream in) {
        int flag = BinaryFields.readUnsignedByte(in);
        if (flag == 0) {
            return null;
        }
        if (flag != 1) {
            throw new SyncVaultException("invalid commit list flag");
        }
        int count = BinaryFields.readCount(in);
        List<byte[]> commits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            commits.add(BinaryFields.readByteArray(in));
        }
        return commits;
    }

    private static void writeBlobs(DataOutputStream out, Map<String, byte[]> blobs) throws IOException {
        if (blobs == null) {
            out.writeByte(0);
            return;
        }
        out.writeByte(1);
        out.writeInt(blobs.size());
        for (Map.Entry<String, byte[]> entry : blobs.entrySet()) {
            BinaryFields.writeString(out, entry.getKey());
            BinaryFields.writeByteArray(out, entry.getValue());
        }
    }

    private static Map<String, byte[]> readBlobs(DataInputStream in) {
        int flag = BinaryFields.readUnsignedByte(in);
        if (flag == 0) {
            return null;
        }
        if (flag != 1) {
            throw new SyncVaultException("invalid blob map flag");
        }
        int count = BinaryFields.readCount(in);
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = BinaryFields.readString(in);
            byte[] body = BinaryFields.readByteArray(in);
            if (blobs.put(id, body) != null) {
                throw new SyncVaultException("duplicate blob id");
            }
        }
        return blobs;
    }

    private static int typeCode(Type type) {
        return switch (type) {
            case HELLO -> 1;
            case HELLO_OK -> 2;
            case FETCH_REQ -> 3;
            case FETCH_TIP -> 4;
            case FETCH_HAVE -> 5;
            case FETCH_OBJECTS -> 6;
            case PUSH_REQ -> 7;
            case PUSH_TIP -> 8;
            case PUSH_OBJECTS -> 9;
            case ACK -> 10;
            case ERROR -> 11;
        };
    }

    private static Type typeFromCode(int code) {
        return switch (code) {
            case 1 -> Type.HELLO;
            case 2 -> Type.HELLO_OK;
            case 3 -> Type.FETCH_REQ;
            case 4 -> Type.FETCH_TIP;
            case 5 -> Type.FETCH_HAVE;
            case 6 -> Type.FETCH_OBJECTS;
            case 7 -> Type.PUSH_REQ;
            case 8 -> Type.PUSH_TIP;
            case 9 -> Type.PUSH_OBJECTS;
            case 10 -> Type.ACK;
            case 11 -> Type.ERROR;
            default -> throw new SyncVaultException("unknown sync message type: " + code);
        };
    }

    /**
     * Codec converting between length-framed ByteBufs and SyncMessage instances.
     */
    public static class Codec extends MessageToMessageCodec<ByteBuf, SyncMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, SyncMessage msg, List<Object> out) {
            byte[] bytes = msg.encode();
            ByteBuf buf = ctx.alloc().buffer(bytes.length);
            buf.writeBytes(bytes);
            out.add(buf);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            byte[] bytes = new byte[in.readableBytes()];
            in.readBytes(bytes);
            out.add(SyncMessage.decode(bytes));
        }
    }
}
