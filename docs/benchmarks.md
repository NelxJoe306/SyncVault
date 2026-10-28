# SyncVault Micro-Benchmarks

This document records reproducible multi-sample latency measurements for core SyncVault operations at repository scales of **1,000** and **10,000** sequential commits, a same-host baseline comparison with `git`, and a 10-client concurrent Netty push stress test.

Machine-readable CSV output from the benchmark run is checked in at [`docs/bench-results.csv`](./bench-results.csv).

## Test Environment

- **Operating System**: Linux `6.12.94+` (`x86_64` / `amd64`, KVM guest)
- **CPU**: Intel(R) Xeon(R) Processor (`8` vCPUs)
- **Memory**: `15 GiB` RAM
- **JDK**: OpenJDK `21.0.12.1` (OpenJDK 64-Bit Server VM, bytecode target `--release 17`)
- **Reference Git**: `git version 2.47.3`
- **Storage Backend**: SQLite (`sqlite-jdbc 3.46.1.3`) in an isolated temporary repository under `/tmp`
- **Filesystem (`disk` vs `tmpfs`) Caveat**:
  - `syncvault.Benchmark` creates temporary working directories under `/tmp` (`Files.createTempDirectory(Path.of("/tmp"), ...)`).
  - On this test machine, `/tmp` is backed by an `ext4` filesystem on `/dev/vdb` (`Benchmark` auto-detects and prints the mount `fstype` from `/proc/mounts`). Because `ext4` performs block-level journal flushes, write-heavy batches at $N = 10,000$ occasionally observe a background flush spike in the tail (`p95`), while the `median` reflects steady-state throughput.
  - On many Linux hosts and container environments, `/tmp` is mounted as `tmpfs` (RAM-backed). Running under `tmpfs` eliminates block-device sync latency and yields lower commit latencies than physical disk mounts. Keep the underlying `/tmp` mount type in mind when comparing numbers across machines.

## Methodology

For each commit scale $N \in \{1000, 10000\}$, `syncvault.Benchmark` spawns an isolated worker JVM whose working directory (`Repository.CWD`) points to `/tmp/syncvault-bench-<N>-*`:

1. **Warmup and Multi-Sample Execution**:
   - Within the worker JVM, the harness runs **1 warmup iteration** followed by **5 measured sample iterations** (`--warmup=1 --samples=5`).
   - The warmup iteration warms up classloading, the SQLite native JDBC driver, Netty event loops, and the HotSpot C2 JIT compiler before any timings are recorded.
   - Before every iteration, the worker closes the active SQLite session and deletes `.syncvault` and all working-tree files so each sample measures a fresh repository from `Repository.init()`.
2. **Statistical Aggregation**:
   - For each operation and scale, the harness reports **median** ($p_{50}$) and **p95** (nearest-rank $\lceil 0.95 \times S \rceil$-th ordered sample across $S = 5$ samples) in milliseconds, both in the Markdown table on stdout and in [`docs/bench-results.csv`](./bench-results.csv).
3. **Measured Operations**:
   - `commit_batch`: Total wall-clock time for $N$ sequential `add` + `commit` operations rotating updates across 5 tracked files (`note_0.txt` .. `note_4.txt`), creating branch `early` at commit `#1`.
   - `commit_avg`: Mean latency per single `add` + `commit` within the $N$-commit batch (`commit_batch / N`).
   - `log`: First-parent history traversal from `HEAD` (commit `#N`) back to the initial commit via `Repository.log()`, with `System.out` redirected to `OutputStream.nullOutputStream()` so terminal rendering does not skew traversal latency.
   - `status`: Working-tree and staging inspection at the $N$-commit tip across the 5 tracked files via `Repository.status()` (with stdout redirected to null).
   - `checkout`: Branch switch from tip to commit `#1` (`Repository.check_branch("early")`) and back to tip (`Repository.check_branch("master")`), updating the working tree and SQLite `HEAD` / staging tables.
   - `netty_push`: Starts an embedded `NettyServer` (`serve`) on an ephemeral localhost TCP port backed by a fresh remote repository and pushes the $N$-commit history over TCP via `Repository.push("origin", "master")`.

### What We Are Not Gating in CI

- GitHub Actions CI runs `scripts/bench.sh --smoke` ($N = 50$ commits, 1 warmup + 5 samples, verifying CSV output structure) to ensure the benchmark harness and CLI flags remain functional on every pull request.
- CI **does not** gate on latency thresholds or run $10\text{k}$/$100\text{k}$ commit scales. Shared cloud CI runners experience unpredictable CPU steal and disk I/O contention from co-located workloads, making hard latency assertions noisy and prone to false failures.

## Results (1 Warmup + 5 Samples per Scale)

Source data: [`docs/bench-results.csv`](./bench-results.csv)

| Scale ($N$ commits) | Samples | `commit_batch` med / p95 (ms) | `commit_avg` med / p95 (ms) | `log` med / p95 (ms) | `status` med / p95 (ms) | `checkout` early -> tip med / p95 (ms) | `netty_push` med / p95 (ms) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| **1,000** | 5 | 2,389.05 / 2,453.05 | 2.39 / 2.45 | 15.47 / 21.53 | 0.41 / 0.48 | 7.22 / 9.30 | 199.08 / 237.82 |
| **10,000** | 5 | 19,857.15 / 98,671.44 | 1.99 / 9.87 | 155.54 / 203.40 | 1.02 / 1.55 | 6.50 / 9.20 | 1,689.93 / 1,759.43 |

Key observations:
- **`commit_batch` and `commit_avg`**: Median per-commit latency improves slightly from `2.39 ms` at $N = 1,000$ to `1.99 ms` at $N = 10,000$ as C2 JIT compilation and SQLite page caches stay hot. At $N = 10,000$, one of the 5 samples encountered an `ext4` block journal flush stall on `/dev/vdb` (`98.67 s`), which is captured in `p95` while `median` (`19.86 s`) reflects steady-state execution.
- **`log`**: Scales linearly with commit chain depth (`15.47 ms` median at $1,000$ commits to `155.54 ms` median at $10,000$ commits, or `~15.5 us` per commit).
- **`status` and `checkout`**: Depend on the number of tracked working-tree files (5 files) rather than commit history depth $N$, remaining flat (`~0.4 to 1.0 ms` median for `status`, `~6.5 to 7.2 ms` median for the two-step `early -> master` branch checkout).
- **`netty_push`**: Transfers 1,000 commits + blobs over localhost TCP with full SHA-256 content-hash and parent-chain integrity verification in `199.08 ms` median (`237.82 ms` p95), and 10,000 commits in `1,689.93 ms` median (`1,759.43 ms` p95).

## Same-Machine Baseline Comparison with `git` ($N = 1,000$)

Using the identical workload in `/tmp` on the same machine (`git 2.47.3`, 1 warmup + 5 measured samples, 5 rotating tracked files, `early` branch at commit `#1`):

| Operation ($N = 1,000$) | SyncVault med / p95 (ms) | `git 2.47.3` med / p95 (ms) | Architectural Notes |
|---|---:|---:|---|
| **`log` (1,000 commits, stdout -> null)** | 15.47 / 21.53 | 15.45 / 16.25 | Near-identical median latency (`15.47 ms` vs `15.45 ms`); SyncVault walks `commit_parent` and `object` rows in SQLite while `git` walks loose object files in `.git/objects`. |
| **`status` (5 tracked files)** | 0.41 / 0.48 | 1.63 / 1.86 | SyncVault runs in-process inside the warm worker JVM; `git status` includes `fork` + `exec` process startup overhead (~1 ms). |
| **`checkout` (`early` -> `master`)** | 7.22 / 9.30 | 3.46 / 3.95 | SyncVault writes a crash-recovery `workspace_journal` and updates SQLite `ref` / `stage` tables atomically; `git` updates `.git/index` and working-tree files directly in C. |
| **`commit_batch` (1,000 sequential `add` + `commit`)** | 2,389.05 / 2,453.05 | 4,721.17 / 4,819.29 | SyncVault executes in-process within the worker JVM using SQLite transactions; the `git` CLI loop spawns 2,000 separate OS processes (`1,000 x git add` + `1,000 x git commit`). |

## Concurrent Netty Push Stress Test (`scripts/concurrent-push-test.sh`)

To validate CAS-aware push concurrency and repository consistency under contention, [`scripts/concurrent-push-test.sh`](../scripts/concurrent-push-test.sh) runs an end-to-end multi-process stress test using the packaged JAR:

1. Initializes one `hub` repository and starts `serve` on an ephemeral localhost port with a required shared authentication token (`--token`), first verifying that an invalid token is rejected.
2. Initializes **10 independent client repositories**, fetches the common initial commit from `hub`, and creates a unique file (`client_<i>.txt`) and commit (`"client-<i> unique commit"`) in each client.
3. Spawns **10 concurrent client processes** that race to `push origin master`:
   - Exactly 1 client wins the initial fast-forward push (`ACK`).
   - Concurrent clients whose `PUSH_REQ` read the old tip before the winner committed are rejected by the server's atomic compare-and-swap check (`Push rejected: expected tip ...`).
   - Clients whose `PUSH_REQ` arrives after the tip advanced are rejected by the non-fast-forward check (`Please pull down remote changes before pushing.`).
   - On rejection, each client runs `pull origin master` (fetching the new remote history and creating a conflict-free merge commit) and retries `push` with a small randomized backoff (`20 ms` to `120 ms`) until all 10 clients succeed.
4. After all 10 client processes join, the script verifies:
   - `fsck` passes (`fsck: ok`) on the hub repository.
   - All 10 unique client commit messages exist on the hub (`find "client-<i> unique commit"`), and total commit count reflects the initial commit + 10 client commits + 45 merge commits (`56` commits in the DAG).
   - Every client pulls the final `master` tip, passes `fsck`, and verifies that all 10 `client_<i>.txt` files and all 10 commit messages are present.

Recorded run on this machine (re-verified with `CLIENTS=10`):

```text
=== Aggregate Contention Summary ===
Clients succeeded       : 10 / 10
First-attempt push wins : 1
Total push attempts     : 55
CAS tip rejections      : 1
Non-FF tip rejections   : 44
Other rejections        : 0

=== Consistency Verification ===
[OK] Hub fsck passed (fsck: ok)
[OK] Hub contains all 10 unique client commits (total commits in DAG: 56)
[OK] All 10 clients converged to hub master tip, passed fsck, and verified all 10 files and commit messages
=== Concurrent Netty Push Test PASSED ===
```

## How to Reproduce

Run the default multi-sample benchmark suite (`1,000` and `10,000` commits, 1 warmup + 5 samples, updating `docs/bench-results.csv`):

```bash
scripts/bench.sh
```

Run the fast CI smoke check (`50` commits, 1 warmup + 5 samples, verifying CSV output in ~5s):

```bash
scripts/bench.sh --smoke
```

Run custom scales or options directly via the CLI (backward-compatible with `bench 1000 5000 10000`):

```bash
mvn -B package -DskipTests
java -jar target/syncvault-0.1.0-SNAPSHOT.jar bench 1000 5000 10000
java -jar target/syncvault-0.1.0-SNAPSHOT.jar bench --warmup=1 --samples=5 --csv=docs/bench-results.csv 1000 10000
```

Run the 10-client concurrent Netty push stress test:

```bash
scripts/concurrent-push-test.sh
```
