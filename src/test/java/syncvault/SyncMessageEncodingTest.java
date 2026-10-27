package syncvault;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

public class SyncMessageEncodingTest {

    @Test
    public void roundTripsProtocolMessagesAndRejectsJavaSerialization() {
        Commit commit = new Commit("synced", new TreeMap<>(), null);
        byte[] commitBytes = commit.encode();
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        blobs.put("blob-a", new byte[] {1, 2, 3});
        blobs.put("blob-b", new byte[0]);

        assertRoundTrip(SyncMessage.hello(null));
        assertRoundTrip(SyncMessage.hello("vault-token"));
        assertRoundTrip(SyncMessage.helloOk());
        assertRoundTrip(SyncMessage.fetchReq("master"));
        assertRoundTrip(SyncMessage.fetchTip(null));
        assertRoundTrip(SyncMessage.fetchTip("abc"));
        assertRoundTrip(SyncMessage.fetchHave("master", "tip", null));
        assertRoundTrip(SyncMessage.fetchHave("master", "tip", Set.of()));
        assertRoundTrip(SyncMessage.fetchHave("master", "tip", Set.of("z", "a")));
        assertRoundTrip(SyncMessage.fetchObjects("tip", List.of(), blobs));
        assertRoundTrip(SyncMessage.fetchObjects("tip", List.of(commitBytes), null));
        assertRoundTrip(SyncMessage.pushReq("master", "local"));
        assertRoundTrip(SyncMessage.pushTip(null, Set.of("b", "a")));
        assertRoundTrip(SyncMessage.pushObjects("master", "local", List.of(commitBytes), blobs));
        assertRoundTrip(SyncMessage.ack());
        assertRoundTrip(SyncMessage.error("nope"));

        byte[] evil = new byte[] {(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        SyncVaultException rejected = assertThrows(SyncVaultException.class, () -> SyncMessage.decode(evil));
        assertTrue(rejected.getMessage().contains("Java serialization"));

        SyncMessage pushed = SyncMessage.pushObjects("master", commit.contentId(), List.of(commitBytes), blobs);
        byte[] framed = pushed.encode();
        assertEquals("SVM1", new String(framed, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        SyncMessage decoded = SyncMessage.decode(framed);
        Commit fromWire = SyncMessage.deserializeCommit(decoded.commits.get(0));
        assertEquals(commit.contentId(), fromWire.getSelfHash());
        assertEquals("synced", fromWire.getMessage());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.blobs.get("blob-a"));
    }

    @Test
    public void codecRoundTripsInsideLengthFrame() {
        Commit commit = new Commit("framed", new TreeMap<>(), "p");
        SyncMessage original = SyncMessage.pushObjects(
                "master", "tip", List.of(commit.encode()), Map.of("h", new byte[] {9}));
        EmbeddedChannel channel = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(NettyServer.MAX_FRAME_BYTES, 0, 4, 0, 4),
                new LengthFieldPrepender(4),
                new SyncMessage.Codec());
        try {
            channel.writeOutbound(original);
            channel.checkException();
            ByteBuf framed = Unpooled.buffer();
            ByteBuf part;
            while ((part = channel.readOutbound()) != null) {
                framed.writeBytes(part);
                part.release();
            }
            assertTrue(framed.readableBytes() > 4);
            assertTrue(channel.writeInbound(framed));
            SyncMessage decoded = channel.readInbound();
            assertNotNull(decoded);
            assertEquals(SyncMessage.Type.PUSH_OBJECTS, decoded.type);
            assertEquals("master", decoded.branch);
            assertEquals("tip", decoded.tipHash);
            assertArrayEquals(original.commits.get(0), decoded.commits.get(0));
            assertArrayEquals(new byte[] {9}, decoded.blobs.get("h"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void codecRejectsJavaSerializedFrame() {
        byte[] payload = new byte[] {(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x00};
        ByteBuf frame = Unpooled.buffer();
        frame.writeInt(payload.length);
        frame.writeBytes(payload);
        EmbeddedChannel channel = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(NettyServer.MAX_FRAME_BYTES, 0, 4, 0, 4),
                new LengthFieldPrepender(4),
                new SyncMessage.Codec());
        try {
            Exception thrown = assertThrows(Exception.class, () -> channel.writeInbound(frame));
            Throwable cursor = thrown;
            boolean sawRefusal = false;
            while (cursor != null) {
                if (cursor.getMessage() != null && cursor.getMessage().contains("Java serialization")) {
                    sawRefusal = true;
                    break;
                }
                cursor = cursor.getCause();
            }
            assertTrue(sawRefusal);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertRoundTrip(SyncMessage message) {
        byte[] encoded = message.encode();
        assertNotEquals((byte) 0xAC, encoded[0]);
        SyncMessage decoded = SyncMessage.decode(encoded);
        assertEquals(message.type, decoded.type);
        assertEquals(message.branch, decoded.branch);
        assertEquals(message.tipHash, decoded.tipHash);
        assertEquals(message.error, decoded.error);
        assertEquals(message.token, decoded.token);
        assertEquals(message.hashes, decoded.hashes);
        if (message.commits == null) {
            assertNull(decoded.commits);
        } else {
            assertEquals(message.commits.size(), decoded.commits.size());
            for (int i = 0; i < message.commits.size(); i++) {
                assertArrayEquals(message.commits.get(i), decoded.commits.get(i));
            }
        }
        if (message.blobs == null) {
            assertNull(decoded.blobs);
        } else {
            assertEquals(message.blobs.keySet(), decoded.blobs.keySet());
            for (Map.Entry<String, byte[]> entry : message.blobs.entrySet()) {
                assertArrayEquals(entry.getValue(), decoded.blobs.get(entry.getKey()));
            }
        }
    }
}
