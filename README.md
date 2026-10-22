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
- **远程仓库基础命令**：
  - `add-remote`：添加远程仓库信息与关联路径。
  - `rm-remote`：移除远程仓库配置。
  - `push`：将本地分支推送到远程仓库。
  - `fetch`：从远程仓库拉取提交与分支历史。
  - `pull`：从远程拉取并合并至当前分支。
- **可插拔哈希架构**：
  - 定义了 `HashFunction` 抽象接口，提供可插拔的哈希算法支持（已包含 SHA-256 与 SHA-1 实现）。

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

## 路线图

- [ ] SQLite 存储
- [ ] Netty push/pull 多设备同步
- [ ] 三方合并改进
- [ ] gc 垃圾回收
- [ ] Web UI (Vis.js 提交图)
- [ ] 包名重命名 (gitlet -> io.github.nelxjoe306.syncvault)
