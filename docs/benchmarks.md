# SyncVault Micro-Benchmarks

This document records reproducible wall-clock measurements for core SyncVault operations across repository scales of **1,000**, **5,000**, and **10,000** sequential commits.

## Test Environment

- **Operating System**: Linux `6.12.94+` (`x86_64` / `amd64`)
- **CPU**: Intel(R) Xeon(R) Processor (8 vCPUs)
- **Memory**: 15 GiB RAM
- **JDK**: OpenJDK `21.0.12.1` (OpenJDK 64-Bit Server VM, compiled with `--release 17`)
- **Storage Backend**: SQLite (`sqlite-jdbc 3.46.1.3`) in an isolated temporary repository under `/tmp`

## Measured Operations

For each scale $N \in \{1000, 5000, 10000\}$, `gitlet.Benchmark` spawns a fresh worker process inside an isolated `/tmp/syncvault-bench-<N>-*` directory and measures:

1. **$N$ Sequential Commits (`add` + `commit`)**:
   - Initializes a new repository (`init`) and creates an `early` branch at commit `#1`.
   - Performs $N$ sequential commits, rotating updates across 5 tracked small text files (`note_0.txt` .. `note_4.txt`), staging each change with `Repository.add`, and committing with `Commit.commitInCurrBran`.
2. **`log` Traversal**:
   - Executes `Repository.log()` from `HEAD` (commit $\#N$) back to the initial commit along the first-parent chain in SQLite (`Store.firstParentChain` + `Store.getCommit`), with stdout redirected to `OutputStream.nullOutputStream()` so terminal I/O does not skew repository traversal time.
3. **`checkout` (Early Commit -> Tip)**:
   - Checks out branch `early` (commit `#1`) via `Repository.check_branch("early")` and then checks out `master` (tip commit $\#N$) via `Repository.check_branch("master")`, updating the working tree and SQLite `HEAD` / staging tables.
4. **Optional Local Netty `serve` + `push`**:
   - At $N = 1000$ (`--with-netty`), starts an embedded `NettyServer` on an ephemeral localhost port backed by a second temporary repository and pushes the 1,000-commit history over TCP (`251 ms`). By default, the multi-scale script skips Netty so large single-frame object transfers remain fast and non-fragile.

## Results (ops × scale -> ms)

| Scale ($N$ commits) | $N$ Sequential Commits (ms) | Avg per Commit (ms) | `log` (ms) | `checkout` early -> tip (ms) |
|---:|---:|---:|---:|---:|
| **1,000** | 1,980 | 1.98 | 72 | 5 |
| **5,000** | 8,540 | 1.71 | 160 | 5 |
| **10,000** | 16,463 | 1.65 | 268 | 2 |

> Optional Netty TCP sync measurement (`java -jar target/syncvault-0.1.0-SNAPSHOT.jar bench --with-netty 1000`):
> - **1,000 commits** local `serve` + `push`: **251 ms** (with sequential commits: `2,202 ms`, `log`: `69 ms`, `checkout`: `2 ms`).

## How to Reproduce

Run the benchmark script from the repository root:

```bash
scripts/bench.sh
```

Or invoke the `bench` CLI command directly with custom commit scales:

```bash
mvn -B package -DskipTests
java -jar target/syncvault-0.1.0-SNAPSHOT.jar bench 1000 5000 10000
```
