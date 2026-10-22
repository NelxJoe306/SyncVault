# Gitlet 设计说明

代码仍是原来的四个类：`Main` 读命令，`Repository` 管仓库和大部分命令，`Commit` 管提交、分支指针和 `commitInCurrBran`，`Blob` 管暂存。`Utils` 是课程给的工具，没有改。下面写的是修过正确性之后、这些类实际在做什么。

## 类

`Main` 检查参数个数。除了 `init`，每条命令开始前从 `HEAD` 读出当前分支名，再从 `ref/<分支>` 读出 `Repository.currHead`。`commit` 调用 `Commit.commitInCurrBran`，`branch` / `rm-branch` 也在 `Commit` 里。其余命令进 `Repository`。

`Repository` 里的路径都是 `public static final File`：工作目录、`.gitlet`、`blobs`、`commits`、`stage`、`ref`、`rm`、`remotes`，再加上 `HEAD` 和当前提交 id `currHead`。命令都是静态方法，名字保持原来的 `check_file`、`check_id`、`check_branch`、`global_log`、`find_latest_ances_id`。

`Commit` 序列化进 `commits/<id>`。字段是 message、timestamp、parent、secondParent、self_hash、`TreeMap<String, String> nameToHash`（文件名到 blob id）。`log` 仍通过 `returnInfo()` 取日期、message、id、父节点，以及 merge 时两个父节点的前 7 位。

`Blob` 不单独序列化成对象。`saveToStage` 把文件字节的 SHA-1 写进 `stage/<hash>`，并在 `stage/files` 这张表里记下「文件名 → hash」。

## `.gitlet` 布局

```
.gitlet/HEAD                 当前分支名
.gitlet/commits/<id>         序列化的 Commit，文件名是提交 id
.gitlet/blobs/<id>           文件原文的字节
.gitlet/stage/files          暂存新增：文件名 → blob id
.gitlet/stage/<id>           这次暂存的文件字节，commit 时拷进 blobs
.gitlet/ref/<分支>           分支指向的提交 id
.gitlet/ref/<remote>/<分支>  fetch 下来的远程跟踪分支
.gitlet/rm/<文件名>          暂存删除。在不在，看文件名，不看内容
.gitlet/remotes/<名字>       远程 .gitlet 目录的路径
```

`plainFilenamesIn` 只看一层文件，看不见 `ref/R1/master`。要判断远程跟踪分支在不在，直接看 `ref` 下面按 `/` 拆开的那个文件在不在。`status` 列分支时会走进子目录。

## 提交 id 和时间

提交 id 是「`self_hash` 还没填进去」时，序列化字节的 SHA-1。时间存的是 `long` 毫秒，初始提交是 `0`。`returnInfo()` 显示的时候才用 `EEE MMM d HH:mm:ss yyyy Z` 和本机时区格式化。所以同一份初始提交在不同时区下 id 一样，`log` 上的日期字符串按本地时区变。blob id 是文件字节的 SHA-1。

## add、commit、rm

`add` 用文件字节算 SHA-1。这个值和 HEAD 里该文件的 blob id 相同，就不暂存：从 `stage/files` 里拿掉（如果有），并删掉 `rm/<文件名>`（如果有）。否则 `saveToStage`。

`commitInCurrBran` 的新文件表是：复制父提交的 `nameToHash`，放上 `stage/files`，再按 `rm/` 里的名字删掉。和父提交一样就打印 `No changes added to the commit.`，不产生提交。有变化才把暂存字节拷进 `blobs`，写新的 `Commit`，移动当前分支指针，然后 `resetStage` 和 `resetRemoval`。以前没被这次暂存碰到的文件留在父提交的表里，blob 已经在 `blobs` 里，不再从 `stage` 读。

`rm`：在暂存新增里就取消暂存。在 HEAD 里就在 `rm/` 放一个标记，并删除工作区文件（工作区里已经没有也不报错）。两个都不是才打印 `No reason to remove the file.` 两件事可以同时发生。只是暂存、还没进 HEAD 的文件，`rm` 不删工作区。

## status

节标题是 `=== Staged Files ===` 这种写法，节和节之间一个空行。当前分支前面紧贴 `*`。修改行是 `文件名 (deleted)` 或 `文件名 (modified)`，中间有空格。已暂存删除的文件只出现在 Removed，不放进 Modifications。工作区里有、没有暂存新增、并且（HEAD 不跟踪，或者已经暂存删除）的文件算 Untracked。

## checkout、reset、短 id

`check_file` / `check_id` 把 blob 字节写进工作区，不改暂存区。短 id 在 `findCommitId` 里找唯一前缀。没有，或者多于一个，都打印 `No commit with that id exists.`

`check_branch` 和 `reset` 在改任何文件之前先看工作区。未跟踪、而且目标提交里也有这个文件，就打印错误并 `System.exit(0)`。通过之后才删除「当前跟踪、目标里没有」的文件，写下目标里的文件，清空 `stage` 和 `rm`。checkout 只改 `HEAD` 里的分支名。reset 把当前分支的指针移到目标提交。

## 分裂点和 merge

`find_latest_ances_id` 仍是原来的方法。它先记下从当前头沿两个父节点能走到的每个提交，以及距离。再从给定头走一遍，收集所有共同祖先。一个共同祖先如果能从另一个共同祖先走到，就丢掉。剩下的里面，取离当前头更近的；距离相同再取 id 字典序更小的。从给定分支一碰到共同祖先就返回，会选到更靠近给定分支的那一个，criss-cross 两条路长度不一样时会选错。

merge 的八条规则还在 `Repository.merge` 里：当前和给定相同就保持；当前相对分裂点没变就采用给定方；给定方没变就保持当前；否则写成冲突字节并暂存。缺失的一边是空字节数组，标记和文件字节直接拼在一起。分裂点就是给定头时什么都不改。分裂点就是当前头时 `reset` 到给定提交，再打印 `Current branch fast-forwarded.` 有冲突时，merge 提交写完再打印 `Encountered a merge conflict.`

## 远程

`fetch` 把远程分支能到达的提交和 blob 按原始字节拷进来，并写下 `ref/<remote>/<分支>`。`push` 要求远程该分支不存在，或者它的头从本地头能沿父节点走到；否则 `Please pull down remote changes before pushing.` 拷贝同样用字节。`pull` 是 fetch 之后 merge 这个远程跟踪分支。`check_branch` 用文件是否存在判断 `R1/master`，不再只用 `plainFilenamesIn(ref)`。

## 以后可以改，这次没有改

这些是以后的结构建议，当前代码没有拆成新类：

- 把 `blobs` / `commits`、`ref`、`stage`+`rm` 收成三个存储接口，以后可以换成 SQLite，调用命令的代码不用知道路径。
- `find_latest_ances_id`、push 里判断祖先、fetch/push 里沿父节点拷贝，是三份类似的遍历。可以收成一个图工具，这次只把分裂点的选择改对了。
- `returnInfo()` 用下标取日期和 message。已经有 `getParent` 这类方法，log 以后可以直接用，避免下标。
- 错误字符串散落在各个方法里。可以集中到一处，避免少句号。
- SHA-1 直接调用 `Utils.sha1`。要换 SHA-256，加一个哈希接口比改每一处调用更稳。
- push/fetch 已经在两个目录之间搬提交和 blob 的字节。Netty 可以只换传输，不必重写 merge。
- 回收可以沿所有分支头走父节点，留下还能到达的提交，以及这些提交 `nameToHash` 里的 blob，其余删掉。

## 可插拔哈希接口与 SHA-256

为支持现代密码学安全标准与算法扩展，Gitlet 引入了可插拔的哈希策略接口 `HashFunction`：

1. **接口定义 (`HashFunction`)**：
   - `name()`：返回算法标识名称（如 `"SHA-1"`、`"SHA-256"`）。
   - `hexLength()`：返回摘要十六进制字符串的完整长度（SHA-1 为 40，SHA-256 为 64）。
   - `hash(Object... vals)` 与 `hash(List<Object> vals)`：接收 `byte[]` 与 `String` 参数，计算并输出小写十六进制哈希结果。

2. **具体实现**：
   - `Sha1Hash`：保留原系统的 SHA-1 行为（40 位十六进制）。
   - `Sha256Hash`：基于 `MessageDigest.getInstance("SHA-256")` 生成 64 位小写十六进制摘要。

3. **统一入口与调用**：
   - 在 `Utils` 中增加静态入口 `Utils.hash(Object...)` 和 `Utils.hash(List<Object>)`，默认委托至 `Sha256Hash`。
   - 保留 `Utils.sha1` 方法以保证向后兼容性。
   - 替换 `Blob.java`、`Commit.java` 与 `Repository.java` 中原本对 `Utils.sha1` 的直接调用。
   - 提交查找（`findCommitId`）使用前缀匹配（`startsWith`），天然支持 64 位 SHA-256 完整及缩写哈希查找。

4. **为什么采用 SHA-256**：
   - **抗碰撞安全性**：SHA-1 算法已存在已知的实际碰撞攻击（如 SHAttered 攻击），无法保证长期的密码学完整性。SHA-256 提供 256 位安全强度，能够有效防御碰撞与原像攻击。
   - **解耦与架构演进**：通过接口抽象使 Gitlet 的对象寻址机制与具体的哈希算法解耦，与真实 Git 社区向 SHA-256（Object Format）过渡的标准演进方向一致。

## SQLite storage

为了提升存储可靠性、实现原子性事务保障并简化存储结构，SyncVault 将基于文件系统的逐文件存储模式替换为单文件内嵌 SQLite 数据库（`.gitlet/gitlet.db`）。初始化后，`.gitlet/` 目录下仅包含唯一的 `gitlet.db` 文件，且采用默认的 `DELETE` 日志模式（非 WAL），确保无额外附加文件（如 `-wal`、`-shm`）。

### 1. 数据库表结构 (Schema)

```sql
-- 存储版本库核心对象：blob 原文与序列化后的 Commit（写入前已设置 self_hash，与旧版 writeObject 字节完全一致）
CREATE TABLE IF NOT EXISTS object (
    hash TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    data BLOB NOT NULL
);

-- 提交元数据表，便于快速检索提交信息、时间戳与根据 message/前缀查询
CREATE TABLE IF NOT EXISTS commit_meta (
    hash TEXT PRIMARY KEY,
    message TEXT NOT NULL,
    timestamp INTEGER NOT NULL
);

-- 提交父子关系表：idx=0 表示第一父提交，idx=1 表示第二父提交（初始提交无记录）
CREATE TABLE IF NOT EXISTS commit_parent (
    child TEXT NOT NULL,
    parent TEXT NOT NULL,
    idx INTEGER NOT NULL,
    PRIMARY KEY (child, idx)
);
CREATE INDEX IF NOT EXISTS idx_commit_parent_parent ON commit_parent(parent);

-- 分支引用表（包含本地分支与形如 'origin/master' 的远程跟踪分支）
CREATE TABLE IF NOT EXISTS ref (
    name TEXT PRIMARY KEY,
    hash TEXT NOT NULL
);

-- 核心元数据（如 key='HEAD' 指向当前活动分支名）
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- 暂存区新增文件映射（文件名 -> blob 哈希）
CREATE TABLE IF NOT EXISTS stage_add (
    name TEXT PRIMARY KEY,
    hash TEXT NOT NULL
);

-- 暂存区删除文件记录
CREATE TABLE IF NOT EXISTS stage_rm (
    name TEXT PRIMARY KEY
);

-- 远程仓库配置表
CREATE TABLE IF NOT EXISTS remote (
    name TEXT PRIMARY KEY,
    path TEXT NOT NULL
);
```

### 2. 递归 CTE 分叉点（split point）查询与 Criss-Cross 合并正确性

在合并两个分支时，寻找最近公共祖先（Split Point / LCA）是核心算法。SyncVault 使用单个包含递归公共表表达式（CTE）的 SQL 查询完成计算：

```sql
WITH RECURSIVE
  ca(h, d) AS (
    SELECT ?, 0
    UNION
    SELECT p.parent, ca.d + 1 FROM commit_parent p JOIN ca ON p.child = ca.h
  ),
  ga(h) AS (
    SELECT ?
    UNION
    SELECT p.parent FROM commit_parent p JOIN ga ON p.child = ga.h
  ),
  common(h, d) AS (
    SELECT h, MIN(d) FROM ca WHERE h IN (SELECT h FROM ga) GROUP BY h
  ),
  below(h) AS (
    SELECT p.parent FROM commit_parent p JOIN common c ON p.child = c.h
    UNION
    SELECT p.parent FROM commit_parent p JOIN below b ON p.child = b.h
  )
SELECT h FROM common WHERE h NOT IN (SELECT h FROM below) ORDER BY d, h LIMIT 1;
```

**为什么在 Criss-Cross 下依然正确**：
- `ca(h, d)` 从当前分支头遍历所有父节点并记录到达各祖先的最短距离 `d`。
- `ga(h)` 从给定分支头遍历其所有祖先节点。
- `common(h, d)` 计算交集，即所有公共祖先及离当前头的最短距离。
- `below(h)` 递归收集所有能被某个公共祖先所到达的祖先（即被“支配”的更旧祖先）。
- `common WHERE h NOT IN (SELECT h FROM below)` 关键性地排除了任何能被其他公共祖先到达的祖先节点。在 criss-cross 合并场景中，两个分支分别交叉合并了对方之前的节点，可能存在多个不可相互到达的候选公共祖先。通过排除 `below` 节点，留下的都是极小非支配候选节点；再通过 `ORDER BY d, h LIMIT 1` 选择离当前头距离最近的候选节点，并在距离相等时由哈希字典序平局断开，保证了结果的确定性与正确性。

### 3. 第一父节点链（First-parent log CTE）

`log` 命令需沿当前活动分支的第一父提交（idx=0）反向追溯历史。通过如下递归 CTE 查询一次性取得完整有序链条：

```sql
WITH RECURSIVE chain(h, step) AS (
  SELECT ?, 0
  UNION ALL
  SELECT p.parent, chain.step + 1
  FROM commit_parent p JOIN chain ON p.child = chain.h
  WHERE p.idx = 0
)
SELECT h FROM chain ORDER BY step;
```

随后逐个加载对应 Commit 打印日志输出，与原输出格式严格保持一致。

### 4. 事务边界设计 (Transaction Boundaries)

SyncVault 针对所有写操作定义了严密的事务边界，采用支持重入的深度计数器管理（`begin()` / `commitTx()` / `rollback()` / `transaction(...)`）：

- **Commit (`commitInCurrBran`)**：将新 Commit 对象写入 `object`、写入 `commit_meta`、插入 `commit_parent`、更新活动分支 `ref` 指针以及清空暂存区表（`clearStage`）全部包裹在单一事务中。
- **Merge (`merge`)**：在冲突检测与规则应用阶段，冲突 blob 的写入、暂存区表的更新、新 merge commit 及其父节点写入、活动分支移动以及暂存区重置全部包裹在单个事务内。因为 merge 内部会调用 `saveToStage` 与 `reset`，重入事务机制确保只有最外层提交生效。
- **Reset (`reset`)**：包括目标提交校验后的引用指针更新（`setRef`）与暂存区清空。
- **Checkout Branch (`check_branch`)**：分支切换后的 HEAD 引用更新（`setHead`）与暂存区清空。
- **Push / Fetch (`push` / `fetch`)**：
  - `fetch`：拉取的所有 missing commits、blob 写入与远程跟踪分支 ref（如 `origin/master`）的更新包裹在本地数据库的一个事务中。
  - `push`：推送到远端的所有 missing commits、blob 写入与远端目标分支 ref 更新包裹在远端数据库的一个事务中。
- **工作区与文件系统写入**：注意工作区（CWD）文件的创建、覆盖与删除属于文件系统 I/O，位于数据库事务外部执行，因此崩溃时工作区可能已部分更新，但数据库（ref/HEAD/暂存区/提交）要么全部生效要么全部不生效。
- **回滚方式**：为了保持原有代码结构，业务代码里只写 `Store.begin()` ... `Store.commitTx()`，没有额外的 try/catch。进程在 `commitTx()` 之前因异常或 `System.exit` 结束时，SQLite 的 rollback journal 会把未提交的事务丢弃（下次打开数据库时自动回滚），效果与显式 rollback 相同。

### 5. 暂存区表结构与设计理由

暂存新增存储在 `stage_add (name, hash)` 表，暂存删除存储在 `stage_rm (name)` 表。在 `add` 执行时，文件内容立即作为 blob 写入 `object` 表（类似 Git 的对象数据库设计，使用 `INSERT OR IGNORE` 去重）。
- **理由**：
  1. 避免在 `.gitlet/stage/` 与 `.gitlet/rm/` 中频繁创建/删除小文件与目录，消除了文件系统遍历与并发文件锁定的开销。
  2. 暂存区操作与提交/重置等操作可以通过数据库事务原子化处理，避免进程中断时留下孤立文件或不一致状态。

### 6. 为何选择 SQLite 而非 MySQL

SyncVault 定位于多设备分布式资产同步与版本控制系统：
1. **单文件零配置 (Embedded Single File)**：每个本地仓库只需一个 `.gitlet/gitlet.db` 文件，无需安装、配置与维护独立的数据库服务进程（daemon）。
2. **离线高可用 (Offline-First)**：版本控制要求每个节点在无网络连接、无中央服务端的情况下能够独立执行 init, add, commit, branch, merge, reset, log 等全部操作。
3. **轻量与跨平台**：通过 `sqlite-jdbc` 跨平台驱动，SyncVault 可以直接打包为便携的单一可执行 Fat JAR，随处运行。
4. **强 ACID 事务与可靠性**：SQLite 经过长期工业验证，提供完整的 ACID 事务与崩溃恢复能力，在进程异常退出时不会造成元数据损坏。
5. **便捷备份与迁移**：复制或同步单个 `.gitlet/gitlet.db` 文件即可完成整个版本库元数据的迁移与备份。

### 7. 提交 ID 与内容哈希保持不变

- Commit ID 的计算机制完全保持不变：在 `self_hash` 字段为 `null` 时序列化该 Commit 对象，并计算其内容哈希（`Utils.hash(Utils.serialize(commit))`）。
- Java 序列化字节里包含类的 `serialVersionUID`。为了让存储层访问提交信息，`Commit` 新增了 `getMessage()` / `getTimestamp()`，这会改变 Java 自动计算的 UID，从而改变所有 commit id。因此 `Commit` 显式固定 `serialVersionUID = -1296720244208386047L`（即迁移前类的自动计算值），初始提交的 id 仍是 `15fd3b34…dd01`，与文件存储版本逐字节一致（有单元测试 `CommitIdTest` 守护）。
- Blob ID 依然严格等于文件原始字节的内容哈希（`Utils.hash(fileBytes)`）。
- 对象寻址机制与历史数据兼容，保证了计算的纯粹性与可验证性。
