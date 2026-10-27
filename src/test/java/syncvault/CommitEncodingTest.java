package syncvault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

public class CommitEncodingTest {

    @Test
    public void roundTripIgnoresSelfHashAndUsesSvc1(@TempDir Path tempDir) throws Exception {
        TreeMap<String, String> files = new TreeMap<>();
        files.put("b.txt", "bbb");
        files.put("a.txt", "aaa");
        Commit commit = new Commit("merge me", files, "parent-1", "parent-2");
        byte[] encoded = commit.encode();

        assertEquals("SVC1", new String(encoded, 0, 4, StandardCharsets.US_ASCII));
        assertNotEquals((byte) 0xAC, encoded[0]);
        commit.setSelfHash("not-part-of-the-encoding");
        assertArrayEquals(encoded, commit.encode());
        assertEquals(Utils.hash(encoded), commit.contentId());

        Commit decoded = Commit.decode(encoded);
        assertNull(decoded.getSelfHash());
        assertEquals("merge me", decoded.getMessage());
        assertEquals(commit.getTimestamp(), decoded.getTimestamp());
        assertEquals("parent-1", decoded.getParent());
        assertEquals("parent-2", decoded.getSecondParent());
        assertEquals(files, decoded.getNameToHash());

        Commit onWire = SyncMessage.deserializeCommit(encoded);
        assertEquals(commit.contentId(), onWire.getSelfHash());
        assertEquals(files, onWire.getNameToHash());

        commit.setSelfHash(commit.contentId());
        Store.Session store = Store.open(tempDir.toFile());
        try {
            store.createSchema();
            store.putCommit(commit);
            byte[] stored;
            try (PreparedStatement ps = store.getConnection().prepareStatement(
                    "SELECT data FROM object WHERE hash = ? AND type = 'commit'")) {
                ps.setString(1, commit.getSelfHash());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    stored = rs.getBytes(1);
                }
            }
            assertArrayEquals(encoded, stored);
            Commit loaded = store.getCommit(commit.getSelfHash());
            assertEquals(commit.getSelfHash(), loaded.getSelfHash());
            assertEquals("merge me", loaded.getMessage());
            assertEquals("parent-2", loaded.getSecondParent());
            assertEquals(files, loaded.getNameToHash());
        } finally {
            store.close();
        }
    }

    @Test
    public void rejectsJavaSerializationAndNonCanonicalBytes(@TempDir Path tempDir) throws Exception {
        Commit commit = new Commit("old", new TreeMap<>(), null);
        byte[] legacy;
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(raw)) {
            out.writeObject(commit);
            legacy = raw.toByteArray();
        }
        assertEquals((byte) 0xAC, legacy[0]);
        assertEquals((byte) 0xED, legacy[1]);

        SyncVaultException decoded = assertThrows(SyncVaultException.class, () -> Commit.decode(legacy));
        assertTrue(decoded.getMessage().contains("Java serialization"));
        SyncVaultException wire = assertThrows(SyncVaultException.class,
                () -> SyncMessage.deserializeCommit(legacy));
        assertTrue(wire.getMessage().contains("Java serialization"));

        Store.Session store = Store.open(tempDir.toFile());
        try {
            store.createSchema();
            try (PreparedStatement ps = store.getConnection().prepareStatement(
                    "INSERT INTO object (hash, type, data) VALUES ('legacy', 'commit', ?)")) {
                ps.setBytes(1, legacy);
                ps.executeUpdate();
            }
            SyncVaultException loaded = assertThrows(SyncVaultException.class, () -> store.getCommit("legacy"));
            assertTrue(loaded.getMessage().contains("Java serialization"));
        } finally {
            store.close();
        }

        Commit migrated = LegacyCommitReader.read(legacy);
        assertEquals("old", migrated.getMessage());
        assertNull(migrated.getParent());
        assertNotEquals(Utils.hash(legacy), migrated.contentId());

        byte[] hashMap;
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(raw)) {
            out.writeObject(new HashMap<String, String>());
            hashMap = raw.toByteArray();
        }
        SyncVaultException rejected = assertThrows(SyncVaultException.class, () -> LegacyCommitReader.read(hashMap));
        assertTrue(rejected.getMessage().toLowerCase().contains("filter")
                || rejected.getMessage().contains("REJECTED"));

        byte[] unsorted = unsortedFiles();
        SyncVaultException canonical = assertThrows(SyncVaultException.class, () -> Commit.decode(unsorted));
        assertTrue(canonical.getMessage().contains("not canonical"));
    }

    private static byte[] unsortedFiles() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.write(new byte[] {'S', 'V', 'C', '1'});
            out.writeShort(1);
            out.writeLong(5L);
            out.writeByte(1);
            writeRawString(out, "m");
            out.writeByte(0);
            out.writeByte(0);
            out.writeInt(2);
            writeRawString(out, "b.txt");
            writeRawString(out, "bbb");
            writeRawString(out, "a.txt");
            writeRawString(out, "aaa");
        }
        return raw.toByteArray();
    }

    private static void writeRawString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
