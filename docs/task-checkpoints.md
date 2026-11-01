# 独立任务状态与执行 Checkpoint

会话保存对话；Task 保存独立目标与步骤；每次执行分配新的 runId。主任务和子任务各有 taskId、消息历史、工具日志和恢复位置，parentTaskId 关联父子任务。worktree 的产物状态与任务执行状态分别管理。

## 使用

```text
/task list
/task new 实现并验证登录接口
/task show <taskId>
/task pause [taskId]
/task resume <taskId>
/task checkpoints <taskId>
/checkpoint list [taskId]
/task complete <taskId>
/task cancel <taskId>
```

普通后续消息沿用当前会话最新主任务；该任务已完成或取消时创建新任务。独立目标使用 `/task new`。恢复不重复添加用户目标；切换会话时替换历史和任务关联。

命令行 `--list-tasks` 查看工作区任务，`--resume-task <taskId>` 自动加载原会话并恢复。也可先通过 `-c` 或 `-S <sessionId>` 打开原会话，再执行交互命令。恢复规划模式任务时需要启用原规划模式。

## 状态

| 状态 | 含义 |
| --- | --- |
| PENDING | 已创建，尚未执行 |
| RUNNING | 执行中，持有任务文件锁 |
| WAITING_INPUT | 回答结束等待用户输入，或等待核验不确定的工具结果 |
| PAUSED | 主动暂停或达到轮次上限，可继续 |
| INTERRUPTED | 原执行进程已退出，可恢复 |
| FAILED | 执行失败，保留恢复位置 |
| SUCCEEDED | 用户验收完成，终态 |
| CANCELLED | 用户取消，终态 |

自然回答结束不代表验收完成。`complete` 要求步骤和工具调用均已结束。`pause` 保留继续执行能力，`cancel` 终止任务；退出程序会向在运行的任务请求暂停。TodoWrite 的稳定步骤 ID、状态与验收证据随所属任务持久化，父子任务之间不共享步骤。

## 存储与恢复协议

任务与检查点现在保存在项目根目录的 `.thoughtcoding/state.db`（本地 SQLite）。不需要安装或启动数据库服务，JDBC 驱动随应用打包。主任务与隔离子任务共用原工作区的数据库，worktree 目录继续单独保存。数据库、WAL/SHM 辅助文件均不提交到 Git。

`tasks` 保存可索引的任务状态、会话、父任务、最新版本及步骤信息；`checkpoints` 按 `(task_id, version)` 保存完整检查点 JSON，包括消息、控制位置、工具日志、元数据和结果消费凭证；`schema_migrations` 与 `PRAGMA user_version` 标识数据库结构版本。第一版仍保存完整快照，不做消息或工具日志的增量压缩，历史版本暂不自动裁剪。

每次写入使用短事务：按期望版本更新任务状态，插入新检查点，再一起提交。任一步失败都回滚，内存只在提交成功后推进。唯一键和外键保护版本与最新指针；WAL 支持读取已提交快照，`synchronous=FULL` 保持提交持久性，写锁最多等待 5 秒后报错，不无限重试。模型请求和工具执行不持有数据库写事务。

执行锁独立于数据库：`.thoughtcoding/tasks/<taskId>/run.lock` 仍保护整个任务运行，防止跨进程重复执行。worktree 的占用锁使用独立的 `TaskLeaseManager`，不创建额外数据库。

### 从旧 JSON 存储迁移

升级前退出该工作区的旧版 ThoughtCoding 进程；不支持新旧版本同时运行。首次打开新版本时自动扫描旧 `.thoughtcoding/tasks/`，持有旧任务与会话锁，校验 HEAD、任务归属和历史版本，然后在一个事务内导入所有任务。迁移期间不能获取执行锁时会停止并提示重试。损坏或无法判定归属的历史文件会阻止迁移，不静默丢弃。

未被 HEAD 选中的最新同版本残留文件及比 HEAD 更新的孤立版本不导入。旧 JSON 文件全部保留；HEAD 先改为 `SQLITE-PENDING:<原文件名>`，数据库提交完成后改为 `SQLITE-MIGRATED:<原文件名>`。此标记让旧二进制拒绝继续写入已迁移任务，原 HEAD 文件名仍可追溯。

中途崩溃后重新启动会继续迁移：事务提交前回滚并重试，提交后按迁移记录校验，不重复导入、不覆盖 SQLite 中的新版本。已完成迁移但数据库记录丢失时会明确报错，不能自动拿旧 JSON 覆盖新进度。旧目录是迁移前备份，不是迁移后的实时备份；不要通过手动重置 HEAD 降级。

### 备份和部署边界

数据库应放在本机磁盘，不用于多台机器共享的网络目录。最简单的备份方式是退出所有使用该工作区的进程，再复制 `.thoughtcoding/`；若存在 `state.db-wal`、`state.db-shm`，一并保留。运行中的在线备份需要 SQLite backup API 或支持它的工具，不能只复制正在写入的 `state.db`。数据库损坏或版本过高时程序会停止，不自动新建空状态替代。SQLite 自身的 WAL checkpoint 是日志维护，与 Agent 执行检查点不是同一概念。

模型请求前、工具批次准备、工具开始、工具结果和回答结束均有持久化边界。恢复复用已保存的工具结果，继续剩余调用，不重复已有用户消息。模型调用失败保留请求前的位置；读取类操作中断可重试。

写操作已经开始却没有确定结果时标记 UNKNOWN，禁止自动重放。核验真实副作用后执行：

```text
/task resolve <taskId> <invocationId> done|failed <核验证据>
/task resume <taskId>
```

已记录的 read/write/edit 文件 SHA-256 指纹发生变化时阻止恢复。核验变化后可执行 `/task accept-workspace <taskId>` 接受当前基线。该检查仅覆盖已追踪文件，不覆盖任意 Shell、MCP 或外部服务副作用。恢复只从最新提交点开始，不回滚文件，也不复活原线程或进程。旧任务历史不能覆盖更新的会话消息。原有 `/rewind` 单文件回退仍独立工作。

## 子任务、后台结果与 worktree

派发之前先持久化子任务，子任务 ID 由父任务和 invocationId 确定，重复派发不会创建多个逻辑任务。`/task resume <子任务ID>` 使用子任务自己的历史与工具日志继续运行。父调用结果不明时会根据已登记子任务或已发布结果进行核验，无法确定的情况仍要求人工 resolve。

后台结果保存在子任务检查点中。父会话下次模型请求前，将结果消息和消费凭证写入同一个检查点，再更新内存历史；会话文件尚未保存就崩溃时，可从检查点补回，避免重复注入。恢复过的前台子任务也通过此机制投递新结论。这保证本地结果消息的消费一致性，不承诺外部工具副作用的 exactly-once。

隔离子任务记录 worktree 产物 ID、目录、分支、基准提交及结果提交，恢复复用原 worktree。目录和分支保留供验收，后续通过 `/agents` 的既有合并、清理流程处理。跨进程文件锁保护正在执行的任务与 worktree，巡检不会把其他进程仍在执行的目录误判为遗留目录，清理不会删除被占用目录。目录丢失、分支不符或未登记的残留目录会阻止自动恢复。

## 验证

`SqliteTaskStoreTest` 覆盖事务回滚、版本竞争、锁等待超时、迁移重试与核验、旧程序占用保护和独立 JVM 非正常退出后的恢复。`TaskServiceTest`、`DurableRecoveryTest`、`WorktreeManagerTest` 继续覆盖跨 JVM 执行锁、部分批次恢复、不确定写操作、工作区漂移、持久化消费凭证、失败模型重试、提交失败阻止工具执行及 worktree 复用。执行 `mvn test` 运行项目回归。

实现参考：[SQLite JDBC](https://github.com/xerial/sqlite-jdbc)、[WAL](https://www.sqlite.org/wal.html)、[SQLite PRAGMA](https://www.sqlite.org/pragma.html)。
