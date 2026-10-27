package syncvault;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Repository-level mutual exclusion lock for SyncVault.
 *
 * <p>Prevents dangerous concurrency between garbage collection (gc) and push operations.
 * SQLite write transactions alone cannot eliminate the semantic race condition where
 * GC computes its reachability snapshot, a concurrent push inserts new objects and advances refs,
 * and then GC sweeps and deletes those newly written objects because they were absent from
 * the initial snapshot.
 *
 * <p>This lock provides:
 * <ul>
 *   <li>Intra-process mutual exclusion via JVM-level {@link ReentrantLock} per canonical lock path.</li>
 *   <li>Inter-process mutual exclusion via OS-level {@link FileLock} on {@code repo.lock}.</li>
 *   <li>Safe reentrancy for nested operations within the same thread.</li>
 * </ul>
 */
public class RepoLock implements AutoCloseable {

    private static class LockState {
        final ReentrantLock jvmLock = new ReentrantLock();
        FileChannel channel;
        FileLock fileLock;
        int refCount = 0;
    }

    private static final ConcurrentHashMap<String, LockState> LOCK_STATES = new ConcurrentHashMap<>();

    private final String canonicalKey;
    private final LockState state;
    private boolean closed = false;

    private RepoLock(String canonicalKey, LockState state) {
        this.canonicalKey = canonicalKey;
        this.state = state;
    }

    public static File resolveLockFile(File syncvaultDirOrDb) {
        if (syncvaultDirOrDb == null) {
            syncvaultDirOrDb = Repository.SYNCVAULT_DIR;
        }
        File dir;
        if (syncvaultDirOrDb.isDirectory() || ".syncvault".equals(syncvaultDirOrDb.getName())) {
            dir = syncvaultDirOrDb;
        } else if (syncvaultDirOrDb.isFile() || syncvaultDirOrDb.getName().endsWith(".db")) {
            dir = syncvaultDirOrDb.getParentFile();
            if (dir == null) {
                dir = syncvaultDirOrDb.getAbsoluteFile().getParentFile();
            }
        } else {
            if (syncvaultDirOrDb.getName().endsWith(".db") || syncvaultDirOrDb.getName().contains(".")) {
                dir = syncvaultDirOrDb.getParentFile();
                if (dir == null) {
                    dir = syncvaultDirOrDb.getAbsoluteFile().getParentFile();
                }
            } else {
                dir = syncvaultDirOrDb;
            }
        }
        if (dir == null) {
            dir = new File(".");
        }
        return new File(dir, "repo.lock");
    }

    public static RepoLock acquire() {
        return acquire(Repository.SYNCVAULT_DIR);
    }

    public static RepoLock acquire(Store.Session session) {
        return acquire(session != null ? session.getDbFile() : null);
    }

    public static RepoLock acquire(File syncvaultDirOrDb) {
        File lockFile = resolveLockFile(syncvaultDirOrDb);
        String key;
        try {
            key = lockFile.getCanonicalPath();
        } catch (IOException e) {
            key = lockFile.toPath().toAbsolutePath().normalize().toString();
        }

        LockState state = LOCK_STATES.computeIfAbsent(key, k -> new LockState());
        state.jvmLock.lock();

        try {
            if (state.refCount == 0) {
                File parent = lockFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                state.channel = FileChannel.open(
                        lockFile.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE
                );
                state.fileLock = state.channel.lock();
            }
            state.refCount++;
            return new RepoLock(key, state);
        } catch (Throwable t) {
            if (state.refCount == 0) {
                if (state.fileLock != null) {
                    try {
                        state.fileLock.release();
                    } catch (IOException ignored) {}
                    state.fileLock = null;
                }
                if (state.channel != null) {
                    try {
                        state.channel.close();
                    } catch (IOException ignored) {}
                    state.channel = null;
                }
            }
            state.jvmLock.unlock();
            if (t instanceof SyncVaultException) {
                throw (SyncVaultException) t;
            }
            throw new SyncVaultException("Failed to acquire repository lock on " + lockFile + ": " + t.getMessage());
        }
    }

    public static boolean isLocked(File syncvaultDirOrDb) {
        File lockFile = resolveLockFile(syncvaultDirOrDb);
        String key;
        try {
            key = lockFile.getCanonicalPath();
        } catch (IOException e) {
            key = lockFile.toPath().toAbsolutePath().normalize().toString();
        }
        LockState state = LOCK_STATES.get(key);
        return state != null && state.jvmLock.isLocked();
    }

    public String getCanonicalKey() {
        return canonicalKey;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            state.refCount--;
            if (state.refCount == 0) {
                if (state.fileLock != null) {
                    try {
                        state.fileLock.release();
                    } catch (IOException ignored) {}
                    state.fileLock = null;
                }
                if (state.channel != null) {
                    try {
                        state.channel.close();
                    } catch (IOException ignored) {}
                    state.channel = null;
                }
            }
        } finally {
            state.jvmLock.unlock();
        }
    }
}
