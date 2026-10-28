package syncvault;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reproducible micro-benchmark for SyncVault operations at configurable commit scales.
 *
 * Each scale runs in a fresh isolated temporary directory under /tmp so that
 * Repository.CWD points to the temporary repository. Multi-sample runs execute
 * warmup iterations followed by measured samples within the worker JVM, cleaning
 * the working directory before each sample.
 */
public class Benchmark {

    public static final int DEFAULT_WARMUP = 1;
    public static final int DEFAULT_SAMPLES = 5;
    public static final int SMOKE_SCALE = 50;

    public record ScaleResult(
            int commits,
            double commitTotalMs,
            double commitAvgMs,
            double logMs,
            double statusMs,
            double checkoutEarlyToTipMs,
            double nettyPushMs
    ) {}

    public record OpStats(double medianMs, double p95Ms) {}

    public record ScaleSummary(
            int commits,
            int warmup,
            int samples,
            OpStats commitTotal,
            OpStats commitAvg,
            OpStats log,
            OpStats status,
            OpStats checkoutEarlyToTip,
            OpStats nettyPush
    ) {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--worker".equals(args[0])) {
            int n = Integer.parseInt(args[1]);
            boolean withNetty = args.length < 3 || Boolean.parseBoolean(args[2]);
            int warmup = args.length >= 4 ? Integer.parseInt(args[3]) : 0;
            int samples = args.length >= 5 ? Integer.parseInt(args[4]) : 1;
            Path cwd = Repository.CWD.toPath();
            int totalRuns = warmup + samples;
            for (int iter = 0; iter < totalRuns; iter++) {
                cleanDirectoryContents(cwd);
                ScaleResult res = runWorkerInCurrentDir(n, withNetty);
                if (iter >= warmup) {
                    System.out.printf(Locale.ROOT,
                            "RESULT\t%d\t%.3f\t%.4f\t%.3f\t%.3f\t%.3f\t%.3f%n",
                            res.commits(),
                            res.commitTotalMs(),
                            res.commitAvgMs(),
                            res.logMs(),
                            res.statusMs(),
                            res.checkoutEarlyToTipMs(),
                            res.nettyPushMs());
                }
            }
            return;
        }

        List<Integer> scales = new ArrayList<>();
        boolean withNetty = true;
        boolean smoke = false;
        int warmup = DEFAULT_WARMUP;
        int samples = DEFAULT_SAMPLES;
        String csvPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--with-netty".equals(arg)) {
                withNetty = true;
            } else if ("--no-netty".equals(arg)) {
                withNetty = false;
            } else if ("--smoke".equals(arg)) {
                smoke = true;
            } else if (arg.startsWith("--warmup=")) {
                warmup = Integer.parseInt(arg.substring("--warmup=".length()));
            } else if ("--warmup".equals(arg) && i + 1 < args.length) {
                warmup = Integer.parseInt(args[++i]);
            } else if (arg.startsWith("--samples=")) {
                samples = Integer.parseInt(arg.substring("--samples=".length()));
            } else if ("--samples".equals(arg) && i + 1 < args.length) {
                samples = Integer.parseInt(args[++i]);
            } else if (arg.startsWith("--csv=")) {
                csvPath = arg.substring("--csv=".length());
            } else if ("--csv".equals(arg) && i + 1 < args.length) {
                csvPath = args[++i];
            } else if (!arg.startsWith("-")) {
                scales.add(Integer.parseInt(arg));
            }
        }

        if (scales.isEmpty()) {
            if (smoke) {
                scales.add(SMOKE_SCALE);
            } else {
                scales.add(1000);
                scales.add(10000);
            }
        }

        List<ScaleSummary> summaries = new ArrayList<>();
        for (int n : scales) {
            List<ScaleResult> sampleResults = spawnWorkerSamples(n, withNetty, warmup, samples);
            summaries.add(summarize(n, warmup, sampleResults));
        }

        printMarkdownReport(summaries, withNetty);
        if (csvPath != null && !csvPath.isEmpty()) {
            writeCsvReport(summaries, withNetty, csvPath);
        }
    }

    private static ScaleResult spawnWorker(int n, boolean withNetty) throws Exception {
        List<ScaleResult> list = spawnWorkerSamples(n, withNetty, 0, 1);
        return list.get(0);
    }

    private static List<ScaleResult> spawnWorkerSamples(
            int n, boolean withNetty, int warmup, int samples) throws Exception {
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
                    String.valueOf(withNetty),
                    String.valueOf(warmup),
                    String.valueOf(samples)
            );
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            if (code != 0) {
                throw new RuntimeException("Benchmark worker failed (exit " + code + "):\n" + output);
            }
            List<ScaleResult> results = new ArrayList<>();
            for (String line : output.split("\\r?\\n")) {
                if (line.startsWith("RESULT\t")) {
                    String[] parts = line.split("\t");
                    results.add(new ScaleResult(
                            Integer.parseInt(parts[1]),
                            Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]),
                            Double.parseDouble(parts[4]),
                            Double.parseDouble(parts[5]),
                            Double.parseDouble(parts[6]),
                            Double.parseDouble(parts[7])
                    ));
                }
            }
            if (results.size() != samples) {
                throw new RuntimeException("Expected " + samples + " RESULT lines from worker, got "
                        + results.size() + ":\n" + output);
            }
            return results;
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
        double commitTotalMs = (System.nanoTime() - tCommitStart) / 1_000_000.0;
        double commitAvgMs = commitTotalMs / n;

        // 2. Measure `log` across all N commits and `status` at tip
        PrintStream origOut = System.out;
        double logMs;
        double statusMs;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            long tLogStart = System.nanoTime();
            Repository.log();
            logMs = (System.nanoTime() - tLogStart) / 1_000_000.0;

            long tStatusStart = System.nanoTime();
            Repository.status();
            statusMs = (System.nanoTime() - tStatusStart) / 1_000_000.0;
        } finally {
            System.setOut(origOut);
        }

        // 3. Measure `checkout` of early commit (#1) then back to tip (`master`)
        long tCheckoutStart = System.nanoTime();
        Repository.check_branch("early");
        Repository.check_branch("master");
        double checkoutEarlyToTipMs = (System.nanoTime() - tCheckoutStart) / 1_000_000.0;

        // 4. Optional local Netty `serve` + `push` of the tip
        double nettyPushMs = -1.0;
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
                    nettyPushMs = (System.nanoTime() - tPushStart) / 1_000_000.0;
                }
            } finally {
                deleteRecursively(remoteDir);
            }
        }

        Store.closeDefault();
        return new ScaleResult(
                n, commitTotalMs, commitAvgMs, logMs, statusMs, checkoutEarlyToTipMs, nettyPushMs);
    }

    static ScaleSummary summarize(int commits, int warmup, List<ScaleResult> results) {
        List<Double> commitTotals = new ArrayList<>();
        List<Double> commitAvgs = new ArrayList<>();
        List<Double> logs = new ArrayList<>();
        List<Double> statuses = new ArrayList<>();
        List<Double> checkouts = new ArrayList<>();
        List<Double> nettyPushes = new ArrayList<>();

        for (ScaleResult r : results) {
            commitTotals.add(r.commitTotalMs());
            commitAvgs.add(r.commitAvgMs());
            logs.add(r.logMs());
            statuses.add(r.statusMs());
            checkouts.add(r.checkoutEarlyToTipMs());
            if (r.nettyPushMs() >= 0) {
                nettyPushes.add(r.nettyPushMs());
            }
        }

        return new ScaleSummary(
                commits,
                warmup,
                results.size(),
                computeStats(commitTotals),
                computeStats(commitAvgs),
                computeStats(logs),
                computeStats(statuses),
                computeStats(checkouts),
                nettyPushes.isEmpty() ? new OpStats(-1.0, -1.0) : computeStats(nettyPushes)
        );
    }

    static OpStats computeStats(List<Double> values) {
        return new OpStats(percentile(values, 50.0), percentile(values, 95.0));
    }

    static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (p == 50.0) {
            int mid = sorted.size() / 2;
            if (sorted.size() % 2 == 1) {
                return sorted.get(mid);
            }
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        int idx = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static String detectTmpFsType() {
        Path mounts = Path.of("/proc/mounts");
        if (!Files.isReadable(mounts)) {
            return "unknown";
        }
        String bestMount = "";
        String bestFs = "unknown";
        try {
            for (String line : Files.readAllLines(mounts, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    String mountPoint = parts[1];
                    String fsType = parts[2];
                    if (("/tmp".equals(mountPoint) || "/".equals(mountPoint))
                            && mountPoint.length() >= bestMount.length()) {
                        bestMount = mountPoint;
                        bestFs = fsType;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return bestFs;
    }

    private static void printMarkdownReport(List<ScaleSummary> summaries, boolean withNetty) {
        String tmpFs = detectTmpFsType();
        int sampleCount = summaries.isEmpty() ? DEFAULT_SAMPLES : summaries.get(0).samples();
        int warmupCount = summaries.isEmpty() ? DEFAULT_WARMUP : summaries.get(0).warmup();

        System.out.println("# SyncVault Micro-Benchmark Results");
        System.out.println();
        System.out.printf("- **OS / Arch**: `%s` (`%s`, `%s`)%n",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));
        System.out.printf("- **JDK**: `%s` (%s)%n",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"));
        System.out.printf("- **vCPUs**: `%d`%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("- **Workdir**: `/tmp` (detected fstype: `%s`; note: `/tmp` is often `tmpfs` on Linux)%n",
                tmpFs);
        System.out.printf("- **Sampling**: `%d` warmup + `%d` measured samples per scale (reporting **median / p95**)%n",
                warmupCount, sampleCount);
        System.out.println();

        if (withNetty) {
            System.out.println("| Scale (N) | Samples | N Commits med / p95 (ms) | Avg Commit med / p95 (ms) | `log` med / p95 (ms) | `status` med / p95 (ms) | `checkout` med / p95 (ms) | Netty `push` med / p95 (ms) |");
            System.out.println("|---:|---:|---:|---:|---:|---:|---:|---:|");
            for (ScaleSummary s : summaries) {
                System.out.printf(Locale.ROOT,
                        "| %d | %d | %.2f / %.2f | %.2f / %.2f | %.2f / %.2f | %.2f / %.2f | %.2f / %.2f | %.2f / %.2f |%n",
                        s.commits(),
                        s.samples(),
                        s.commitTotal().medianMs(), s.commitTotal().p95Ms(),
                        s.commitAvg().medianMs(), s.commitAvg().p95Ms(),
                        s.log().medianMs(), s.log().p95Ms(),
                        s.status().medianMs(), s.status().p95Ms(),
                        s.checkoutEarlyToTip().medianMs(), s.checkoutEarlyToTip().p95Ms(),
                        s.nettyPush().medianMs(), s.nettyPush().p95Ms());
            }
        } else {
            System.out.println("| Scale (N) | Samples | N Commits med / p95 (ms) | Avg Commit med / p95 (ms) | `log` med / p95 (ms) | `status` med / p95 (ms) | `checkout` med / p95 (ms) |");
            System.out.println("|---:|---:|---:|---:|---:|---:|---:|");
            for (ScaleSummary s : summaries) {
                System.out.printf(Locale.ROOT,
                        "| %d | %d | %.2f / %.2f | %.2f / %.2f | %.2f / %.2f | %.2f / %.2f | %.2f / %.2f |%n",
                        s.commits(),
                        s.samples(),
                        s.commitTotal().medianMs(), s.commitTotal().p95Ms(),
                        s.commitAvg().medianMs(), s.commitAvg().p95Ms(),
                        s.log().medianMs(), s.log().p95Ms(),
                        s.status().medianMs(), s.status().p95Ms(),
                        s.checkoutEarlyToTip().medianMs(), s.checkoutEarlyToTip().p95Ms());
            }
        }
    }

    private static void writeCsvReport(
            List<ScaleSummary> summaries, boolean withNetty, String csvPath) throws IOException {
        String tmpFs = detectTmpFsType();
        StringBuilder sb = new StringBuilder();
        sb.append("scale,op,median_ms,p95_ms,samples,notes\n");
        for (ScaleSummary s : summaries) {
            int n = s.commits();
            int samples = s.samples();
            int warmup = s.warmup();
            String envSuffix = "warmup=" + warmup + "; workdir=/tmp (" + tmpFs + "; often tmpfs)";
            appendCsvRow(sb, n, "commit_batch", s.commitTotal(), samples,
                    "N sequential add+commit operations across 5 tracked files (" + envSuffix + ")");
            appendCsvRow(sb, n, "commit_avg", s.commitAvg(), samples,
                    "Per-commit mean latency across N sequential commits (" + envSuffix + ")");
            appendCsvRow(sb, n, "log", s.log(), samples,
                    "First-parent history traversal from HEAD to root with stdout redirected (" + envSuffix + ")");
            appendCsvRow(sb, n, "status", s.status(), samples,
                    "Workspace and staging status inspection at tip with 5 tracked files (" + envSuffix + ")");
            appendCsvRow(sb, n, "checkout", s.checkoutEarlyToTip(), samples,
                    "Branch switch early (#1) -> master (#N) updating working tree (" + envSuffix + ")");
            if (withNetty && s.nettyPush().medianMs() >= 0) {
                appendCsvRow(sb, n, "netty_push", s.nettyPush(), samples,
                        "Localhost TCP Netty serve+push of N commits to fresh remote (" + envSuffix + ")");
            }
        }
        if ("-".equals(csvPath)) {
            System.out.print(sb);
        } else {
            Path out = Path.of(csvPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        }
    }

    private static void appendCsvRow(
            StringBuilder sb, int scale, String op, OpStats stats, int samples, String notes) {
        sb.append(String.format(Locale.ROOT, "%d,%s,%.2f,%.2f,%d,%s%n",
                scale, op, stats.medianMs(), stats.p95Ms(), samples, notes));
    }

    private static void cleanDirectoryContents(Path root) throws IOException {
        Store.closeDefault();
        Repository.currHead = null;
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(root))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
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
