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
