package syncvault;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reproducible micro-benchmark for SyncVault operations at configurable commit scales.
 *
 * Each scale runs in a fresh isolated temporary directory under /tmp so that
 * Repository.CWD points to the temporary repository.
 */
public class Benchmark {

    public record ScaleResult(
            int commits,
            long commitTotalMs,
            double commitAvgMs,
            long logMs,
            long checkoutEarlyToTipMs,
            long nettyPushMs
    ) {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--worker".equals(args[0])) {
            int n = Integer.parseInt(args[1]);
            boolean withNetty = args.length < 3 || Boolean.parseBoolean(args[2]);
            ScaleResult res = runWorkerInCurrentDir(n, withNetty);
            System.out.printf("RESULT\t%d\t%d\t%.3f\t%d\t%d\t%d%n",
                    res.commits(),
                    res.commitTotalMs(),
                    res.commitAvgMs(),
                    res.logMs(),
                    res.checkoutEarlyToTipMs(),
                    res.nettyPushMs());
            return;
        }

        List<Integer> scales = new ArrayList<>();
        boolean withNetty = false;
        for (String arg : args) {
            if ("--with-netty".equals(arg)) {
                withNetty = true;
            } else if ("--no-netty".equals(arg)) {
                withNetty = false;
            } else if (!arg.startsWith("-")) {
                scales.add(Integer.parseInt(arg));
            }
        }
        if (scales.isEmpty()) {
            scales.add(1000);
            scales.add(5000);
            scales.add(10000);
        }

        List<ScaleResult> results = new ArrayList<>();
        for (int n : scales) {
            ScaleResult r = spawnWorker(n, withNetty);
            results.add(r);
        }

        printMarkdownReport(results, withNetty);
    }

    private static ScaleResult spawnWorker(int n, boolean withNetty) throws Exception {
        Path tempDir = Files.createTempDirectory(Path.of("/tmp"), "syncvault-bench-" + n + "-");
        try {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String rawCp = System.getProperty("java.class.path");
            StringBuilder absCp = new StringBuilder();
            for (String entry : rawCp.split(File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    if (absCp.length() > 0) {
                        absCp.append(File.pathSeparator);
                    }
                    absCp.append(new File(entry).getAbsolutePath());
                }
            }
            ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "-cp", absCp.toString(),
                    "syncvault.Benchmark",
                    "--worker",
                    String.valueOf(n),
                    String.valueOf(withNetty)
            );
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            if (code != 0) {
                throw new RuntimeException("Benchmark worker failed (exit " + code + "):\n" + output);
            }
            for (String line : output.split("\\r?\\n")) {
                if (line.startsWith("RESULT\t")) {
                    String[] parts = line.split("\t");
                    return new ScaleResult(
                            Integer.parseInt(parts[1]),
                            Long.parseLong(parts[2]),
                            Double.parseDouble(parts[3]),
                            Long.parseLong(parts[4]),
                            Long.parseLong(parts[5]),
                            Long.parseLong(parts[6])
                    );
                }
            }
            throw new RuntimeException("No RESULT line from worker:\n" + output);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static ScaleResult runWorkerInCurrentDir(int n, boolean withNetty) throws IOException {
        Repository.init();
        String initialCommitHash = Repository.currHead;

        int fileSlots = 5;
        File[] files = new File[fileSlots];
        for (int i = 0; i < fileSlots; i++) {
            files[i] = Utils.join(Repository.CWD, "note_" + i + ".txt");
        }

        // 1. Measure N sequential commits (each writing/updating a small file, staging, and committing)
        long tCommitStart = System.nanoTime();
        for (int i = 1; i <= n; i++) {
            int slot = i % fileSlots;
            String content = "commit=" + i + "\nslot=" + slot + "\npayload=syncvault-bench-line-" + i + "\n";
            Utils.writeContents(files[slot], content);
            Repository.add(files[slot].getName());
            Commit.commitInCurrBran("bench commit #" + i);
            if (i == 1) {
                Commit.branch("early");
            }
        }
        long commitTotalMs = (System.nanoTime() - tCommitStart) / 1_000_000L;
        double commitAvgMs = (double) commitTotalMs / n;

        // 2. Measure `log` across all N commits
        PrintStream origOut = System.out;
        long logMs;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            long tLogStart = System.nanoTime();
            Repository.log();
            logMs = (System.nanoTime() - tLogStart) / 1_000_000L;
        } finally {
            System.setOut(origOut);
        }

        // 3. Measure `checkout` of early commit (#1) then back to tip (`master`)
        long tCheckoutStart = System.nanoTime();
        Repository.check_branch("early");
        Repository.check_branch("master");
        long checkoutEarlyToTipMs = (System.nanoTime() - tCheckoutStart) / 1_000_000L;

        // 4. Optional local Netty `serve` + `push` of the tip
        long nettyPushMs = -1L;
        if (withNetty) {
            Path remoteDir = Files.createTempDirectory(Path.of("/tmp"), "syncvault-bench-remote-" + n + "-");
            try {
                File remoteSyncvault = remoteDir.resolve(".syncvault").toFile();
                remoteSyncvault.mkdirs();
                try (Store.Session remoteSession = Store.open(remoteSyncvault)) {
                    remoteSession.createSchema();
                    Commit initCommit = Store.getCommit(initialCommitHash);
                    remoteSession.putCommit(initCommit);
                    remoteSession.setRef("master", initialCommitHash);
                    remoteSession.setHead("master");
                }
                try (NettyServer server = new NettyServer(remoteSyncvault, 0).start()) {
                    Repository.add_remote("origin", "127.0.0.1:" + server.getPort());
                    long tPushStart = System.nanoTime();
                    Repository.push("origin", "master");
                    nettyPushMs = (System.nanoTime() - tPushStart) / 1_000_000L;
                }
            } finally {
                deleteRecursively(remoteDir);
            }
        }

        Store.closeDefault();
        return new ScaleResult(n, commitTotalMs, commitAvgMs, logMs, checkoutEarlyToTipMs, nettyPushMs);
    }

    private static void printMarkdownReport(List<ScaleResult> results, boolean withNetty) {
        System.out.println("# SyncVault Micro-Benchmark Results");
        System.out.println();
        System.out.printf("- **OS / Arch**: `%s` (`%s`, `%s`)%n",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));
        System.out.printf("- **JDK**: `%s` (%s)%n",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"));
        System.out.println();
        if (withNetty) {
            System.out.println("| Scale (N commits) | N Sequential Commits (ms) | Avg per Commit (ms) | `log` (ms) | `checkout` early -> tip (ms) | Netty `serve` + `push` (ms) |");
            System.out.println("|---:|---:|---:|---:|---:|---:|");
            for (ScaleResult r : results) {
                System.out.printf("| %d | %d | %.2f | %d | %d | %d |%n",
                        r.commits(),
                        r.commitTotalMs(),
                        r.commitAvgMs(),
                        r.logMs(),
                        r.checkoutEarlyToTipMs(),
                        r.nettyPushMs());
            }
        } else {
            System.out.println("| Scale (N commits) | N Sequential Commits (ms) | Avg per Commit (ms) | `log` (ms) | `checkout` early -> tip (ms) |");
            System.out.println("|---:|---:|---:|---:|---:|");
            for (ScaleResult r : results) {
                System.out.printf("| %d | %d | %.2f | %d | %d |%n",
                        r.commits(),
                        r.commitTotalMs(),
                        r.commitAvgMs(),
                        r.logMs(),
                        r.checkoutEarlyToTipMs());
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
