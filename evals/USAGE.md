# 可复现评测使用说明

推荐入口：`python evals/cli.py --help`，以下命令从仓库根目录运行。
当前推荐流程采用 **v3 可靠埋点运行时 + v4 迁移任务与独立评分器**。
这是 ThoughtCoding 专用内部题集，不是任意仓库/模型通用评测框架。
历史报告不属于运行依赖；`TRANSFER_V4_PLAN.md`、`PUBLIC_FEEDBACK_V3_PLAN.md` 和
`PUBLIC_FEEDBACK_V2_PLAN.md` 是冻结清单引用的协议文件，应随代码保留。

## 环境与无模型验证

需要 Python 3.10+、JDK 21+（java 和 javac）、Maven、Git，以及完整 Git 历史。
不要使用下载 ZIP 或浅克隆：任务使用历史 commit 导出代码。缺少历史时先获取完整历史。
Maven 首次构建/测试需要下载依赖。当前流程使用本地工具限制，不是操作系统安全沙箱；
历史代码及 Maven 测试在当前用户权限下执行，建议在专用机器或隔离环境运行。

```powershell
python -m venv .venv
# Windows PowerShell；也可以直接使用 .venv/Scripts/python.exe
.\.venv\Scripts\Activate.ps1
python -m pip install -r evals/requirements.txt
python evals/cli.py doctor
python evals/cli.py test
python evals/cli.py build
python evals/cli.py calibrate
```

Linux/macOS 激活命令为 `source .venv/bin/activate`。
上述步骤均不调用模型；doctor 不访问网络，build/calibrate 可能访问 Maven 仓库。
校准执行8题的起点、参考修复及适用的已知错误补丁，生成
`evals/results/transfer-v4-calibration/`，包括预期测试集合和冻结哈希。
该目录不是随仓库分发的历史成绩，需要每个使用者自行生成。
校准包含完整回归和Git worktree行为测试，可能需要十几分钟；各步骤日志在该目录内，
失败与超时会保留证据，不要把进程仍在执行误判成没有输出。
若校准目录已存在，入口拒绝覆盖；失败后先保留并排查记录，再手动改名归档后重新校准。
构建也会执行适配器限制和可靠埋点的合成自检。

## 配置与真实实验

将 `evals/config.siliconflow.example.yaml` 复制到根目录 `config.yaml`，仅在本地填写密钥。
已有配置不要覆盖。当前运行器固定支持 `Qwen/Qwen3-Coder-30B-A3B-Instruct`，
代理使用历史输入0.70元/百万Token、输出2.80元/百万Token估算预算，非当前价格或账单承诺。
更换模型必须先修改预算计价与模型约束并冻结新协议，不能直接比较历史成绩。
当前是固定26次实验，每次最多2元历史估算额度；这不是供应商端的消费硬上限。

```powershell
# 只冻结运行清单，不调用模型；需要有效的本地模型配置
python evals/cli.py run --prepare-only
# 将上一条打印的 BATCH 路径填入；从这里开始调用付费模型
python evals/cli.py run --resume evals/results/transfer-v4-实际批次ID
```

也可直接 `python evals/cli.py run` 创建并执行新批次。
固定5个新需求各组2次、3个历史回归题各组1次，两组共26次，随机交错、2个worker。
两组仅改变公开兼容性检查的可用性；最终回归与私有验收相同。
输出目录保留源码/运行时哈希、任务合同、补丁、请求响应、工具事件、测试结果和原始Span。
`summary.json` 分组统计，`freeze-end-verification.json` 核对结束后的冻结状态。
启动前会核对校准的成功标记与任务/验收输入哈希；输入改变后必须重新校准。
存在未完成trial目录或INVALIDATED标记时拒绝静默重跑，不按结果挑选重试。
请勿在批次运行期间修改源码、脚本、配置或验收材料。

## Langfuse 是可选的后续上传步骤

v3运行时先将原始Span和OTLP工件落盘，执行上述实验不要求Langfuse账户。
生产CLI的在线导出与评测的本地持久化是两个入口，不能混淆。

```powershell
# 一个trial目录，例如 批次/批次--baseline/write-string-type-1
python evals/cli.py spans <trial目录>
# 在当前进程设置环境变量后才执行上传（密钥不写入文件或命令示例）
# LANGFUSE_BASE_URL / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY
python evals/cli.py spans <trial目录> --upload
python evals/cli.py spans <trial目录> --verify
```

Python入口不会自动读取`.env`文件，上传前需将变量注入当前进程。
默认spans命令仅核对本地工件，不发网络请求。上传检查点会保留，避免重放已接收Span。
超时导致没有完整收尾凭据时，这个严格上传入口会拒绝；保留原始工件并标为不完整，
不要补造Span或把缺失usage记为0。部分超时上传脚本属于高级维护工具。
此入口仅上传/核验Span，不自动发布Dataset、Judge或task_success评分。
高级发布操作分别由 `publish_dataset_v4.py`、`publish_evaluators.py`、`finish_transfer_v4.py`
等历史批次脚本处理；这些脚本确有原项目ID和历史批次绑定，不属于通用使用入口。
如需公开这类脚本，应先参数化项目/批次配置；不要把原项目链接当成自己的运行结果。
本地result.json里的passed来自Java测试，Langfuse模型评分不替代它。

## 文件与发布范围

- 必要代码：本目录Python脚本（保留传递依赖）、java/、tasks/、acceptance/、
  public_checks/、reference/、reference-patches/、regression_profiles/、requirements.txt。
- 生产依赖：src/main与相关src/test、pom.xml、根配置示例；仅复制evals不足以运行。
- 历史入口：agent_run.py、standard_eval.py等仍作为底层依赖或历史复现工具，
  新用户从cli.py进入，不单独选择旧评分口径。
- 不提交：results/、.build*/、__pycache__/、config.yaml、.env、完整原始请求和个人会话。
- 可不提交：BASELINE.md、FINDINGS.md、*_RESULTS.md等实验报告。保留本说明和运行协议。

运行数据可能包含代码、对话和工具输出。导出过滤并非任意文本的完整隐私检测；
公开证据前应另外脱敏。发布仓库不能让其他人自动访问你的Langfuse项目。

## 已知边界

这8个任务依赖本仓库历史，不提供跨仓库泛化结论；Memory/MCP/SubAgent未纳入该实验。
运行器包含历史实验的版本化代码，尚未统一任意模型计价和任意suite扩展。
Windows为主要验证环境；其他系统需自行完成doctor/build/calibrate验收。
修改生产接口可能导致旧适配器构建失败；build会明确失败，不能拿历史编译产物继续冒充新版本。

## 生成待发布文件清单

运行 `python evals/prepare_publication.py` 生成独立的发布副本和`PUBLISH_FILES.json`（路径、SHA-256及检查结果）。
副本只包含推荐流程的传递依赖、相关测试、生产源码和公开说明；不修改Git暂存区，不推送远端。
副本内的协议是去除历史关联信息后的公开设计说明，原始实验协议仍保留在本地。
它不包含旧的云端批次发布脚本，也不包含Git历史。请按清单合入现有仓库分支，保留历史任务所需commit，
不要将副本直接初始化成一个没有历史的新仓库。现有根README可保留，导出说明在`PUBLICATION.md`。
检查规则覆盖已知本地密钥、密钥格式、个人目录和原Langfuse标识；这不等于穷尽所有隐私信息。
