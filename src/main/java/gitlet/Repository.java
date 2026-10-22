package gitlet;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static gitlet.Blob.resetRemoval;
import static gitlet.Blob.resetStage;
import static gitlet.Blob.saveToStage;
import static gitlet.Commit.currCommitFiles_map;
import static gitlet.Utils.*;

/** Represents a gitlet repository.
 *  It coordinates gitlet operations and manages repository files and directories,
 *  including blobs, commits, staging, branches, and checkout/merge operations.
 *
 *  @author JOE
 */
public class Repository {
    /**
     * Directory paths and state variables for the Gitlet repository structure.
     */

    /**
     * The current working directory.
     */
    public static final File CWD = new File(System.getProperty("user.dir"));
    /**
     * The .gitlet directory.
     */
    public static final File GITLET_DIR = join(CWD, ".gitlet");
    /**
     * The Blobs directory.
     */
    public static final File blob_DIR = join(GITLET_DIR, "blobs");
    /**
     * The Stage directory.
     */
    public static final File stage_DIR = join(GITLET_DIR, "stage");
    /**
     * The Commits directory.
     */
    public static final File Commit_DIR = join(GITLET_DIR, "commits");
    /**
     * The Head to point current commit
     */
    public static String currHead;
    /**
     * The Branches File.
     */
    public static final File HEAD = join(GITLET_DIR, "HEAD");
    /**
     * The Reference directory.
     */
    public static final File ref = join(GITLET_DIR, "ref");
    /**
     * The removal directory
     */
    public static final File removal = join(GITLET_DIR, "rm");
    /**
     * The Remotes directory.
     */
    public static final File remotes_DIR = join(GITLET_DIR, "remotes");

    /**
     * initiate the .gitlet folder with a #initial commit
     * if the .gitlet already exists then throw an error
     */
    public static void init() throws IOException {
        if (GITLET_DIR.exists()) {
            message("A Gitlet version-control system already exists in the current directory.");
            System.exit(0);
        } else {
            GITLET_DIR.mkdir();
            blob_DIR.mkdir();
            Commit_DIR.mkdir();
            stage_DIR.mkdir();
            HEAD.createNewFile();
            ref.mkdir();
            removal.mkdir();
            remotes_DIR.mkdir();
            currHead = Commit.first_commit();
            writeContents(HEAD, "master");
        }
    }

    /**
     * Commit changes with the specified commit message.
     *
     * @param message the commit message
     */
    public static void commit(String message) {
        File fs = join(stage_DIR, "files");
        if (!fs.exists()) {
            System.out.println("No changes added to the commit.");
            System.exit(0);
        } else {

        }
    }

    /**
     * Adds a copy of the file as it currently exists to the stage area
     * if the file is same as that in current commit, delete it from stage if it is there
     */
    public static void add(String name) throws IOException {
        /**
         * 1.if the file do not exist in the workzone, print error
         * 2.if the file's previous version in the stage, replace it with current version
         * 3.if the file is identical to that in the current commit, do not stage it to be added,
         *     and remove it from the staging area if it is already there
         */
        if (name.isEmpty()) {
            System.out.println("File does not exist.");
            System.exit(0);
        }
        File f = join(CWD, name);
        if (!f.exists()) {
            System.out.println("File does not exist.");
            System.exit(0);
        } else {
            byte[] data = readContents(f);
            String hash = Utils.hash(data);
            TreeMap<String, String> m = currCommitFiles_map();
            if (m.containsKey(name) && m.get(name).equals(hash)) {
                File copyInStage = join(stage_DIR, "files");
                if (copyInStage.exists()) {
                    TreeMap<String, String> text = readObject(copyInStage, TreeMap.class);
                    text.remove(name);
                    writeObject(copyInStage, text);
                }
                File stagedRemoval = join(removal, name);
                if (stagedRemoval.exists()) {
                    stagedRemoval.delete();
                }
                return;
            }
            saveToStage(name);
        }
    }

    /**
     * Unstage the file if it is currently staged for addition.
     * If the file is tracked in the current commit,
     * stage it for removal and remove the file from the working directory
     * if the user has not already done so
     * (do not remove it unless it is tracked in the current commit).
     */
    public static void rm(String name) throws IOException {
        File CWDf = join(CWD, name);
        File fs = join(stage_DIR, "files");
        boolean staged = false;
        if (fs.exists()) {
            TreeMap<String, String> m = readObject(fs, TreeMap.class);
            if (m.containsKey(name)) {
                staged = true;
                m.remove(name);
                writeObject(fs, m);
            }
        }
        boolean tracked = currCommitFiles_map().containsKey(name);
        if (!staged && !tracked) {
            System.out.println("No reason to remove the file.");
            System.exit(0);
        }
        /* Tracked: remember the removal and delete the working copy.
           Staged only: the unstage above is the whole operation. */
        if (tracked) {
            File r = join(removal, name);
            if (!r.exists()) {
                r.createNewFile();
            }
            restrictedDelete(CWDf);
        }
    }

    /**
     * Starting at the current head commit,
     * display information about each commit backwards along the commit tree
     * until the initial commit, following the first parent commit links,
     * ignoring any second parents found in merge commits.
     * For every node in this history, the information it should display is
     * the commit id, the timestamp, and message.
     * e.g.
     * ===
     * commit 3e8bf1d794ca2e9ef8a4007275acf3751c7170ff
     * Date: Thu Nov 9 17:01:33 2017 -0800
     * Another commit message.
     * ===
     * commit e881c9575d180a215d1a636545b8fd9abfb1d2bb
     * Date: Wed Dec 31 16:00:00 1969 -0800
     * initial commit
     */
    public static void log() {
        String currentId = currHead;

        while (currentId != null) {
            File commitFile = Utils.join(Commit_DIR, currentId);
            Commit commit = Utils.readObject(commitFile, Commit.class);

            List<String> info = commit.returnInfo();

            System.out.println("===");
            System.out.println("commit " + info.get(2));

            // merge commit
            if (info.size() > 4) {
                System.out.println(
                        "Merge: " + info.get(4) + " " + info.get(5)
                );
            }

            System.out.println("Date: " + info.get(0));
            System.out.println(info.get(1));
            System.out.println();

            currentId = info.get(3);
        }
    }

    /**
     * displays information about all commits ever made.
     * The order of the commits does not matter.
     */
    public static void global_log() {
        List<String> l = plainFilenamesIn(Commit_DIR);
        File f;
        Commit c;

        String me;
        String ts; /* timestamp */
        String hash;
        String par;
        String secPar;
        for (String s : l) {
            f = join(Commit_DIR, s);
            c = readObject(f, Commit.class);
            l = c.returnInfo();
            ts = l.get(0);
            me = l.get(1);
            hash = l.get(2);
            if (l.size() > 4) {
                par = l.get(4);
                secPar = l.get(5);
                System.out.printf("===%ncommit %s%nMerge: %s %s%nDate: %s%n%s%n%n",
                        hash, par, secPar, ts, me);
            } else {
                System.out.printf("===%ncommit %s%nDate: %s%n%s%n%n",
                        hash, ts, me);
            }
        }
    }

    /**
     * Prints out the ids of all commits that have the given commit message
     * If there are multiple such commits, it prints the ids out on separate lines
     *
     */
    public static void find(String fm) {
        List<String> l = plainFilenamesIn(Commit_DIR);
        File f;
        Commit c;
        String m;
        List<String> arr_c = new ArrayList<>();

        for (String s : l) {
            f = join(Commit_DIR, s);
            c = readObject(f, Commit.class);
            List<String> la = c.returnInfo();
            m = la.get(1);
            if (Objects.equals(fm, m)) {
                arr_c.add(la.get(2));
            }
        }
        if (arr_c.isEmpty()) {
            System.out.println("Found no commit with that message.");
        } else {
            for (String s : arr_c) {
                System.out.println(s);
            }
        }

    }

    /**
     * Displays what branches currently exist,and marks the current branch with a *.
     * Also displays what files have been staged for addition or removal.
     * An example of the exact format it should follow is as follows.
     * === Branches ===
     * *master
     * other-branch
     * <p>
     * === Staged Files ===
     * wug.txt
     * wug2.txt
     * <p>
     * === Removed Files ===
     * goodbye.txt
     * <p>
     * === Modifications Not Staged For Commit ===
     * junk.txt (deleted)
     * wug3.txt (modified)
     * <p>
     * === Untracked Files ===
     * random.stuff
     *
     */
    public static void status() {
        /*  branches  */
        String currb = readContentsAsString(join(GITLET_DIR, "HEAD"));
        List<String> l = allBranchNames();
        System.out.println("=== Branches ===");
        for (String s : l) {
            if (Objects.equals(s, currb)) {
                System.out.format("*%s%n", s);
            } else {
                System.out.println(s);
            }
        }
        System.out.println();
        /*  staged files  */
        File f = join(stage_DIR, "files");
        TreeMap<String, String> stage_m = f.exists()
                ? readObject(f, TreeMap.class)
                : new TreeMap<>();
        Set<String> sf = stage_m.keySet();
        System.out.println("=== Staged Files ===");
        for (String s : sf) {
            System.out.format("%s%n", s);
        }
        System.out.println();

        /*removed files*/
        List<String> removed = plainFilenamesIn(removal);
        if (removed == null) {
            removed = new ArrayList<>();
        }
        System.out.println("=== Removed Files ===");
        for (String s : removed) {
            System.out.format("%s%n", s);
        }
        System.out.println();

        /* === Modifications Not Staged For Commit === */
        System.out.println("=== Modifications Not Staged For Commit ===");
        l = plainFilenamesIn(CWD);
        if (l == null) {
            l = new ArrayList<>();
        }
        TreeMap<String, String> m = currCommitFiles_map();
        Set<String> cf = m.keySet();
        Set<String> removedSet = new HashSet<>(removed);
        /*
        Tracked in the current commit, changed in the working directory, but not staged; or
        Staged for addition, but with different contents than in the working directory; or
        Staged for addition, but deleted in the working directory; or
        Not staged for removal, but tracked in the current commit and deleted from the working directory.
        l -> CWD files
        sf -> staged files
        cf -> current commit files
         */
        TreeSet<String> modifications = new TreeSet<>();
        for (String s : sf) {
            File cwd_f = join(CWD, s);
            if (!cwd_f.exists()) {
                modifications.add(s + " (deleted)");
            } else if (!Utils.hash(readContents(cwd_f)).equals(stage_m.get(s))) {
                modifications.add(s + " (modified)");
            }
        }
        for (String s : cf) {
            if (sf.contains(s) || removedSet.contains(s)) {
                continue;
            }
            File cwd_f = join(CWD, s);
            if (!cwd_f.exists()) {
                modifications.add(s + " (deleted)");
            } else if (!Utils.hash(readContents(cwd_f)).equals(m.get(s))) {
                modifications.add(s + " (modified)");
            }
        }
        for (String s : modifications) {
            System.out.println(s);
        }
        System.out.println();
    /** Untracked Files.
    * Files present in the working directory
    * but neither staged for addition nor tracked.
    * A file staged for removal and then recreated counts as untracked.  */
        System.out.println("=== Untracked Files ===");
        for (String s : l) {
            boolean tracked = cf.contains(s) && !removedSet.contains(s);
            if (!tracked && !sf.contains(s)) {
                System.out.format("%s%n", s);
            }
        }
        System.out.println();
    }

    /**
     * checkout -- [file name]
     * Takes the version of the file as it exists in the head commit and puts it in the working directory,
     * overwriting the version of the file that’s already there if there is one.
     * The new version of the file is not staged.
     *
     */
    public static void check_file(String name) throws IOException {
        TreeMap<String,String> curr_com_m = currCommitFiles_map();
        if (!curr_com_m.containsKey(name)) {
            System.out.println("File does not exist in that commit.");
        }else {
            File cwd_f = join(CWD, name);
            String hash = curr_com_m.get(name);
            byte[] text = readContents(join(blob_DIR, hash));
            writeContents(cwd_f, text);
            if (!cwd_f.exists()) {
                cwd_f.createNewFile();
            }
        }
    }
    /**
     *  checks the commit with the given id, and puts it in the working directory,
     *  overwriting the version of the file that’s already there if there is one.
     *  The new version of the file is not staged.
     */
    public static void check_id (String id, String name) throws IOException {
        String full_id = findCommitId(id);
        String p = currHead;
        currHead = full_id;
        check_file(name);
        currHead = p;
    }

    /**
     * find the id by using characters fewer than 64 (or equal).
     * Zero matches and more than one match are both "no such commit".
     */
    private static String findCommitId(String id) {
        List<String> commits_name = plainFilenamesIn(Commit_DIR);
        String match = null;
        if (commits_name != null) {
            for (String s : commits_name) {
                if (s.startsWith(id)) {
                    if (match != null) {
                        System.out.println("No commit with that id exists.");
                        System.exit(0);
                    }
                    match = s;
                }
            }
        }
        if (match == null) {
            System.out.println("No commit with that id exists.");
            System.exit(0);
        }
        return match;
    }

    /**
     *  The commit at the head of the given branch, and puts them in the working directory,
     *      overwriting the versions of the files that are already there if they exist.
     *  At the end of this command,
     *      the given branch will now be considered the current branch (HEAD)
     *  Files that are tracked in the current branch
     *      but are not present in the checked-out branch are deleted.
     *  The staging area is cleared, unless the checked-out branch is the current branch.
     */
    public static void check_branch(String name) throws IOException {
        /*check if the branch exists, including remote/branch under ref/ */
        File rev_bran = Utils.join(ref, name.replace('/', File.separatorChar));
        if (!rev_bran.isFile()) {
            System.out.println("No such branch exists.");
            System.exit(0);
        }
        /*check if is the current branch*/
        File head_file = Utils.join(GITLET_DIR, "HEAD");
        if (readContentsAsString(head_file).equals(name)) {
            System.out.println("No need to checkout the current branch.");
            System.exit(0);
        }


        /*check if is untracked*/
        List<String> l = plainFilenamesIn(CWD);
        File f = Utils.join(stage_DIR, "files");
        Set<String> sf;
        Map<String, String> cf = currCommitFiles_map();

        String oldHead = currHead;
        currHead = Utils.readContentsAsString(rev_bran);
        Set<String> targetFiles = currCommitFiles_map().keySet();
        currHead = oldHead;

        List<String> removedNames = plainFilenamesIn(removal);
        Set<String> removedSet = removedNames == null
                ? Collections.emptySet()
                : new HashSet<>(removedNames);

        if (l != null) {
            sf = f.exists()
                    ? readObject(f, TreeMap.class).keySet()
                    : Collections.emptySet();

            for (String s : l) {
                boolean untracked = (!cf.containsKey(s) || removedSet.contains(s))
                        && !sf.contains(s);
                if (untracked && targetFiles.contains(s)) {
                    System.out.println("There is an untracked file in the way; " +
                            "delete it, or add and commit it first.");
                    System.exit(0);
                }
            }

            for (String s : l) {
                if (cf.containsKey(s) && !targetFiles.contains(s)) {
                    Utils.join(CWD, s).delete();

                }
            }
        }

        currHead = Utils.readContentsAsString(rev_bran);
        Utils.writeContents(Utils.join(GITLET_DIR, "HEAD"), name);
        TreeMap<String, String> cm = currCommitFiles_map();
        for (String s : cm.keySet()) {
            File copy = Utils.join(CWD, s);
            byte[] content = Utils.readContents(Utils.join(blob_DIR, cm.get(s)));
            Utils.writeContents(copy, content);
            if (!copy.exists()) {
                copy.createNewFile();
            }
        }
        for (String s : cf.keySet()) {
            if (!cm.containsKey(s)) {
                Utils.join(CWD, s).delete();
            }
        }
        resetStage();
        resetRemoval();
    }

    /**
     * Reset the current branch to the given commit.
     * The working directory is updated to match the given commit,
     * and the staging area is cleared.
     */
    public static void reset(String id) {

        String full_id = findCommitId(id);

        File aim_com = join(Commit_DIR, full_id);

        if (!aim_com.exists()) {
            System.out.println("No commit with that id exists.");
            return;
        }

        Map<String, String> cf = currCommitFiles_map();

        String oldHead = currHead;
        currHead = full_id;
        Map<String, String> targetFiles = currCommitFiles_map();
        currHead = oldHead;

        List<String> files = plainFilenamesIn(CWD);

        if (files != null) {

            File stageFile = Utils.join(stage_DIR, "files");

            Set<String> stagedFiles = stageFile.exists()
                    ? readObject(stageFile, TreeMap.class).keySet()
                    : Collections.emptySet();

            List<String> removedNames = plainFilenamesIn(removal);
            Set<String> removedSet = removedNames == null
                    ? Collections.emptySet()
                    : new HashSet<>(removedNames);

            for (String fileName : files) {
                boolean untracked = (!cf.containsKey(fileName) || removedSet.contains(fileName))
                        && !stagedFiles.contains(fileName);
                if (untracked && targetFiles.containsKey(fileName)) {

                    System.out.println(
                            "There is an untracked file in the way; "
                                    + "delete it, or add and commit it first."
                    );
                    System.exit(0);
                }
            }
        }

        for (String fileName : cf.keySet()) {

            if (!targetFiles.containsKey(fileName)) {
                Utils.join(CWD, fileName).delete();
            }
        }

        for (String fileName : targetFiles.keySet()) {

            File file = Utils.join(CWD, fileName);

            byte[] content = Utils.readContents(
                    Utils.join(blob_DIR, targetFiles.get(fileName))
            );

            Utils.writeContents(file, content);
        }

        currHead = full_id;

        String currentBranch = Utils.readContentsAsString(
                Utils.join(GITLET_DIR, "HEAD")
        );

        Utils.writeContents(
                Utils.join(ref, currentBranch),
                full_id
        );

        resetStage();
        resetRemoval();
    }

    /**
     * Implement the BFS to find the latest common ancestor
     */
    public static String find_latest_ances_id(String curr_head, String bran_head) {
        Map<String, Integer> currDist = new HashMap<>();
        Queue<String> q = new ArrayDeque<>();
        q.add(curr_head);
        currDist.put(curr_head, 0);

        while (!q.isEmpty()) {
            String currId = q.poll();
            List<String> parents = Commit.return_parents(currId);
            for (String p : parents) {
                if (p != null && !currDist.containsKey(p)) {
                    currDist.put(p, currDist.get(currId) + 1);
                    q.add(p);
                }
            }
        }

        /* Every common ancestor, not the one closest to the given branch. */
        Set<String> common = new HashSet<>();
        Queue<String> qBran = new ArrayDeque<>();
        Set<String> visitedBran = new HashSet<>();
        qBran.add(bran_head);
        visitedBran.add(bran_head);

        while (!qBran.isEmpty()) {
            String currId = qBran.poll();
            if (currDist.containsKey(currId)) {
                common.add(currId);
            }
            List<String> parents = Commit.return_parents(currId);
            for (String p : parents) {
                if (p != null && !visitedBran.contains(p)) {
                    visitedBran.add(p);
                    qBran.add(p);
                }
            }
        }
        if (common.isEmpty()) {
            return null;
        }

        /* Drop a common ancestor that another common ancestor can reach. */
        Set<String> dominated = new HashSet<>();
        for (String candidate : common) {
            Queue<String> walk = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            for (String p : Commit.return_parents(candidate)) {
                if (p != null) {
                    walk.add(p);
                }
            }
            while (!walk.isEmpty()) {
                String id = walk.poll();
                if (!seen.add(id)) {
                    continue;
                }
                if (common.contains(id)) {
                    dominated.add(id);
                }
                for (String p : Commit.return_parents(id)) {
                    if (p != null && !seen.contains(p)) {
                        walk.add(p);
                    }
                }
            }
        }

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : common) {
            if (dominated.contains(candidate)) {
                continue;
            }
            int distance = currDist.get(candidate);
            if (best == null || distance < bestDistance
                    || (distance == bestDistance && candidate.compareTo(best) < 0)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * BFS find current branch's and given branch's both latest ancestor
     * @param curr_head the current branch's head
     * @param bran_head the given branch's head
     */
    public static Commit find_latest_ances(String curr_head, String bran_head) {
        String lcaId = find_latest_ances_id(curr_head, bran_head);
        if (lcaId == null) {
            return null;
        }
        return readObject(join(Commit_DIR, lcaId), Commit.class);
    }

    private static boolean wouldBeOverwrittenOrDeleted(String sHash, String cHash, String gHash) {
        // Rule 1: modified in given, unmodified in curr
        if (sHash != null && Objects.equals(sHash, cHash) && gHash != null && !Objects.equals(sHash, gHash)) {
            return true;
        }
        // Rule 5: not in split, only in given
        if (sHash == null && cHash == null && gHash != null) {
            return true;
        }
        // Rule 6: in split, unmodified in curr, deleted in given
        if (sHash != null && Objects.equals(sHash, cHash) && gHash == null) {
            return true;
        }
        // Rule 8: conflict
        if (!Objects.equals(cHash, gHash)) {
            if (sHash != null && !Objects.equals(sHash, cHash) && !Objects.equals(sHash, gHash)) {
                return true;
            }
            if (sHash == null && cHash != null && gHash != null) {
                return true;
            }
            if (sHash != null && !Objects.equals(sHash, cHash) && gHash == null) {
                return true;
            }
            if (sHash != null && cHash == null && !Objects.equals(sHash, gHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merge the two branch
     * applying many rules
     * @param branch the given branch
     */
    public static void merge(String branch) throws IOException {
        // 1. Check uncommitted changes
        File stageFiles = join(stage_DIR, "files");
        boolean hasStagedAdditions = false;
        if (stageFiles.exists()) {
            TreeMap<String, String> m = readObject(stageFiles, TreeMap.class);
            if (!m.isEmpty()) {
                hasStagedAdditions = true;
            }
        }
        List<String> removedFiles = plainFilenamesIn(removal);
        boolean hasStagedRemovals = removedFiles != null && !removedFiles.isEmpty();
        if (hasStagedAdditions || hasStagedRemovals) {
            System.out.println("You have uncommitted changes.");
            System.exit(0);
        }

        // 2. Check branch existence
        File branchFile = Utils.join(ref, branch.replace('/', File.separatorChar));
        String curr_Head = Utils.readContentsAsString(HEAD);
        if (!branchFile.exists() || branchFile.isDirectory()) {
            System.out.println("A branch with that name does not exist.");
            System.exit(0);
        }

        // 3. Check merging with self
        if (curr_Head.equals(branch)) {
            System.out.println("Cannot merge a branch with itself.");
            System.exit(0);
        }

        String current_head_hash = Utils.readContentsAsString(Utils.join(ref, curr_Head));
        String branch_head_hash = Utils.readContentsAsString(branchFile);

        // 4. Find split point
        String split_id = find_latest_ances_id(current_head_hash, branch_head_hash);
        Commit splitCommit = (split_id != null) ? readObject(join(Commit_DIR, split_id), Commit.class) : null;

        Commit currCommit = readObject(join(Commit_DIR, current_head_hash), Commit.class);
        Commit givenCommit = readObject(join(Commit_DIR, branch_head_hash), Commit.class);

        TreeMap<String, String> splitMap = (splitCommit != null) ? splitCommit.getNameToHash() : new TreeMap<>();
        TreeMap<String, String> currMap = currCommit.getNameToHash();
        TreeMap<String, String> givenMap = givenCommit.getNameToHash();

        List<String> cwdFiles = plainFilenamesIn(CWD);
        if (cwdFiles == null) {
            cwdFiles = new ArrayList<>();
        }
        Set<String> stagedAdd = (stageFiles.exists())
                ? readObject(stageFiles, TreeMap.class).keySet()
                : Collections.emptySet();

        // 5. Special exits
        if (split_id != null && split_id.equals(branch_head_hash)) {
            System.out.println("Given branch is an ancestor of the current branch.");
            return;
        }

        if (split_id != null && split_id.equals(current_head_hash)) {
            for (String file : cwdFiles) {
                if (!currMap.containsKey(file) && !stagedAdd.contains(file) && givenMap.containsKey(file)) {
                    System.out.println("There is an untracked file in the way; delete it, or add and commit it first.");
                    System.exit(0);
                }
            }
            reset(branch_head_hash);
            System.out.println("Current branch fast-forwarded.");
            return;
        }

        // 6. Check untracked files
        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(splitMap.keySet());
        allFiles.addAll(currMap.keySet());
        allFiles.addAll(givenMap.keySet());

        for (String file : cwdFiles) {
            boolean isUntracked = !currMap.containsKey(file) && !stagedAdd.contains(file);
            if (isUntracked) {
                String sHash = splitMap.get(file);
                String cHash = currMap.get(file);
                String gHash = givenMap.get(file);
                if (wouldBeOverwrittenOrDeleted(sHash, cHash, gHash)) {
                    System.out.println("There is an untracked file in the way; delete it, or add and commit it first.");
                    System.exit(0);
                }
            }
        }

        // 7. Apply 8 rules
        TreeMap<String, String> newCommitMap = new TreeMap<>(currMap);
        boolean hasConflict = false;

        for (String file : allFiles) {
            String sHash = splitMap.get(file);
            String cHash = currMap.get(file);
            String gHash = givenMap.get(file);

            if (sHash != null) {
                // In split point
                if (Objects.equals(sHash, cHash)) {
                    if (gHash != null && !Objects.equals(sHash, gHash)) {
                        // Rule 1: modified in given, not modified in curr
                        byte[] content = readContents(join(blob_DIR, gHash));
                        writeContents(join(CWD, file), content);
                        Blob.saveToStage(file);
                        newCommitMap.put(file, gHash);
                    } else if (gHash == null) {
                        // Rule 6: absent in given, unmodified in curr
                        File cwdF = join(CWD, file);
                        if (cwdF.exists()) {
                            cwdF.delete();
                        }
                        File r = join(removal, file);
                        if (!r.exists()) {
                            r.createNewFile();
                        }
                        newCommitMap.remove(file);
                    }
                    // if sHash == gHash: unmodified in both, do nothing
                } else if (Objects.equals(sHash, gHash)) {
                    // Rule 2 & 7: unmodified in given
                    // If cHash != null, stays as current (already in newCommitMap)
                    // If cHash == null, remains absent (already not in newCommitMap)
                } else {
                    // Modified in both
                    if (Objects.equals(cHash, gHash)) {
                        // Rule 3: modified same way or both deleted
                    } else {
                        // Rule 8: Conflict
                        hasConflict = true;
                        byte[] cContent = (cHash != null) ? readContents(join(blob_DIR, cHash)) : new byte[0];
                        byte[] gContent = (gHash != null) ? readContents(join(blob_DIR, gHash)) : new byte[0];
                        byte[] conflictContent = buildConflict(cContent, gContent);
                        writeContents(join(CWD, file), conflictContent);
                        Blob.saveToStage(file);
                        String conflictBlobHash = Utils.hash(conflictContent);
                        writeContents(join(blob_DIR, conflictBlobHash), conflictContent);
                        newCommitMap.put(file, conflictBlobHash);
                    }
                }
            } else {
                // Not in split point
                if (cHash != null && gHash == null) {
                    // Rule 4: present only in curr -> stays as is
                } else if (cHash == null && gHash != null) {
                    // Rule 5: present only in given -> checkout & stage
                    byte[] content = readContents(join(blob_DIR, gHash));
                    writeContents(join(CWD, file), content);
                    Blob.saveToStage(file);
                    newCommitMap.put(file, gHash);
                } else if (cHash != null && gHash != null) {
                    if (Objects.equals(cHash, gHash)) {
                        // Rule 3: both added same way
                    } else {
                        // Rule 8: Conflict
                        hasConflict = true;
                        byte[] cContent = readContents(join(blob_DIR, cHash));
                        byte[] gContent = readContents(join(blob_DIR, gHash));
                        byte[] conflictContent = buildConflict(cContent, gContent);
                        writeContents(join(CWD, file), conflictContent);
                        Blob.saveToStage(file);
                        String conflictBlobHash = Utils.hash(conflictContent);
                        writeContents(join(blob_DIR, conflictBlobHash), conflictContent);
                        newCommitMap.put(file, conflictBlobHash);
                    }
                }
            }
        }

        // 8. Check if anything changed
        File fs = join(stage_DIR, "files");
        List<String> rml = plainFilenamesIn(removal);
        boolean hasStaged = (fs.exists() && !readObject(fs, TreeMap.class).isEmpty())
                || (rml != null && !rml.isEmpty());
        if (!hasStaged && newCommitMap.equals(currMap)) {
            System.out.println("No changes added to the commit.");
            System.exit(0);
        }

        // 9. Create merge commit
        String commitMessage = "Merged " + branch + " into " + curr_Head + ".";
        Commit mergeCommit = new Commit(commitMessage, newCommitMap, current_head_hash, branch_head_hash);
        byte[] text = serialize(mergeCommit);
        mergeCommit.setSelfHash(Utils.hash(text));

        File ncf = join(Commit_DIR, mergeCommit.getSelfHash());
        writeObject(ncf, mergeCommit);

        currHead = mergeCommit.getSelfHash();
        File currBranchFile = join(ref, curr_Head);
        writeContents(currBranchFile, currHead);

        resetStage();
        resetRemoval();

        if (hasConflict) {
            System.out.println("Encountered a merge conflict.");
        }
    }

    /**
     * Saves the given match between the remote name and the base directory.
     *
     * @param name remote name
     * @param path remote directory path
     */
    public static void add_remote(String name, String path) {
        if (!remotes_DIR.exists()) {
            remotes_DIR.mkdir();
        }
        File remoteFile = join(remotes_DIR, name);
        if (remoteFile.exists()) {
            System.out.println("A remote with that name already exists.");
            System.exit(0);
        }
        String normalizedPath = path.replace('/', File.separatorChar);
        writeContents(remoteFile, normalizedPath);
    }

    /**
     * Remove information associated with the given remote name.
     *
     * @param name remote name
     */
    public static void rm_remote(String name) {
        File remoteFile = join(remotes_DIR, name);
        if (!remoteFile.exists()) {
            System.out.println("A remote with that name does not exist.");
            System.exit(0);
        }
        remoteFile.delete();
    }

    /**
     * Attempts to append the current branch's commits to the end of the given branch at the given remote.
     *
     * @param remoteName remote name
     * @param branchName remote branch name
     */
    public static void push(String remoteName, String branchName) throws IOException {
        File remoteFile = join(remotes_DIR, remoteName);
        if (!remoteFile.exists()) {
            System.out.println("Remote directory not found.");
            System.exit(0);
        }
        String remotePath = readContentsAsString(remoteFile);
        File remoteGitlet = new File(remotePath);
        if (!remoteGitlet.exists() || !remoteGitlet.isDirectory()) {
            System.out.println("Remote directory not found.");
            System.exit(0);
        }

        File remoteCommits = join(remoteGitlet, "commits");
        File remoteBlobs = join(remoteGitlet, "blobs");
        File remoteRef = join(remoteGitlet, "ref");
        File remoteBranchFile = join(remoteRef, branchName);

        if (remoteBranchFile.exists()) {
            String remoteHead = readContentsAsString(remoteBranchFile);
            boolean isAncestor = false;
            Queue<String> q = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            q.add(currHead);
            visited.add(currHead);
            while (!q.isEmpty()) {
                String id = q.poll();
                if (id.equals(remoteHead)) {
                    isAncestor = true;
                    break;
                }
                for (String p : Commit.return_parents(id)) {
                    if (p != null && !visited.contains(p)) {
                        visited.add(p);
                        q.add(p);
                    }
                }
            }
            if (!isAncestor) {
                System.out.println("Please pull down remote changes before pushing.");
                System.exit(0);
            }
        }

        // Copy commits and blobs from local to remote
        Queue<String> qSync = new ArrayDeque<>();
        Set<String> visitedSync = new HashSet<>();
        qSync.add(currHead);
        visitedSync.add(currHead);
        while (!qSync.isEmpty()) {
            String id = qSync.poll();
            File localCommitFile = join(Commit_DIR, id);
            File remoteCommitFile = join(remoteCommits, id);
            if (!remoteCommitFile.exists()) {
                Commit c = readObject(localCommitFile, Commit.class);
                writeObject(remoteCommitFile, c);
                for (String blobHash : c.getNameToHash().values()) {
                    File localBlob = join(blob_DIR, blobHash);
                    File remoteBlob = join(remoteBlobs, blobHash);
                    if (localBlob.exists() && !remoteBlob.exists()) {
                        writeContents(remoteBlob, readContents(localBlob));
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

        if (!remoteRef.exists()) {
            remoteRef.mkdirs();
        }
        writeContents(remoteBranchFile, currHead);
    }

    /**
     * Brings down commits from the remote Gitlet repository into the local Gitlet repository.
     *
     * @param remoteName remote name
     * @param branchName remote branch name
     */
    public static void fetch(String remoteName, String branchName) throws IOException {
        File remoteFile = join(remotes_DIR, remoteName);
        if (!remoteFile.exists()) {
            System.out.println("Remote directory not found.");
            System.exit(0);
        }
        String remotePath = readContentsAsString(remoteFile);
        File remoteGitlet = new File(remotePath);
        if (!remoteGitlet.exists() || !remoteGitlet.isDirectory()) {
            System.out.println("Remote directory not found.");
            System.exit(0);
        }

        File remoteCommits = join(remoteGitlet, "commits");
        File remoteBlobs = join(remoteGitlet, "blobs");
        File remoteRef = join(remoteGitlet, "ref");
        File remoteBranchFile = join(remoteRef, branchName);
        if (!remoteBranchFile.exists()) {
            System.out.println("That remote does not have that branch.");
            System.exit(0);
        }

        String remoteHead = readContentsAsString(remoteBranchFile);
        Queue<String> q = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        q.add(remoteHead);
        visited.add(remoteHead);

        while (!q.isEmpty()) {
            String id = q.poll();
            File remoteCommitFile = join(remoteCommits, id);
            File localCommitFile = join(Commit_DIR, id);
            if (remoteCommitFile.exists() && !localCommitFile.exists()) {
                Commit c = readObject(remoteCommitFile, Commit.class);
                writeObject(localCommitFile, c);
                for (String blobHash : c.getNameToHash().values()) {
                    File remoteBlob = join(remoteBlobs, blobHash);
                    File localBlob = join(blob_DIR, blobHash);
                    if (remoteBlob.exists() && !localBlob.exists()) {
                        writeContents(localBlob, readContents(remoteBlob));
                    }
                }
            }
            if (remoteCommitFile.exists()) {
                Commit c = readObject(remoteCommitFile, Commit.class);
                String p1 = c.getParent();
                if (p1 != null && !visited.contains(p1)) {
                    visited.add(p1);
                    q.add(p1);
                }
                String p2 = c.getSecondParent();
                if (p2 != null && !visited.contains(p2)) {
                    visited.add(p2);
                    q.add(p2);
                }
            }
        }

        File localRemoteDir = join(ref, remoteName);
        if (!localRemoteDir.exists()) {
            localRemoteDir.mkdirs();
        }
        File localRemoteBranchFile = join(localRemoteDir, branchName);
        writeContents(localRemoteBranchFile, remoteHead);
    }

    /**
     * Fetches branch [remote name]/[remote branch name] and then merges it into the current branch.
     *
     * @param remoteName remote name
     * @param branchName remote branch name
     */
    public static void pull(String remoteName, String branchName) throws IOException {
        fetch(remoteName, branchName);
        merge(remoteName + "/" + branchName);
    }

    /** Branch names, including remote-tracking names stored as ref/remote/branch. */
    private static List<String> allBranchNames() {
        List<String> names = new ArrayList<>();
        collectBranchNames(ref, "", names);
        Collections.sort(names);
        return names;
    }

    private static void collectBranchNames(File dir, String prefix, List<String> names) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            String name = prefix.isEmpty() ? kid.getName() : prefix + "/" + kid.getName();
            if (kid.isDirectory()) {
                collectBranchNames(kid, name, names);
            } else {
                names.add(name);
            }
        }
    }

    /** Spec conflict text. File bytes are appended raw, so a missing side is empty. */
    private static byte[] buildConflict(byte[] current, byte[] given) {
        byte[] head = "<<<<<<< HEAD\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] mid = "=======\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] end = ">>>>>>>\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + current.length + mid.length + given.length + end.length];
        int i = 0;
        System.arraycopy(head, 0, out, i, head.length);
        i += head.length;
        System.arraycopy(current, 0, out, i, current.length);
        i += current.length;
        System.arraycopy(mid, 0, out, i, mid.length);
        i += mid.length;
        System.arraycopy(given, 0, out, i, given.length);
        i += given.length;
        System.arraycopy(end, 0, out, i, end.length);
        return out;
    }
}



