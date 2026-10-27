package syncvault;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class WorkspaceJournal {

    public static final String JOURNAL_FILE_NAME = "workspace_journal";
    public static final String TMP_DIR_NAME = "tmp";

    /**
     * Test hook for simulating process interrupts at key points during workspace update.
     */
    public static Consumer<String> testInterruptHook = null;

    static void triggerInterrupt(String point) {
        if (testInterruptHook != null) {
            testInterruptHook.accept(point);
        }
    }

    public static File getJournalFile() {
        File syncDir = Repository.syncvaultDirForSession(Store.getDefault());
        return new File(syncDir, JOURNAL_FILE_NAME);
    }

    public static File getWorkDir() {
        return Repository.workDirForSession(Store.getDefault());
    }

    public static boolean hasPending() {
        File jf = getJournalFile();
        return jf != null && jf.exists() && jf.isFile() && jf.length() > 0;
    }

    public static class JournalRecord {
        public String operation;
        public String oldBranch;
        public String oldCommit;
        public String targetBranch;
        public String targetCommit;
        public String tempDir;
        public Map<String, String> filesToWrite = new LinkedHashMap<>();
        public List<String> filesToDelete = new ArrayList<>();
        public Map<String, String> oldFiles = new LinkedHashMap<>();

        public JournalRecord() {}

        public JournalRecord(
                String operation,
                String oldBranch,
                String oldCommit,
                String targetBranch,
                String targetCommit,
                String tempDir,
                Map<String, String> filesToWrite,
                List<String> filesToDelete,
                Map<String, String> oldFiles
        ) {
            this.operation = operation;
            this.oldBranch = oldBranch;
            this.oldCommit = oldCommit;
            this.targetBranch = targetBranch;
            this.targetCommit = targetCommit;
            this.tempDir = tempDir;
            if (filesToWrite != null) {
                this.filesToWrite.putAll(filesToWrite);
            }
            if (filesToDelete != null) {
                this.filesToDelete.addAll(filesToDelete);
            }
            if (oldFiles != null) {
                this.oldFiles.putAll(oldFiles);
            }
        }
    }

    static void writeJournal(File journalFile, JournalRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("OP:").append(r.operation != null ? r.operation : "").append("\n");
        sb.append("OLD_BRANCH:").append(r.oldBranch != null ? r.oldBranch : "").append("\n");
        sb.append("OLD_COMMIT:").append(r.oldCommit != null ? r.oldCommit : "").append("\n");
        sb.append("TARGET_BRANCH:").append(r.targetBranch != null ? r.targetBranch : "").append("\n");
        sb.append("TARGET_COMMIT:").append(r.targetCommit != null ? r.targetCommit : "").append("\n");
        sb.append("TEMP_DIR:").append(r.tempDir != null ? r.tempDir : "").append("\n");
        sb.append("---WRITES---\n");
        for (Map.Entry<String, String> e : r.filesToWrite.entrySet()) {
            sb.append(e.getValue()).append("\t").append(e.getKey()).append("\n");
        }
        sb.append("---DELETES---\n");
        for (String f : r.filesToDelete) {
            sb.append(f).append("\n");
        }
        sb.append("---OLDFILES---\n");
        for (Map.Entry<String, String> e : r.oldFiles.entrySet()) {
            sb.append(e.getValue()).append("\t").append(e.getKey()).append("\n");
        }
        sb.append("---END---\n");
        Utils.writeContents(journalFile, sb.toString());
    }

    static JournalRecord readJournal(File journalFile) {
        List<String> lines = Arrays.asList(Utils.readContentsAsString(journalFile).split("\r?\n"));
        JournalRecord r = new JournalRecord();
        String section = "HEADER";
        for (String line : lines) {
            if (line.equals("---WRITES---")) {
                section = "WRITES";
                continue;
            } else if (line.equals("---DELETES---")) {
                section = "DELETES";
                continue;
            } else if (line.equals("---OLDFILES---")) {
                section = "OLDFILES";
                continue;
            } else if (line.equals("---END---")) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }
            switch (section) {
                case "HEADER":
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon);
                        String val = line.substring(colon + 1);
                        switch (key) {
                            case "OP": r.operation = val; break;
                            case "OLD_BRANCH": r.oldBranch = val; break;
                            case "OLD_COMMIT": r.oldCommit = val; break;
                            case "TARGET_BRANCH": r.targetBranch = val; break;
                            case "TARGET_COMMIT": r.targetCommit = val; break;
                            case "TEMP_DIR": r.tempDir = val; break;
                        }
                    }
                    break;
                case "WRITES":
                    int tabW = line.indexOf('\t');
                    if (tabW > 0) {
                        String hash = line.substring(0, tabW);
                        String file = line.substring(tabW + 1);
                        r.filesToWrite.put(file, hash);
                    }
                    break;
                case "DELETES":
                    r.filesToDelete.add(line);
                    break;
                case "OLDFILES":
                    int tabO = line.indexOf('\t');
                    if (tabO > 0) {
                        String hash = line.substring(0, tabO);
                        String file = line.substring(tabO + 1);
                        r.oldFiles.put(file, hash);
                    }
                    break;
            }
        }
        return r;
    }

    /**
     * Executes an atomic workspace update with pre-written temp files and a transaction journal.
     */
    public static void updateWorkspace(
            String operation,
            String oldBranch,
            String oldCommit,
            String targetBranch,
            String targetCommit,
            Map<String, String> targetFiles,
            Map<String, String> currentFiles
    ) throws IOException {
        File workDir = getWorkDir();
        Map<String, String> filesToWrite = new LinkedHashMap<>();
        List<String> filesToDelete = new ArrayList<>();

        for (Map.Entry<String, String> e : targetFiles.entrySet()) {
            String file = e.getKey();
            String targetHash = e.getValue();
            String currHash = (currentFiles != null) ? currentFiles.get(file) : null;
            File diskFile = new File(workDir, file);
            if (!Objects.equals(targetHash, currHash) || !diskFile.exists()) {
                filesToWrite.put(file, targetHash);
            } else if ("reset".equals(operation)) {
                // In reset, ensure any locally modified tracked files are also overwritten
                byte[] diskBytes = Utils.readContents(diskFile);
                if (!Utils.hash(diskBytes).equals(targetHash)) {
                    filesToWrite.put(file, targetHash);
                }
            }
        }

        if (currentFiles != null) {
            for (String file : currentFiles.keySet()) {
                if (!targetFiles.containsKey(file)) {
                    filesToDelete.add(file);
                }
            }
        }

        if (filesToWrite.isEmpty() && filesToDelete.isEmpty()) {
            Store.begin();
            if ("checkout".equals(operation)) {
                Store.setHead(targetBranch);
            } else if ("reset".equals(operation) || "merge".equals(operation)) {
                String b = (targetBranch != null && !targetBranch.isEmpty()) ? targetBranch : Store.getHead();
                Store.setRef(b, targetCommit);
            }
            Blob.resetStage();
            Blob.resetRemoval();
            Store.commitTx();
            Repository.currHead = targetCommit;
            return;
        }

        File syncvaultDir = Repository.syncvaultDirForSession(Store.getDefault());
        File tmpBase = new File(syncvaultDir, TMP_DIR_NAME);
        tmpBase.mkdirs();
        String opId = UUID.randomUUID().toString();
        File tempDir = new File(tmpBase, "op-" + opId);
        tempDir.mkdirs();

        // 1. Prepare temp files
        for (Map.Entry<String, String> e : filesToWrite.entrySet()) {
            String relPath = e.getKey();
            String hash = e.getValue();
            byte[] data = Store.getBlob(hash);
            if (data == null) {
                throw new SyncVaultException("Missing blob object: " + hash);
            }
            File tempFile = new File(tempDir, relPath);
            Utils.writeContents(tempFile, data);
        }

        triggerInterrupt("after-temp-prepared");

        // 2. Write journal
        File journalFile = getJournalFile();
        JournalRecord record = new JournalRecord(
                operation,
                oldBranch,
                oldCommit,
                targetBranch,
                targetCommit,
                tempDir.getAbsolutePath(),
                filesToWrite,
                filesToDelete,
                currentFiles
        );
        writeJournal(journalFile, record);

        triggerInterrupt("after-journal-created");

        // 3. Apply worktree changes
        for (String relPath : filesToWrite.keySet()) {
            File src = new File(tempDir, relPath);
            File dst = new File(workDir, relPath);
            Utils.atomicMove(src, dst);
        }
        for (String relPath : filesToDelete) {
            File dst = new File(workDir, relPath);
            if (dst.exists()) {
                dst.delete();
            }
        }

        triggerInterrupt("after-worktree-applied");

        // 4. Update database
        Store.begin();
        if ("checkout".equals(operation)) {
            Store.setHead(targetBranch);
        } else if ("reset".equals(operation) || "merge".equals(operation)) {
            String b = (targetBranch != null && !targetBranch.isEmpty()) ? targetBranch : Store.getHead();
            Store.setRef(b, targetCommit);
        }
        Blob.resetStage();
        Blob.resetRemoval();
        Store.commitTx();
        Repository.currHead = targetCommit;

        triggerInterrupt("after-db-commit");

        // 5. Cleanup
        deleteJournal(journalFile);
        deleteDirectory(tempDir);
    }

    /**
     * Rolls back an interrupted workspace operation to the previous consistent commit.
     */
    public static void abort() throws IOException {
        File journalFile = getJournalFile();
        if (!journalFile.exists()) {
            return;
        }
        JournalRecord record = readJournal(journalFile);
        File workDir = getWorkDir();

        // Restore all files from previous commit
        if (record.oldFiles != null) {
            for (Map.Entry<String, String> e : record.oldFiles.entrySet()) {
                String file = e.getKey();
                String hash = e.getValue();
                byte[] data = Store.getBlob(hash);
                if (data != null) {
                    Utils.writeContents(new File(workDir, file), data);
                }
            }
        }
        // Delete any files introduced by target commit that were not in old commit
        if (record.filesToWrite != null) {
            for (String file : record.filesToWrite.keySet()) {
                if (record.oldFiles == null || !record.oldFiles.containsKey(file)) {
                    File f = new File(workDir, file);
                    if (f.exists()) {
                        f.delete();
                    }
                }
            }
        }

        // Restore DB state
        Store.begin();
        if (record.oldBranch != null && !record.oldBranch.isEmpty()) {
            Store.setHead(record.oldBranch);
            if (record.oldCommit != null && !record.oldCommit.isEmpty()) {
                Store.setRef(record.oldBranch, record.oldCommit);
            }
        }
        Blob.resetStage();
        Blob.resetRemoval();
        Store.commitTx();
        if (record.oldCommit != null && !record.oldCommit.isEmpty()) {
            Repository.currHead = record.oldCommit;
        }

        deleteJournal(journalFile);
        if (record.tempDir != null) {
            deleteDirectory(new File(record.tempDir));
        }
    }

    /**
     * Completes an interrupted workspace operation to the target commit.
     */
    public static void continueOp() throws IOException {
        File journalFile = getJournalFile();
        if (!journalFile.exists()) {
            return;
        }
        JournalRecord record = readJournal(journalFile);
        File workDir = getWorkDir();

        // Ensure all target files are placed
        if (record.filesToWrite != null) {
            for (Map.Entry<String, String> e : record.filesToWrite.entrySet()) {
                String file = e.getKey();
                String hash = e.getValue();
                File tempFile = (record.tempDir != null) ? new File(record.tempDir, file) : null;
                File dst = new File(workDir, file);
                if (tempFile != null && tempFile.exists()) {
                    Utils.atomicMove(tempFile, dst);
                } else {
                    byte[] data = Store.getBlob(hash);
                    if (data != null) {
                        Utils.writeContents(dst, data);
                    }
                }
            }
        }
        if (record.filesToDelete != null) {
            for (String file : record.filesToDelete) {
                File f = new File(workDir, file);
                if (f.exists()) {
                    f.delete();
                }
            }
        }

        // Complete DB state
        Store.begin();
        if ("checkout".equals(record.operation)) {
            if (record.targetBranch != null && !record.targetBranch.isEmpty()) {
                Store.setHead(record.targetBranch);
            }
        } else if ("reset".equals(record.operation) || "merge".equals(record.operation)) {
            String b = (record.targetBranch != null && !record.targetBranch.isEmpty())
                    ? record.targetBranch : Store.getHead();
            if (b != null && record.targetCommit != null) {
                Store.setRef(b, record.targetCommit);
            }
        }
        Blob.resetStage();
        Blob.resetRemoval();
        Store.commitTx();
        if (record.targetCommit != null && !record.targetCommit.isEmpty()) {
            Repository.currHead = record.targetCommit;
        }

        deleteJournal(journalFile);
        if (record.tempDir != null) {
            deleteDirectory(new File(record.tempDir));
        }
    }

    public static boolean checkAndRecover(boolean continueIfPending) throws IOException {
        if (!hasPending()) {
            return false;
        }
        if (continueIfPending) {
            continueOp();
        } else {
            abort();
        }
        return true;
    }

    static void deleteJournal(File journalFile) {
        if (journalFile != null && journalFile.exists()) {
            journalFile.delete();
        }
    }

    static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
