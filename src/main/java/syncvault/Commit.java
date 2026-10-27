package syncvault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;

import static syncvault.Blob.resetRemoval;
import static syncvault.Blob.resetStage;
import static syncvault.Repository.*;

/** Represents a syncvault commit object.
 *  It stores commit metadata including log message, timestamp, parent pointer(s),
 *  and a mapping of tracked filenames to blob SHA-1 hashes.
 *
 *  @author JOE
 */
public class Commit implements Serializable, Dumpable {
    /**
     * SVC1 commit encoding (big-endian). The commit id is the hash of these
     * bytes. {@code self_hash} is not a field; {@code getCommit} fills it from
     * the object-table key, and {@code deserializeCommit} sets it to this hash.
     *
     * <pre>
     * magic          4 bytes  'S' 'V' 'C' '1'
     * version        uint16   1
     * timestamp      int64    epoch milliseconds
     * message        nullable string
     * parent         nullable string
     * secondParent   nullable string
     * fileCount      uint32
     * files          fileCount times, TreeMap order (String.compareTo):
     *                  name      string
     *                  blobHash  string
     * </pre>
     *
     * A nullable string is a uint8 flag (0 = null, 1 = present) followed by a
     * string. A string is a uint32 byte length plus UTF-8. No Java serialization.
     */
    private static final byte[] MAGIC = new byte[] {'S', 'V', 'C', '1'};
    private static final int VERSION = 1;

    /**
     * Kept only so {@link LegacyCommitReader} can read commits written before
     * SVC1. New ids are {@link #contentId()} and do not depend on this value.
     */
    private static final long serialVersionUID = -1296720244208386047L;
    /**
     * Instance variables of the Commit class representing metadata and file mappings.
     */

    /** The message of this Commit. */
    private String message;
    /**
     * Epoch milliseconds. A formatted date string would bake the local
     * timezone into the serialized commit, so the same commit would hash
     * differently on another machine. Log still prints a local date.
     */
    private long timestamp;
    /** The parent commit's hash of this Commit. */
    private final String parent;
    /** The second parent commit's hash of this Commit. */
    private String secondParent;
    /** The hash of this Commit. */
    private String self_hash;
    /** The map<String, String> of this Commit. */
    private TreeMap<String, String> nameToHash = new TreeMap<String, String>();

    private static String formatDate(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US);
        return sdf.format(date);
    }

    /**
     * Creates a new Commit object with a single parent.
     *
     * @param info the commit message
     * @param m    the map of file names to blob SHA-1 hashes
     * @param p    the parent commit hash
     */
    public Commit(String info, TreeMap<String, String> m, String p){
        this.message = info;
        this.nameToHash = m;
        this.parent = p;
        this.secondParent = null;
        this.timestamp = new Date().getTime();

    }

    /**
     * Creates a new Commit object with two parents (merge commit).
     *
     * @param info         the commit message
     * @param m            the map of file names to blob SHA-1 hashes
     * @param p            the first parent commit hash
     * @param secondParent the second parent commit hash
     */
    public Commit(String info, TreeMap<String, String> m, String p, String secondParent){
        this.message = info;
        this.nameToHash = m;
        this.parent = p;
        this.secondParent = secondParent;
        this.timestamp = new Date().getTime();

    }

    /**
     * Creates the initial commit for syncvault initialization.
     *
     * @return the SHA-1 hash of the initial commit
     */
    public static String first_commit() throws IOException {
        Commit first = new Commit("initial commit",new TreeMap<>(), null);
        first.timestamp = 0L;
        first.self_hash = first.contentId();
        Store.putCommit(first);
        currHead = first.self_hash;
        Store.setRef("master", currHead);
        return first.self_hash;
    }

    /**
     * commiting a new commit in *current* branch
     */
    public static String commitInCurrBran(String me) throws IOException {
        /* Parent snapshot, then staged additions, then staged removals. */
        TreeMap<String, String> snapshot = new TreeMap<>(currCommitFiles_map());
        TreeMap<String, String> staged = Store.stagedAdditions();
        if (!staged.isEmpty()) {
            snapshot.putAll(staged);
        }
        List<String> removed = Store.stagedRemovals();
        if (removed != null) {
            for (String name : removed) {
                snapshot.remove(name);
            }
        }
        if (snapshot.equals(currCommitFiles_map())) {
            System.out.println("No changes added to the commit.");
            System.exit(0);
        }

        Commit nc = new Commit(me, snapshot, currHead);
        String currBranch = Store.getHead();
        nc.self_hash = nc.contentId();
        currHead = nc.self_hash;
        Store.begin();
        Store.putCommit(nc);
        Store.setRef(currBranch, nc.self_hash);
        resetStage();
        resetRemoval();
        Store.commitTx();
        return currHead;
    }

    /**
     * create branch
     */
    public static void branch(String branch) throws IOException {
        if (Store.getRef(branch) != null) {
            System.out.println("A branch with that name already exists.");
            System.exit(0);
        }else {
            Store.setRef(branch, currHead);
        }
    }

    /**
     * Deletes the branch with the given name.
     * This only means to delete the pointer associated with the branch;
     * it does not mean to delete all commits that were created under the branch,
     * or anything like that.
     */
    public static void rm_branch(String name) {
        /* if the branch exists*/
        if (Store.getRef(name) == null) {
            System.out.println("A branch with that name does not exist.");
            System.exit(0);
        }
        /* if is the current branch */
        String curr_bran = Store.getHead();
        if (curr_bran.equals(name)) {
            System.out.println("Cannot remove the current branch.");
            System.exit(0);
        }

        Store.deleteRef(name);
    }

    /**
     * get the current Commit's map
     */
    public static TreeMap<String, String> currCommitFiles_map() {
        Commit c = Store.getCommit(currHead);
        return c.nameToHash;
    }

    /**
     * Returns the mapping of filenames to blob hashes for this commit.
     *
     * @return map of filename to blob hash
     */
    public TreeMap<String, String> getNameToHash() {
        return this.nameToHash;
    }

    /**
     * Returns the mapping of filenames to blob hashes for a commit by its hash id.
     *
     * @param id the commit SHA-1 hash
     * @return map of filename to blob hash
     */
    public static TreeMap<String, String> getCommitFiles_map(String id) {
        if (id == null) {
            return new TreeMap<>();
        }
        Commit c = Store.getCommit(id);
        return c.nameToHash;
    }

    /**
     * Returns the commit message.
     *
     * @return the commit message
     */
    public String getMessage() {
        return this.message;
    }

    /**
     * Returns the commit timestamp in epoch milliseconds.
     *
     * @return the commit timestamp
     */
    public long getTimestamp() {
        return this.timestamp;
    }

    /**
     * Returns the SHA-1 hash of this commit.
     *
     * @return the commit SHA-1 hash
     */
    public String getSelfHash() {
        return this.self_hash;
    }

    /**
     * Sets the SHA-1 hash of this commit.
     *
     * @param hash the commit SHA-1 hash
     */
    public void setSelfHash(String hash) {
        this.self_hash = hash;
    }

    /**
     * Returns the first parent commit hash.
     *
     * @return the first parent hash
     */
    public String getParent() {
        return this.parent;
    }

    /**
     * Returns the second parent commit hash (if merge commit).
     *
     * @return the second parent hash
     */
    public String getSecondParent() {
        return this.secondParent;
    }

    /**
     * @return a list of information for log
     * 1 date
     * 2 message
     * 3 self_hash
     * 4 parent
     * 5 6 if merged, then add a “Merge:” consist of the first seven digits of the first
     *          and second parents’ commit ids
     */
    public List<String> returnInfo() {
        List<String> l = new ArrayList<>();
        l.add(formatDate(new Date(this.timestamp)));
        l.add(this.message);
        l.add(this.self_hash);
        l.add(this.parent);
        if (this.secondParent != null) {
            l.add(this.parent.substring(0, 7));
            l.add(this.secondParent.substring(0, 7));
        }

        return l;
    }

    /**
     * return the commit's parent(s)
     * default the id exists
     * @param id the commit's hash
     */
    public static List<String> return_parents(String id) {
        List<String> l = new ArrayList<>();
        Commit c = Store.getCommit(id);
        l.add(c.parent);
        if (c.secondParent != null) {
            l.add(c.secondParent);
        }

        return l;
    }

    /**
     * Canonical SVC1 bytes. The same commit encodes the same way before and
     * after {@code self_hash} is assigned.
     */
    public byte[] encode() {
        TreeMap<String, String> files = new TreeMap<>();
        if (nameToHash != null) {
            files.putAll(nameToHash);
        }
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            out.write(MAGIC);
            out.writeShort(VERSION);
            out.writeLong(timestamp);
            BinaryFields.writeNullableString(out, message);
            BinaryFields.writeNullableString(out, parent);
            BinaryFields.writeNullableString(out, secondParent);
            out.writeInt(files.size());
            for (Map.Entry<String, String> entry : files.entrySet()) {
                BinaryFields.writeString(out, entry.getKey());
                BinaryFields.writeString(out, entry.getValue());
            }
            out.flush();
            return raw.toByteArray();
        } catch (IOException e) {
            throw new SyncVaultException("failed to encode commit");
        }
    }

    /**
     * Hash of {@link #encode()}. This is the commit id for objects this
     * process creates. It does not include {@code self_hash}.
     */
    public String contentId() {
        return Utils.hash(encode());
    }

    /**
     * Decodes SVC1 bytes. Does not set {@code self_hash}. Rejects Java
     * serialization ({@code 0xACED}) and any payload that is not the canonical
     * encoding of its fields.
     */
    public static Commit decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new SyncVaultException("missing commit bytes");
        }
        BinaryFields.rejectJavaSerialization(data, "commit");
        Commit commit = read(data);
        if (!Arrays.equals(data, commit.encode())) {
            throw new SyncVaultException("commit encoding is not canonical");
        }
        return commit;
    }

    private static Commit read(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new SyncVaultException("unrecognized commit encoding");
            }
            int version = BinaryFields.readUnsignedShort(in);
            if (version != VERSION) {
                throw new SyncVaultException("unsupported commit encoding version: " + version);
            }
            long timestamp = BinaryFields.readLong(in);
            String message = BinaryFields.readNullableString(in);
            String parent = BinaryFields.readNullableString(in);
            String secondParent = BinaryFields.readNullableString(in);
            int fileCount = BinaryFields.readCount(in);
            TreeMap<String, String> files = new TreeMap<>();
            for (int i = 0; i < fileCount; i++) {
                String name = BinaryFields.readString(in);
                String blobHash = BinaryFields.readString(in);
                if (files.put(name, blobHash) != null) {
                    throw new SyncVaultException("duplicate file name in commit");
                }
            }
            if (in.available() != 0) {
                throw new SyncVaultException("trailing bytes in commit encoding");
            }
            Commit commit = new Commit(message, files, parent, secondParent);
            commit.timestamp = timestamp;
            return commit;
        } catch (SyncVaultException e) {
            throw e;
        } catch (IOException e) {
            throw new SyncVaultException("invalid commit encoding");
        }
    }

    @Override
    public void dump() {
        System.out.printf("parent: %s%n map: %s%n self_hash: %s%n",
                this.parent,this.nameToHash, this.self_hash);
    }
}
