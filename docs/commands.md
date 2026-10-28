# SyncVault 命令与配置参考

> 从 [README](../README.md) 拆出的完整命令清单与运行示例。设计原理见 [design.md](design.md)。

## 命令与功能

当前版本实现了 Git 的核心基础子集（命令均在 `Main.java` 中实现）：

- **基础版本控制命令**：
  - `init`：初始化全新的版本控制仓库。
  - `add`：将工作区文件快照暂存到暂存区。
  - `commit`：提交暂存区变更并生成历史提交节点。
  - `rm`：从暂存区取消暂存，或标记删除受版本控制的文件并从工作区移除。
  - `log`：展示当前分支从 HEAD 起的提交历史记录。
  - `global-log`：展示仓库中所有提交的历史记录。
  - `find`：根据提交信息（commit message）查找提交 ID。
  - `status`：显示当前分支、暂存新增、暂存删除、修改未暂存以及未跟踪文件的状态。
  - `checkout`：检出指定提交的文件、当前提交的文件或切换分支。
  - `branch`：创建新分支。
  - `rm-branch`：删除指定分支指针。
  - `reset`：将当前分支 HEAD 回滚至指定提交并恢复工作区。
  - `merge`：将指定分支合并到当前分支，支持冲突处理与分叉点（split point）定位。
  - `resolve <file> --ours|--theirs|[--combined]`：按侧选择解决工作区冲突标记并自动 `add` 暂存；`resolve --all --ours|--theirs` 可一次处理全部冲突文件。
  - `gc [--grace-seconds=<s>]`：从所有分支引用、`HEAD` 与暂存区出发进行可达性分析，清理不可达的提交和 Blob 对象，通过仓库锁保证与 push 互斥，并执行 SQLite `VACUUM` 回收磁盘空间。
  - `fsck`：只读一致性检查。确认引用指向的提交存在，每个提交的父提交和文件映射目标存在，并且对象内容的哈希等于 `object_id`。发现问题则打印错误并以非零状态退出。没有 `--fix`。
- **远程仓库基础命令**：
  - `add-remote`：添加远程仓库信息与关联路径或网络地址（支持本地目录路径、`host:port`、`netty://host:port` 以及 TLS 加密的 `nettys://host:port`）。
  - `rm-remote`：移除远程仓库配置。
  - `push`：将本地分支推送到远程仓库（网络远程支持 `--token`、`--tls-ca`、`--tls-insecure`）。
  - `fetch`：从远程仓库拉取提交与分支历史（网络远程支持 `--token`、`--tls-ca`、`--tls-insecure`）。
  - `pull`：从远程拉取并合并至当前分支（网络远程支持 `--token`、`--tls-ca`、`--tls-insecure`）。
  - `serve [port]`：启动基于 Netty 的 TCP 同步服务端（默认端口 `9418`），支持共享令牌鉴权（`--token` / `SYNCVAULT_SYNC_TOKEN` / `.syncvault/sync-token`）与可选 TLS 加密（`--tls-cert` + `--tls-key` / `SYNCVAULT_TLS_CERT` + `SYNCVAULT_TLS_KEY`）。
  - `web [port]`：启动本地 Web（默认端口 `8080`）：提交 **DAG + 冲突面板**，以及提交详情 / 文件预览；冲突可通过 Use Ours / Use Theirs 一键解决（`POST /api/resolve`，仅本机工作区）。
- **可插拔哈希架构**：
  - 定义了 `HashFunction` 抽象接口，提供可插拔的哈希算法支持（已包含 SHA-256 与 SHA-1 实现）。
- **SQLite 存储引擎**：
  - 版本库全部元数据、提交、引用、暂存区及对象均存储于 `.syncvault/syncvault.db`（SQLite），提供 ACID 事务保证与单文件嵌入式存储，离线环境下无需运行任何外部数据库服务。
- **显式提交 / 同步编码（0.1.0-SNAPSHOT，不兼容旧库）**：
  - 提交对象与 Netty 同步帧使用 SVC1 / SVM1 字段编码，不再使用 Java 原生序列化。提交 id 是 SVC1 规范字节的哈希（默认 SHA-256）。已有仓库里的旧序列化对象不会被自动迁移，需要重新 `init`。格式与离线只读说明见 [design.md](design.md)。
- **工作区原子更新与事务日志 (Atomic Workspace Updates)**：
  - `checkout`、`reset`、`merge` 等工作区写入操作采用「临时文件落地 + 原子 rename」机制，保证单个文件在写入中断时绝不产生截断或空文件半残状态。
  - 多文件工作区切换引入事务日志（`.syncvault/workspace_journal`），中断时在启动期提供明确的可恢复状态（`syncvault abort` 回滚至一致原状态，`syncvault continue` 或 `recover --continue` 前进至目标状态），杜绝工作区与数据库状态静默半残。

## 构建与运行示例

### 环境要求
- JDK 17+
- Apache Maven 3.6+

### 构建
使用 Maven 进行编译与打包：
```bash
mvn -B package
```

### 运行
可以通过生成的独立可执行 JAR 文件运行：
```bash
java -jar target/syncvault-0.1.0-SNAPSHOT.jar init
```

也可以直接通过类路径运行：
```bash
java -cp target/classes syncvault.Main init
```

命令错误（错误参数、仓库检查失败、`fsck` 发现问题等）打印原有提示并以状态码 1 退出。成功命令由 `Main` 正常返回，状态码为 0。`serve` 对客户端协议错误返回 `ERROR` 帧，不会因此结束进程。环境变量一律使用 `SYNCVAULT_*`（例如 `SYNCVAULT_SYNC_TOKEN`、`SYNCVAULT_GC_GRACE_MS`）。

启动 Netty 远程同步服务端并从客户端关联同步：
```bash
# 服务端仓库目录下启动服务（默认监听 9418 端口，无配置时保持明文免密兼容）
java -jar target/syncvault-0.1.0-SNAPSHOT.jar serve 9418

# 客户端仓库添加远程地址并执行 push / fetch / pull
java -jar target/syncvault-0.1.0-SNAPSHOT.jar add-remote origin localhost:9418
java -jar target/syncvault-0.1.0-SNAPSHOT.jar push origin master
```

配置共享令牌鉴权与 TLS 加密同步（优先级：命令行参数 `--token` > 环境变量 `SYNCVAULT_SYNC_TOKEN` > 仓库内文件 `.syncvault/sync-token`；本地目录远程无需令牌）：
```bash
# 方式 1：通过仓库文件或环境变量配置共享令牌 + TLS 服务端
echo "my-shared-secret" > .syncvault/sync-token
java -jar target/syncvault-0.1.0-SNAPSHOT.jar serve 9418 \
  --tls-cert=server-cert.pem --tls-key=server-key.pem

# 也可通过环境变量指定：
# SYNCVAULT_SYNC_TOKEN=my-shared-secret SYNCVAULT_TLS_CERT=server-cert.pem SYNCVAULT_TLS_KEY=server-key.pem \
#   java -jar target/syncvault-0.1.0-SNAPSHOT.jar serve 9418

# 客户端使用 nettys:// 协议连接，指定受信任 CA 证书（或放入 .syncvault/tls-ca.pem / 设置 SYNCVAULT_TLS_CA）
echo "my-shared-secret" > .syncvault/sync-token
java -jar target/syncvault-0.1.0-SNAPSHOT.jar add-remote origin nettys://localhost:9418
java -jar target/syncvault-0.1.0-SNAPSHOT.jar push origin master --tls-ca=server-cert.pem

# 开发测试自签名证书也可显式使用 --tls-insecure（或 SYNCVAULT_TLS_INSECURE=true）跳过证书链校验：
java -jar target/syncvault-0.1.0-SNAPSHOT.jar fetch origin master --token=my-shared-secret --tls-insecure
```

启动本地 Web UI（提交 DAG、冲突面板；默认端口 `8080`，仅监听 127.0.0.1）：
```bash
java -jar target/syncvault-0.1.0-SNAPSHOT.jar web 8080
```

执行垃圾回收清理不可达对象并收缩数据库体积（支持可选宽限期与仓库锁并发保护）：
```bash
java -jar target/syncvault-0.1.0-SNAPSHOT.jar gc
# 或指定宽限期（例如保留近 60 秒内写入的对象）
java -jar target/syncvault-0.1.0-SNAPSHOT.jar gc --grace-seconds=60
```

只读检查引用、提交链接和对象哈希（不修改仓库；有错误时进程退出码非零）：
```bash
java -jar target/syncvault-0.1.0-SNAPSHOT.jar fsck
```

## 测试与基准入口

```bash
mvn test
scripts/run-integration-tests.sh
scripts/bench.sh                 # 多采样微基准
scripts/bench.sh --smoke         # CI 冒烟
scripts/concurrent-push-test.sh  # 10 客户端并发 Netty push
```

方法、环境与原始数据见 [benchmarks.md](benchmarks.md)、[bench-results.csv](bench-results.csv)。
