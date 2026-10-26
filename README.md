# SyncVault
> A lightweight Git-like version control system designed for multi-device asset synchronization.

## 简介

SyncVault 是一个轻量级的类似 Git 的版本控制系统（VCS）。本项目演化自作者在加利福尼亚大学伯克利分校 CS61B（2021 Spring）课程中实现的 Gitlet 项目（原始仓库：https://github.com/NelxJoe306/Gitlet ）。项目的核心目标是发展为一个支持多设备资产同步的现代版本控制与同步工具。

## 当前功能

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
  - `gc`：从所有分支引用、`HEAD` 与暂存区出发进行可达性分析，清理不可达的提交和 Blob 对象，并执行 SQLite `VACUUM` 回收磁盘空间。
- **远程仓库基础命令**：
  - `add-remote`：添加远程仓库信息与关联路径或网络地址（支持本地目录路径、`host:port`、`netty://host:port` 以及 TLS 加密的 `nettys://host:port`）。
  - `rm-remote`：移除远程仓库配置。
  - `push`：将本地分支推送到远程仓库（网络远程支持 `--token`、`--tls-ca`、`--tls-insecure`）。
  - `fetch`：从远程仓库拉取提交与分支历史（网络远程支持 `--token`、`--tls-ca`、`--tls-insecure`）。
  - `pull`：从远程拉取并合并至当前分支（网络远程支持 `--token`、`--tls-ca`、`--tls-insecure`）。
  - `serve [port]`：启动基于 Netty 的 TCP 同步服务端（默认端口 `9418`），支持共享令牌鉴权（`--token` / `GITLET_SYNC_TOKEN` / `.gitlet/sync-token`）与可选 TLS 加密（`--tls-cert` + `--tls-key` / `GITLET_TLS_CERT` + `GITLET_TLS_KEY`）。
  - `web [port]`：启动本地 Web UI 与 JSON API 服务（默认端口 `8080`），提供基于 Vis.js (`vis-network`) 的交互式提交 DAG 图、提交详情检视、文件树 / Blob 原文预览，以及冲突文件的 Use Ours / Use Theirs 一键解决（`POST /api/resolve`，仅操作本机工作区）。
- **可插拔哈希架构**：
  - 定义了 `HashFunction` 抽象接口，提供可插拔的哈希算法支持（已包含 SHA-256 与 SHA-1 实现）。
- **SQLite 存储引擎**：
  - 版本库全部元数据、提交、引用、暂存区及对象均存储于 `.gitlet/gitlet.db`（SQLite），提供 ACID 事务保证与单文件嵌入式存储，离线环境下无需运行任何外部数据库服务。

## 目录结构

```text
.
├── .github/
│   └── workflows/
│       └── ci.yml                     # GitHub Actions CI 工作流
├── docs/
│   └── design.md                      # Gitlet / SyncVault 设计与数据结构说明
├── pom.xml                            # Maven 项目构建配置
├── README.md                          # 项目说明文档
├── scripts/
│   └── run-integration-tests.sh       # 集成测试运行脚本
├── src/
│   ├── main/java/gitlet/              # SyncVault 核心源码
│   ├── main/resources/web/            # 本地 Web UI 静态资源 (HTML/CSS/JS)
│   └── test/java/gitlet/              # JUnit 5 单元测试
└── tests/
    └── integration/                   # 集成测试套件与测试用例
        ├── samples/                   # 基础功能集成测试
        ├── student_tests/             # 边界用例与扩展功能集成测试
        └── tester.py                  # 集成测试驱动脚本
```

## 构建与运行

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
java -cp target/classes gitlet.Main init
```

启动 Netty 远程同步服务端并从客户端关联同步：
```bash
# 服务端仓库目录下启动服务（默认监听 9418 端口，无配置时保持明文免密兼容）
java -jar target/syncvault-0.1.0-SNAPSHOT.jar serve 9418

# 客户端仓库添加远程地址并执行 push / fetch / pull
java -jar target/syncvault-0.1.0-SNAPSHOT.jar add-remote origin localhost:9418
java -jar target/syncvault-0.1.0-SNAPSHOT.jar push origin master
```

配置共享令牌鉴权与 TLS 加密同步（优先级：命令行参数 `--token` > 环境变量 `GITLET_SYNC_TOKEN` > 仓库内文件 `.gitlet/sync-token`；本地目录远程无需令牌）：
```bash
# 方式 1：通过仓库文件或环境变量配置共享令牌 + TLS 服务端
echo "my-shared-secret" > .gitlet/sync-token
java -jar target/syncvault-0.1.0-SNAPSHOT.jar serve 9418 \
  --tls-cert=server-cert.pem --tls-key=server-key.pem

# 也可通过环境变量指定：
# GITLET_SYNC_TOKEN=my-shared-secret GITLET_TLS_CERT=server-cert.pem GITLET_TLS_KEY=server-key.pem \
#   java -jar target/syncvault-0.1.0-SNAPSHOT.jar serve 9418

# 客户端使用 nettys:// 协议连接，指定受信任 CA 证书（或放入 .gitlet/tls-ca.pem / 设置 GITLET_TLS_CA）
echo "my-shared-secret" > .gitlet/sync-token
java -jar target/syncvault-0.1.0-SNAPSHOT.jar add-remote origin nettys://localhost:9418
java -jar target/syncvault-0.1.0-SNAPSHOT.jar push origin master --tls-ca=server-cert.pem

# 开发测试自签名证书也可显式使用 --tls-insecure（或 GITLET_TLS_INSECURE=true）跳过证书链校验：
java -jar target/syncvault-0.1.0-SNAPSHOT.jar fetch origin master --token=my-shared-secret --tls-insecure
```

启动本地只读 Web UI（默认监听 `8080` 端口）：
```bash
java -jar target/syncvault-0.1.0-SNAPSHOT.jar web 8080
```

执行垃圾回收清理不可达对象并收缩数据库体积：
```bash
java -jar target/syncvault-0.1.0-SNAPSHOT.jar gc
```

## 测试

### 单元测试
运行 JUnit 5 单元测试：
```bash
mvn test
```

### 集成测试
运行由 Python 驱动的全量集成测试套件：
```bash
scripts/run-integration-tests.sh
```

### 基准测试 (Benchmarks)
在 `/tmp` 临时仓库中复现微基准测试（测量连续提交、`log`、`checkout` 以及本地 Netty `serve + push` 耗时，详细结果见 [docs/benchmarks.md](docs/benchmarks.md)）：
```bash
scripts/bench.sh
# 或指定提交规模运行：
java -jar target/syncvault-0.1.0-SNAPSHOT.jar bench 1000 5000 10000
```

## 路线图

- [x] SQLite 存储
- [x] Netty push/pull 多设备同步
- [x] 远程同步鉴权与 TLS 加密 (`HELLO` 共享令牌 + `nettys://` TLS)
- [x] 三方合并改进 (Diff3 行级三方合并 + Web UI 冲突可视化)
- [x] 冲突解决 UX (`resolve` CLI + Web Use Ours/Theirs)
- [x] gc 垃圾回收
- [x] Web UI (Vis.js 提交图)
- [ ] 包名重命名 (gitlet -> io.github.nelxjoe306.syncvault)
