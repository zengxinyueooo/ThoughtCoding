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

数据位于 `.thoughtcoding/tasks/<taskId>/`，包含不可变 JSON 版本、原子替换的 HEAD 指针和运行锁。每次提交同时保存任务状态、消息、控制位置、工具日志、元数据和结果消费凭证。写入或版本冲突会停止后续执行；未被 HEAD 引用的残留写入不会成为最新恢复点。正常退出保留数据，历史版本暂不自动裁剪。

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

`TaskServiceTest`、`DurableRecoveryTest`、`WorktreeManagerTest` 覆盖跨 JVM 锁、部分批次恢复、不确定写操作、工作区漂移、孤立写入、持久化消费凭证、失败模型重试、检查点提交失败阻止工具执行及 worktree 复用。执行 `mvn test` 运行项目回归。
