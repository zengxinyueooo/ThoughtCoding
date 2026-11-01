# Shell 沙箱（Linux / WSL2）

ThoughtCoding 使用 `@anthropic-ai/sandbox-runtime@0.0.81`，通过 Node 启动 SRT CLI，
由 bubblewrap、seccomp 和网络代理限制 Shell 及其子进程。没有 Docker 镜像或常驻服务。

## 支持范围

- 第一版仅支持 Linux / WSL2，x86-64 或 ARM64。Windows 用户需在 WSL2 内运行整个 Java 应用。
- 原生 Windows 的 `sandbox.mode: required` 会报错，绝不退回 PowerShell。
- `read/write/edit/glob`、MCP、Hook、宿主管理的 Git 操作不在这个 Shell 沙箱内，仍走现有权限机制。
- workspace 是真实目录；沙箱不提供回滚。允许范围内的文件仍可能被修改或删除。
- 这不是多租户沙箱：没有 CPU、内存、磁盘硬配额，默认读取范围也不是仅限 workspace。
  子代理按 workspace 限制写入，不能据此保证彼此的数据保密。

## 安装

先安装并进入 WSL2 Linux 发行版（需要 Windows 系统功能时按微软 WSL 安装说明完成）。
建议将仓库放在 WSL 的 Linux 文件系统，例如 `~/projects/ThoughtCoding`。
在该 Linux 环境安装 Java 21、Maven 和 Node.js **22.12.0 以上**。

Ubuntu/Debian 的底层依赖：

```bash
sudo apt-get update
sudo apt-get install bubblewrap socat ripgrep curl
```

SRT 安装到项目之外，避免项目命令篡改启动组件：

```bash
npm install --prefix "$HOME/.local/share/thoughtcoding/srt" \
  --save-exact --ignore-scripts --no-audit --no-fund \
  @anthropic-ai/sandbox-runtime@0.0.81
node --version
command -v node
```

不要使用运行时 `npx -y` 自动下载。代码校验包名、版本、Node 版本和 seccomp 可执行文件，
并通过真实沙箱执行 `:` 检查启动能力。缺依赖、用户 namespace 被禁用或策略非法都会阻止启动。
版本固定用于明确接口契约，不是供应链完整性证明；安装目录应由用户信任和维护。

## 配置

将以下段落合并到现有 `config.yaml`，不要覆盖已有模型配置或凭据：

```yaml
sandbox:
  mode: required
  runtimePath: ~/.local/share/thoughtcoding/srt/node_modules/@anthropic-ai/sandbox-runtime/dist/cli.js
  nodePath: /usr/bin/node
  maxTimeoutSeconds: 100
  allowedDomains: []
  deniedDomains: []
  denyRead: []
  denyWrite: []
  extraWritePaths: []
```

使用 nvm 时，将 `nodePath` 改成 `command -v node` 显示的绝对路径。
旧配置省略整个 `sandbox` 段时兼容原行为；明确关闭用 `mode: "off"`（加引号，避免 YAML 将 off 当布尔值）。
启动时会显示当前模式。开启后没有自动降级或模型可用的退出沙箱参数；自动批准也不能取消 OS 限制。
现存配置解析失败、空配置、重复字段或非法 sandbox 字段会报错，不再静默丢弃配置。

## 默认策略

- 写入：当前调用的 workspace、独立临时目录、显式额外写目录；保留必要 stdio 设备访问。
- 读取禁止：主 workspace 和当前 workspace 的 `config.yaml`、`.env` / `.env.*`，以及
  `~/.ssh`、`~/.aws`、`~/.azure`、`~/.config/gcloud`、`~/.kube`、`~/.npmrc`、
  `~/.git-credentials`、`~/.m2/settings.xml`、`~/.gradle/gradle.properties`。
  还会显式保护主 workspace、当前 workspace 和 HOME 顶层的 `.env` 及启动该次命令时已存在的 `.env.*`。
  这只是已知凭据路径清单；其他秘密应加入 `denyRead`。`.env.example` 也会被默认 glob 遮蔽。
- 写入禁止：上述敏感路径、沙箱策略/安装组件、当前与主工作区的 `.git`、SRT 默认的共享临时和日志写目录。
  Git 提交与 worktree 收尾由宿主 `WorktreeManager` 完成；Shell 中 `git add/commit` 可能被拒绝。
- 网络：默认禁止，只通过 SRT 代理访问 `allowedDomains`；设置严格白名单、不开放本地绑定或任意 Unix socket。
- 启动环境：不继承 API key、代理变量、`NODE_OPTIONS`、`BASH_ENV`、`LD_PRELOAD` 等。
  仅提供 HOME、固定 locale/SHELL、过滤后的 PATH 和可信 JAVA_HOME；SRT 再设置代理变量。
- 超时：用户命令的 timeout 最多为 `maxTimeoutSeconds`（1..100），给 Dispatcher 的 120 秒兜底预留清理时间。

策略由宿主按调用生成到 HOME 下的私有临时目录；配置路径与命令均作为独立参数传入 Node，
不经宿主 Shell 拼接。策略目录在沙箱内不可读写。每次执行结束清理策略、临时目录和进程。
SRT 可能为不存在的保护路径短暂建立占位文件；异常强杀时需检查残留，不能依赖沙箱做数据备份。

需要 Maven 下载依赖时，先在宿主创建缓存，再显式开放：

```bash
mkdir -p "$HOME/.m2/repository"
```

```yaml
  allowedDomains:
    - repo.maven.apache.org
  extraWritePaths:
    - ~/.m2/repository
```

这里两项属于 `sandbox` 段。企业镜像和其他仓库需另列域名；不要开放整个 HOME 或 `/tmp`。
构建工具能否联网取决于代理支持，沙箱启动探测不替代 Maven/npm 的实际构建验收。
若宿主 Maven 依赖 `~/.m2/settings.xml`，该文件在沙箱内默认不可读；优先准备无凭据的项目构建配置。
共享依赖缓存会扩大写入影响范围，不适合运行彼此不信任的任务。

## 验证与运行

普通回归测试可在 Windows 上执行，但不会证明 Linux OS 隔离有效：

```bash
mvn test
```

真实 Linux/WSL2 沙箱验收使用假凭据、临时目录和本机 HTTP 测试服务，不连接模型、不访问外部服务：

```bash
THOUGHTCODING_SANDBOX_INTEGRATION=true \
THOUGHTCODING_NODE_PATH="$(command -v node)" \
mvn -Dtest=SrtSandboxIntegrationTest test
```

安装在其他位置时，再设置 `THOUGHTCODING_SRT_PATH` 为 `dist/cli.js` 的绝对路径。
也可在 Linux/WSL2 中运行 `bash scripts/verify-shell-sandbox.sh`，先检查依赖，再清理旧报告并强制执行真实验收。
该脚本不会安装系统组件、修改配置或自动降级；任一检查失败均以非零状态退出。
启用验收后缺依赖或平台不支持会测试失败，而不是跳过。
验收覆盖 Java 编译执行、凭据拒读、路径穿越、符号链接、网络白名单及直连阻止、并发 workspace 和取消清理。

最后在同一 WSL2 环境中构建并运行：

```bash
mvn package
./bin/thought
```

上线前还需在实际目标仓库验证构建缓存、工具链及 worktree 工作流。

参考：[SRT](https://github.com/anthropics/sandbox-runtime)、
[bubblewrap](https://github.com/containers/bubblewrap)、
[WSL 安装](https://learn.microsoft.com/windows/wsl/install)。
