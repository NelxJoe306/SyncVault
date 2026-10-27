package syncvault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * Offline, read-only decoder for commits stored before the SVC1 encoding.
 *
 * <p>0.1.0-SNAPSHOT does not migrate repositories. Commit ids are now the
 * hash of the SVC1 bytes, so an old Java-serialized blob is a different
 * object and its branch pointers would be wrong even after a rewrite.
 * Re-run {@code init}. This reader exists so a local tool can inspect a
 * pre-SVC1 blob; {@code Store.getCommit}, {@code SyncMessage.deserializeCommit},
 * and the Netty server never call it.
 *
 * <p>The stream filter allows only {@code syncvault.Commit}, {@code java.util.TreeMap},
 * and {@code java.lang.String}. That is still not a parser to point at
 * untrusted network input.
 */
public final class LegacyCommitReader {

    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "maxbytes=67108864;maxdepth=16;maxrefs=1000000;maxarray=1000000;"
                    + "syncvault.Commit;java.util.TreeMap;java.lang.String;!*");

    private LegacyCommitReader() {
    }

    /**
     * Reads one commit produced by {@code ObjectOutputStream.writeObject(Commit)}.
     *
     * @param data Java serialization bytes (stream magic {@code 0xACED})
     * @return the commit, including whatever {@code self_hash} was stored
     */
    public static Commit read(byte[] data) {
        if (data == null || data.length < 2
                || (data[0] & 0xFF) != 0xAC
                || (data[1] & 0xFF) != 0xED) {
            throw new SyncVaultException("not a legacy Java-serialized commit");
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            in.setObjectInputFilter(FILTER);
            Object obj = in.readObject();
            if (!(obj instanceof Commit)) {
                throw new SyncVaultException("legacy payload is not a Commit");
            }
            return (Commit) obj;
        } catch (SyncVaultException e) {
            throw e;
        } catch (IOException | ClassNotFoundException e) {
            throw new SyncVaultException(e.getMessage());
        }
    }
}
