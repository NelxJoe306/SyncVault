package syncvault;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Embedded SQLite storage layer for SyncVault.
 * Provides a static JDBC wrapper for the current repository and a Session
 * instance for opening remote or temporary databases.
 */
public class Store {

    private static Session defaultSession;

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static volatile Runnable gcReachabilityHook = null;

    public record CommitMeta(String hash, String message, long timestamp) {}

    public record CommitParent(String child, String parent, int idx) {}

    public record ObjectStat(String hash, String type, long size, long createdAt) {
        public ObjectStat(String hash, String type, long size) {
            this(hash, type, size, 0L);
        }
    }

    public record GcResult(
            int deletedCommits,
            int deletedBlobs,
            int keptCommits,
            int keptBlobs,
            long reclaimedObjectBytes,
            long dbBytesBefore,
            long dbBytesAfter
    ) {
        public int deletedObjects() {
            return deletedCommits + deletedBlobs;
        }

        public int keptObjects() {
            return keptCommits + keptBlobs;
        }
    }

    public static synchronized Session getDefault() {
        if (defaultSession == null) {
            defaultSession = open(Repository.SYNCVAULT_DIR);
        }
        return defaultSession;
    }

    public static synchronized void setDefault(Session session) {
        defaultSession = session;
    }

    public static synchronized void closeDefault() {
        if (defaultSession != null) {
            defaultSession.close();
            defaultSession = null;
        }
    }

    public static synchronized void useDir(File dir) {
        closeDefault();
        defaultSession = open(dir);
    }

    public static Session open(File syncvaultDir) {
        File file = (syncvaultDir.getName().endsWith(".db")) ? syncvaultDir : new File(syncvaultDir, "syncvault.db");
        return new Session(file);
    }

    /* Static delegate methods operating on default repository */

    public static void createSchema() {
        getDefault().createSchema();
    }

    public static void putBlob(String hash, byte[] bytes) {
        getDefault().putBlob(hash, bytes);
    }

    public static byte[] getBlob(String hash) {
        return getDefault().getBlob(hash);
    }

    public static boolean hasObject(String hash) {
        return getDefault().hasObject(hash);
    }

    public static void putCommit(Commit c) {
        getDefault().putCommit(c);
    }

    public static Commit getCommit(String hash) {
        return getDefault().getCommit(hash);
    }

    public static List<String> allCommitIds() {
        return getDefault().allCommitIds();
    }

    public static List<CommitMeta> allCommitMetas() {
        return getDefault().allCommitMetas();
    }

    public static List<CommitParent> allCommitParents() {
        return getDefault().allCommitParents();
    }

    public static List<String> commitIdsWithMessage(String msg) {
        return getDefault().commitIdsWithMessage(msg);
    }

    public static List<String> commitIdsWithPrefix(String prefix) {
        return getDefault().commitIdsWithPrefix(prefix);
    }

    public static String getRef(String name) {
        return getDefault().getRef(name);
    }

    public static void setRef(String name, String hash) {
        getDefault().setRef(name, hash);
    }

    public static void deleteRef(String name) {
        getDefault().deleteRef(name);
    }

    public static List<String> allRefNames() {
        return getDefault().allRefNames();
    }

    public static Map<String, String> allRefs() {
        return getDefault().allRefs();
    }

    public static String getHead() {
        return getDefault().getHead();
    }

    public static void setHead(String branch) {
        getDefault().setHead(branch);
    }

    public static TreeMap<String, String> stagedAdditions() {
        return getDefault().stagedAdditions();
    }

    public static void stageAdd(String name, String hash) {
        getDefault().stageAdd(name, hash);
    }

    public static void unstageAdd(String name) {
        getDefault().unstageAdd(name);
    }

    public static List<String> stagedRemovals() {
        return getDefault().stagedRemovals();
    }

    public static void stageRemove(String name) {
        getDefault().stageRemove(name);
    }

    public static void unstageRemove(String name) {
        getDefault().unstageRemove(name);
    }

    public static void clearStageAdd() {
        getDefault().clearStageAdd();
    }

    public static void clearStageRm() {
        getDefault().clearStageRm();
    }

    public static void clearStage() {
        getDefault().clearStage();
    }

    public static String getRemote(String name) {
        return getDefault().getRemote(name);
    }

    public static void addRemote(String name, String path) {
        getDefault().addRemote(name, path);
    }

    public static void removeRemote(String name) {
        getDefault().removeRemote(name);
    }

    public static Map<String, Integer> ancestors(String head) {
        return getDefault().ancestors(head);
    }

    public static List<String> firstParentChain(String head) {
        return getDefault().firstParentChain(head);
    }

    public static String splitPoint(String currHead, String branchHead) {
        return getDefault().splitPoint(currHead, branchHead);
    }

    public static void begin() {
        getDefault().begin();
    }

    public static void commitTx() {
        getDefault().commitTx();
    }

    public static void rollback() {
        getDefault().rollback();
    }

    public static void transaction(ThrowingRunnable r) {
        getDefault().transaction(r);
    }

    public static List<ObjectStat> allObjects() {
        return getDefault().allObjects();
    }

    public static List<String> allObjectHashes() {
        return getDefault().allObjectHashes();
    }

    public static void deleteObjects(Collection<String> hashes) {
        getDefault().deleteObjects(hashes);
    }

    public static void vacuum() {
        getDefault().vacuum();
    }

    public static RepoLock acquireLock() {
        return getDefault().acquireLock();
    }

    public static long resolveDefaultGraceMs() {
        String env = System.getenv("SYNCVAULT_GC_GRACE_MS");
        if (env != null && !env.isBlank()) {
            try {
                return Long.parseLong(env.trim());
            } catch (NumberFormatException ignored) {}
        }
        String prop = System.getProperty("syncvault.gc.graceMs");
        if (prop != null && !prop.isBlank()) {
            try {
                return Long.parseLong(prop.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    public static GcResult gc() {
        return getDefault().gc();
    }

    public static GcResult gc(long graceMillis) {
        return getDefault().gc(graceMillis);
    }

    /**
     * Session representing a connection to an individual SyncVault database.
     */
    public static class Session implements AutoCloseable {

        private final File dbFile;
        private Connection conn;
        private int txDepth = 0;
        private final Set<String> inFlightObjects = Collections.synchronizedSet(new HashSet<>());
        private final java.util.concurrent.atomic.AtomicInteger inFlightPushCount = new java.util.concurrent.atomic.AtomicInteger(0);

        public Session(File dbFile) {
            this.dbFile = dbFile;
        }

        public RepoLock acquireLock() {
            return RepoLock.acquire(dbFile);
        }

        public void startInFlightPush() {
            inFlightPushCount.incrementAndGet();
        }

        public void endInFlightPush() {
            if (inFlightPushCount.decrementAndGet() <= 0) {
                inFlightPushCount.set(0);
                inFlightObjects.clear();
            }
        }

        public void registerInFlightObject(String hash) {
            if (inFlightPushCount.get() > 0 && hash != null) {
                inFlightObjects.add(hash);
            }
        }

        /**
         * True only for a hash this push has already written.
         * An empty {@code inFlightObjects} set protects nothing, including the
         * window after {@link #startInFlightPush()} and before the first put.
         * {@link RepoLock} and the grace window remain the primary safety net.
         */
        public boolean isObjectInFlight(String hash) {
            return hash != null
                    && inFlightPushCount.get() > 0
                    && inFlightObjects.contains(hash);
        }

        /**
         * Shared push critical section: repository lock, in-flight registration,
         * then one transaction. Used by Netty {@code handlePushObjects} and
         * local {@code Repository.push}. Only objects written inside {@code body}
         * are recorded as in-flight.
         */
        public void runInFlightPush(ThrowingRunnable body) {
            try (RepoLock ignored = acquireLock()) {
                startInFlightPush();
                try {
                    begin();
                    try {
                        body.run();
                        commitTx();
                    } catch (Throwable t) {
                        rollback();
                        if (t instanceof RuntimeException) {
                            throw (RuntimeException) t;
                        } else if (t instanceof Error) {
                            throw (Error) t;
                        } else {
                            throw new SyncVaultException(t.getMessage());
                        }
                    }
                } finally {
                    endInFlightPush();
                }
            }
        }

        public File getDbFile() {
            return dbFile;
        }

        public synchronized Connection getConnection() {
            try {
                if (conn == null || conn.isClosed()) {
                    File parent = dbFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("PRAGMA journal_mode = DELETE;");
                        stmt.execute("PRAGMA busy_timeout = 5000;");
                    }
                }
                return conn;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        @Override
        public synchronized void close() {
            if (conn != null) {
                try {
                    if (!conn.isClosed()) {
                        conn.close();
                    }
                } catch (SQLException ignored) {
                }
                conn = null;
            }
        }

        public void createSchema() {
            String[] statements = new String[]{
                    "CREATE TABLE IF NOT EXISTS object (hash TEXT PRIMARY KEY, type TEXT NOT NULL, data BLOB NOT NULL, created_at INTEGER);",
                    "CREATE TABLE IF NOT EXISTS commit_meta (hash TEXT PRIMARY KEY, message TEXT NOT NULL, timestamp INTEGER NOT NULL);",
                    "CREATE TABLE IF NOT EXISTS commit_parent (child TEXT NOT NULL, parent TEXT NOT NULL, idx INTEGER NOT NULL, PRIMARY KEY (child, idx));",
                    "CREATE INDEX IF NOT EXISTS idx_commit_parent_parent ON commit_parent(parent);",
                    "CREATE TABLE IF NOT EXISTS ref (name TEXT PRIMARY KEY, hash TEXT NOT NULL);",
                    "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
                    "CREATE TABLE IF NOT EXISTS stage_add (name TEXT PRIMARY KEY, hash TEXT NOT NULL);",
                    "CREATE TABLE IF NOT EXISTS stage_rm (name TEXT PRIMARY KEY);",
                    "CREATE TABLE IF NOT EXISTS remote (name TEXT PRIMARY KEY, path TEXT NOT NULL);"
            };
            try (Statement stmt = getConnection().createStatement()) {
                for (String sql : statements) {
                    stmt.execute(sql);
                }
                try {
                    stmt.execute("ALTER TABLE object ADD COLUMN created_at INTEGER;");
                } catch (SQLException ignored) {
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void putBlob(String hash, byte[] bytes) {
            putBlob(hash, bytes, System.currentTimeMillis());
        }

        public void putBlob(String hash, byte[] bytes, long createdAt) {
            registerInFlightObject(hash);
            String sql = "INSERT OR IGNORE INTO object (hash, type, data, created_at) VALUES (?, 'blob', ?, ?)";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, hash);
                pstmt.setBytes(2, bytes);
                pstmt.setLong(3, createdAt);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public byte[] getBlob(String hash) {
            String sql = "SELECT data FROM object WHERE hash = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, hash);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBytes(1);
                    }
                    return null;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public boolean hasObject(String hash) {
            String sql = "SELECT 1 FROM object WHERE hash = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, hash);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void putCommit(Commit c) {
            putCommit(c, System.currentTimeMillis());
        }

        public void putCommit(Commit c, long createdAt) {
            registerInFlightObject(c.getSelfHash());
            byte[] data = c.encode();
            try {
                try (PreparedStatement pstmt = getConnection().prepareStatement(
                        "INSERT OR REPLACE INTO object (hash, type, data, created_at) VALUES (?, 'commit', ?, ?)")) {
                    pstmt.setString(1, c.getSelfHash());
                    pstmt.setBytes(2, data);
                    pstmt.setLong(3, createdAt);
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = getConnection().prepareStatement(
                        "INSERT OR REPLACE INTO commit_meta (hash, message, timestamp) VALUES (?, ?, ?)")) {
                    pstmt.setString(1, c.getSelfHash());
                    pstmt.setString(2, c.getMessage());
                    pstmt.setLong(3, c.getTimestamp());
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = getConnection().prepareStatement(
                        "DELETE FROM commit_parent WHERE child = ?")) {
                    pstmt.setString(1, c.getSelfHash());
                    pstmt.executeUpdate();
                }
                if (c.getParent() != null) {
                    try (PreparedStatement pstmt = getConnection().prepareStatement(
                            "INSERT INTO commit_parent (child, parent, idx) VALUES (?, ?, 0)")) {
                        pstmt.setString(1, c.getSelfHash());
                        pstmt.setString(2, c.getParent());
                        pstmt.executeUpdate();
                    }
                }
                if (c.getSecondParent() != null) {
                    try (PreparedStatement pstmt = getConnection().prepareStatement(
                            "INSERT INTO commit_parent (child, parent, idx) VALUES (?, ?, 1)")) {
                        pstmt.setString(1, c.getSelfHash());
                        pstmt.setString(2, c.getSecondParent());
                        pstmt.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public Commit getCommit(String hash) {
            String sql = "SELECT data FROM object WHERE hash = ? AND type = 'commit'";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, hash);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        byte[] data = rs.getBytes(1);
                        Commit commit = Commit.decode(data);
                        commit.setSelfHash(hash);
                        return commit;
                    }
                    return null;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<String> allCommitIds() {
            String sql = "SELECT hash FROM commit_meta ORDER BY hash ASC";
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
                return list;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<CommitMeta> allCommitMetas() {
            String sql = "SELECT hash, message, timestamp FROM commit_meta ORDER BY timestamp ASC, hash ASC";
            List<CommitMeta> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new CommitMeta(rs.getString(1), rs.getString(2), rs.getLong(3)));
                }
                return list;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<CommitParent> allCommitParents() {
            String sql = "SELECT child, parent, idx FROM commit_parent ORDER BY child ASC, idx ASC";
            List<CommitParent> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new CommitParent(rs.getString(1), rs.getString(2), rs.getInt(3)));
                }
                return list;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<String> commitIdsWithMessage(String msg) {
            String sql = "SELECT hash FROM commit_meta WHERE message = ? ORDER BY hash ASC";
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, msg);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<String> commitIdsWithPrefix(String prefix) {
            String sql = "SELECT hash FROM commit_meta WHERE substr(hash, 1, length(?)) = ? ORDER BY hash ASC";
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, prefix);
                pstmt.setString(2, prefix);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public String getRef(String name) {
            String sql = "SELECT hash FROM ref WHERE name = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                    return null;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void setRef(String name, String hash) {
            String sql = "INSERT OR REPLACE INTO ref (name, hash) VALUES (?, ?)";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.setString(2, hash);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void deleteRef(String name) {
            String sql = "DELETE FROM ref WHERE name = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<String> allRefNames() {
            String sql = "SELECT name FROM ref ORDER BY name ASC";
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
                return list;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public Map<String, String> allRefs() {
            String sql = "SELECT name, hash FROM ref ORDER BY name ASC";
            Map<String, String> map = new LinkedHashMap<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString(1), rs.getString(2));
                }
                return map;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public String getHead() {
            String sql = "SELECT value FROM meta WHERE key = 'HEAD'";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return null;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void setHead(String branch) {
            String sql = "INSERT OR REPLACE INTO meta (key, value) VALUES ('HEAD', ?)";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, branch);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public TreeMap<String, String> stagedAdditions() {
            String sql = "SELECT name, hash FROM stage_add ORDER BY name ASC";
            TreeMap<String, String> map = new TreeMap<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString(1), rs.getString(2));
                }
                return map;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void stageAdd(String name, String hash) {
            String sql = "INSERT OR REPLACE INTO stage_add (name, hash) VALUES (?, ?)";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.setString(2, hash);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void unstageAdd(String name) {
            String sql = "DELETE FROM stage_add WHERE name = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<String> stagedRemovals() {
            String sql = "SELECT name FROM stage_rm ORDER BY name ASC";
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
                return list;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void stageRemove(String name) {
            String sql = "INSERT OR IGNORE INTO stage_rm (name) VALUES (?)";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void unstageRemove(String name) {
            String sql = "DELETE FROM stage_rm WHERE name = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void clearStageAdd() {
            try (Statement stmt = getConnection().createStatement()) {
                stmt.execute("DELETE FROM stage_add");
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void clearStageRm() {
            try (Statement stmt = getConnection().createStatement()) {
                stmt.execute("DELETE FROM stage_rm");
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void clearStage() {
            clearStageAdd();
            clearStageRm();
        }

        public String getRemote(String name) {
            String sql = "SELECT path FROM remote WHERE name = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                    return null;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void addRemote(String name, String path) {
            String sql = "INSERT INTO remote (name, path) VALUES (?, ?)";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.setString(2, path);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void removeRemote(String name) {
            String sql = "DELETE FROM remote WHERE name = ?";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public Map<String, Integer> ancestors(String head) {
            if (head == null) {
                return Collections.emptyMap();
            }
            String sql = "WITH RECURSIVE anc(h, d) AS ("
                    + "  SELECT ?, 0 "
                    + "  UNION "
                    + "  SELECT p.parent, anc.d + 1 FROM commit_parent p JOIN anc ON p.child = anc.h"
                    + ") "
                    + "SELECT h, MIN(d) FROM anc GROUP BY h;";
            Map<String, Integer> map = new HashMap<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, head);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getInt(2));
                    }
                    return map;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<String> firstParentChain(String head) {
            if (head == null) {
                return Collections.emptyList();
            }
            String sql = "WITH RECURSIVE chain(h, step) AS ("
                    + "  SELECT ?, 0 "
                    + "  UNION ALL "
                    + "  SELECT p.parent, chain.step + 1 FROM commit_parent p JOIN chain ON p.child = chain.h "
                    + "  WHERE p.idx = 0"
                    + ") "
                    + "SELECT h FROM chain ORDER BY step;";
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, head);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public String splitPoint(String currHead, String branchHead) {
            if (currHead == null || branchHead == null) {
                return null;
            }
            String sql = "WITH RECURSIVE "
                    + "  ca(h, d) AS (SELECT ?, 0 UNION SELECT p.parent, ca.d + 1 FROM commit_parent p JOIN ca ON p.child = ca.h), "
                    + "  ga(h)    AS (SELECT ? UNION SELECT p.parent FROM commit_parent p JOIN ga ON p.child = ga.h), "
                    + "  common(h, d) AS (SELECT h, MIN(d) FROM ca WHERE h IN (SELECT h FROM ga) GROUP BY h), "
                    + "  below(h) AS (SELECT p.parent FROM commit_parent p JOIN common c ON p.child = c.h "
                    + "               UNION SELECT p.parent FROM commit_parent p JOIN below b ON p.child = b.h) "
                    + "SELECT h FROM common WHERE h NOT IN (SELECT h FROM below) ORDER BY d, h LIMIT 1;";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
                pstmt.setString(1, currHead);
                pstmt.setString(2, branchHead);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                    return null;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public synchronized void begin() {
            try {
                if (txDepth == 0) {
                    getConnection().setAutoCommit(false);
                }
                txDepth++;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public synchronized void commitTx() {
            try {
                if (txDepth == 1) {
                    getConnection().commit();
                    getConnection().setAutoCommit(true);
                }
            } catch (SQLException e) {
                try {
                    getConnection().rollback();
                    getConnection().setAutoCommit(true);
                } catch (SQLException ignored) {
                }
                txDepth = 0;
                throw new SyncVaultException(e.getMessage());
            } finally {
                if (txDepth > 0) {
                    txDepth--;
                }
            }
        }

        public synchronized void rollback() {
            try {
                if (txDepth > 0) {
                    getConnection().rollback();
                    getConnection().setAutoCommit(true);
                    txDepth = 0;
                }
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void transaction(ThrowingRunnable r) {
            begin();
            try {
                r.run();
                commitTx();
            } catch (Throwable t) {
                rollback();
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                } else if (t instanceof Error) {
                    throw (Error) t;
                } else {
                    throw new SyncVaultException(t.getMessage());
                }
            }
        }

        public List<ObjectStat> allObjects() {
            String sql = "SELECT hash, type, length(data), COALESCE(created_at, 0) FROM object ORDER BY hash ASC";
            List<ObjectStat> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new ObjectStat(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));
                }
                return list;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public List<String> allObjectHashes() {
            String sql = "SELECT hash FROM object ORDER BY hash ASC";
            List<String> list = new ArrayList<>();
            try (PreparedStatement pstmt = getConnection().prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
                return list;
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public void deleteObjects(Collection<String> hashes) {
            if (hashes == null || hashes.isEmpty()) {
                return;
            }
            transaction(() -> {
                try (PreparedStatement delObj = getConnection().prepareStatement("DELETE FROM object WHERE hash = ?");
                     PreparedStatement delMeta = getConnection().prepareStatement("DELETE FROM commit_meta WHERE hash = ?");
                     PreparedStatement delParentChild = getConnection().prepareStatement("DELETE FROM commit_parent WHERE child = ?");
                     PreparedStatement delParentParent = getConnection().prepareStatement("DELETE FROM commit_parent WHERE parent = ?")) {
                    for (String hash : hashes) {
                        delObj.setString(1, hash);
                        delObj.addBatch();
                        delMeta.setString(1, hash);
                        delMeta.addBatch();
                        delParentChild.setString(1, hash);
                        delParentChild.addBatch();
                        delParentParent.setString(1, hash);
                        delParentParent.addBatch();
                    }
                    delObj.executeBatch();
                    delMeta.executeBatch();
                    delParentChild.executeBatch();
                    delParentParent.executeBatch();
                }
            });
        }

        public void vacuum() {
            try (Statement stmt = getConnection().createStatement()) {
                stmt.execute("VACUUM;");
            } catch (SQLException e) {
                throw new SyncVaultException(e.getMessage());
            }
        }

        public GcResult gc() {
            return gc(resolveDefaultGraceMs());
        }

        public GcResult gc(long graceMillis) {
            try (RepoLock lock = acquireLock()) {
                long dbBefore = dbFile.exists() ? dbFile.length() : 0L;
                long gcStartTime = System.currentTimeMillis();
                long graceCutoff = (graceMillis > 0) ? (gcStartTime - graceMillis) : Long.MAX_VALUE;

                final int[] counts = new int[4];
                final long[] reclaimedBytes = new long[1];

                transaction(() -> {
                    Set<String> reachableCommits = new HashSet<>();
                    Set<String> reachableBlobs = new HashSet<>();

                    for (String blobHash : stagedAdditions().values()) {
                        if (blobHash != null && !blobHash.isEmpty()) {
                            reachableBlobs.add(blobHash);
                        }
                    }

                    Queue<String> commitQueue = new ArrayDeque<>();
                    for (String refTarget : allRefs().values()) {
                        if (refTarget != null && !refTarget.isEmpty()) {
                            commitQueue.add(refTarget);
                        }
                    }
                    String head = getHead();
                    if (head != null && !head.isEmpty()) {
                        String headRef = getRef(head);
                        if (headRef != null && !headRef.isEmpty()) {
                            commitQueue.add(headRef);
                        } else {
                            commitQueue.add(head);
                        }
                    }

                    Map<String, List<String>> parentEdges = new HashMap<>();
                    for (CommitParent cp : allCommitParents()) {
                        parentEdges.computeIfAbsent(cp.child(), k -> new ArrayList<>()).add(cp.parent());
                    }

                    while (!commitQueue.isEmpty()) {
                        String commitHash = commitQueue.poll();
                        if (commitHash == null || reachableCommits.contains(commitHash)) {
                            continue;
                        }
                        Commit c = getCommit(commitHash);
                        if (c == null) {
                            continue;
                        }
                        reachableCommits.add(commitHash);

                        if (c.getNameToHash() != null) {
                            for (String blobHash : c.getNameToHash().values()) {
                                if (blobHash != null && !blobHash.isEmpty()) {
                                    reachableBlobs.add(blobHash);
                                }
                            }
                        }

                        if (c.getParent() != null && !reachableCommits.contains(c.getParent())) {
                            commitQueue.add(c.getParent());
                        }
                        if (c.getSecondParent() != null && !reachableCommits.contains(c.getSecondParent())) {
                            commitQueue.add(c.getSecondParent());
                        }
                        List<String> extraParents = parentEdges.get(commitHash);
                        if (extraParents != null) {
                            for (String p : extraParents) {
                                if (p != null && !reachableCommits.contains(p)) {
                                    commitQueue.add(p);
                                }
                            }
                        }
                    }

                    // Test hook to allow interleaving tests before querying objects
                    if (gcReachabilityHook != null) {
                        gcReachabilityHook.run();
                    }

                    List<ObjectStat> allObjs = allObjects();

                    // If an unreachable commit is in grace or in-flight, protect its referenced blobs as well
                    for (ObjectStat obj : allObjs) {
                        if ("commit".equals(obj.type()) && !reachableCommits.contains(obj.hash())) {
                            boolean inGrace = (graceMillis > 0 && obj.createdAt() > 0 && obj.createdAt() >= graceCutoff)
                                    || isObjectInFlight(obj.hash());
                            if (inGrace) {
                                Commit c = getCommit(obj.hash());
                                if (c != null && c.getNameToHash() != null) {
                                    for (String blobHash : c.getNameToHash().values()) {
                                        if (blobHash != null && !blobHash.isEmpty()) {
                                            reachableBlobs.add(blobHash);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    List<String> unreachableHashes = new ArrayList<>();
                    for (ObjectStat obj : allObjs) {
                        boolean reachable;
                        if ("commit".equals(obj.type())) {
                            reachable = reachableCommits.contains(obj.hash());
                        } else if ("blob".equals(obj.type())) {
                            reachable = reachableBlobs.contains(obj.hash());
                        } else {
                            reachable = reachableCommits.contains(obj.hash()) || reachableBlobs.contains(obj.hash());
                        }

                        boolean inGrace = false;
                        if (!reachable) {
                            if (graceMillis > 0 && obj.createdAt() > 0 && obj.createdAt() >= graceCutoff) {
                                inGrace = true;
                            } else if (isObjectInFlight(obj.hash())) {
                                inGrace = true;
                            }
                        }

                        if (reachable || inGrace) {
                            if ("commit".equals(obj.type())) {
                                counts[2]++;
                            } else {
                                counts[3]++;
                            }
                        } else {
                            unreachableHashes.add(obj.hash());
                            reclaimedBytes[0] += obj.size();
                            if ("commit".equals(obj.type())) {
                                counts[0]++;
                            } else {
                                counts[1]++;
                            }
                        }
                    }

                    deleteObjects(unreachableHashes);

                    try (Statement stmt = getConnection().createStatement()) {
                        stmt.executeUpdate("DELETE FROM commit_meta WHERE hash NOT IN (SELECT hash FROM object WHERE type = 'commit')");
                        stmt.executeUpdate("DELETE FROM commit_parent WHERE child NOT IN (SELECT hash FROM commit_meta) OR parent NOT IN (SELECT hash FROM commit_meta)");
                    }
                });

                if (txDepth == 0) {
                    vacuum();
                }
                long dbAfter = dbFile.exists() ? dbFile.length() : 0L;
                return new GcResult(counts[0], counts[1], counts[2], counts[3], reclaimedBytes[0], dbBefore, dbAfter);
            }
        }
    }
}
