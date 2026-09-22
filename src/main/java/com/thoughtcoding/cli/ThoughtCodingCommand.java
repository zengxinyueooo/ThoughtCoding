package com.thoughtcoding.cli;

import com.thoughtcoding.core.AgentLoop;
import com.thoughtcoding.core.AgentTurnRunner;
import com.thoughtcoding.core.DirectCommandExecutor;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.core.WorktreeManager;
import com.thoughtcoding.memory.MemoryService;
import com.thoughtcoding.memory.MemoryStore;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.service.SessionService;
import com.thoughtcoding.ui.ThoughtCodingUI;
import com.thoughtcoding.config.MCPConfig;
import com.thoughtcoding.config.MCPServerConfig;
import picocli.CommandLine;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * 命令解析和路由
 *
 * 参数解析：Picocli自动将 --interactive 等参数映射到字段
 *
 * 命令路由：根据参数组合决定执行路径
 *
 * 上下文传递：确保所有命令都能访问统一的上下文
 *
 * 服务获取和准备，通过Context统一获取服务实例 (Command类从Context获取服务)
 */
@CommandLine.Command(name = "thought", mixinStandardHelpOptions = true,
        description = "ThoughtCoding CLI - Interactive Code Assistant")
public class ThoughtCodingCommand implements Callable<Integer> {

    private final ThoughtCodingContext context;

    // 添加会话管理字段
    private AgentLoop currentAgentLoop;
    private String currentSessionId;

    // 直接命令执行器
    private DirectCommandExecutor directCommandExecutor;

    @CommandLine.Option(names = {"-i", "--interactive"}, description = "Run in interactive mode")
    private boolean interactive = true;

    @CommandLine.Option(names = {"-c", "--continue"}, description = "Continue last session")
    private boolean continueSession;

    @CommandLine.Option(names = {"-S", "--session"}, description = "Specify session ID")
    private String sessionId;

    @CommandLine.Option(names = {"-p", "--prompt"}, description = "Single prompt mode")
    private String prompt;

    @CommandLine.Option(names = {"-m", "--model"}, description = "Specify model to use")
    private String model;

    @CommandLine.Option(names = {"--list-sessions"}, description = "List all sessions")
    private boolean listSessions;

    @CommandLine.Option(names = {"--delete-session"}, description = "Delete specified session")
    private String deleteSessionId;

    // 🔥 新增 MCP 选项
    @CommandLine.Option(names = {"--mcp-tools"}, description = "Use predefined MCP tools (e.g., github-search,file-system,sql-query)")
    private String mcpTools;

    @CommandLine.Option(names = {"--mcp-connect"}, description = "Connect to custom MCP server")
    private String mcpConnect;

    @CommandLine.Option(names = {"--mcp-command"}, description = "MCP server command")
    private String mcpCommand;

    @CommandLine.Option(names = {"--mcp-list"}, description = "List available MCP tools and servers")
    private boolean mcpList;

    @CommandLine.Option(names = {"--mcp-predefined"}, description = "List predefined MCP tools")
    private boolean mcpPredefined;

    public ThoughtCodingCommand(ThoughtCodingContext context) {
        this.context = context;
        this.directCommandExecutor = new DirectCommandExecutor(context);
    }

    @Override
    public Integer call() {
        try {
            // 🔥 先处理 MCP 选项（在初始化上下文之前）
            handleMCPOptions();

            ThoughtCodingUI ui = context.getUi();
            SessionService sessionService = context.getSessionService();

            // 显示欢迎信息
            ui.showBanner();

            // 非破坏性巡检：只 prune 已丢失的 Git 注册并提示残留，不自动删除未合并成果。
            auditSubAgentWorktrees(ui);

            // 🔥 显示 MCP 状态信息
            if (context.isMCPEnabled() || mcpTools != null || mcpConnect != null) {
                int mcpToolCount = context.getMCPToolCount();
                if (mcpToolCount > 0) {
                    ui.displaySuccess("MCP Tools: " + mcpToolCount + " tools available");
                }
            }

            // 确定使用哪个模型
            String modelToUse = model != null ? model : context.getAppConfig().getDefaultModel();

            // 处理列表会话
            if (listSessions) {
                List<String> sessions = sessionService.listSessions();
                ui.displaySessionList(sessions);
                return 0;
            }

            // 处理删除会话
            if (deleteSessionId != null) {
                boolean deleted = sessionService.deleteSession(deleteSessionId);
                if (deleted) {
                    ui.displayInfo("Session deleted: " + deleteSessionId);
                } else {
                    ui.displayError("Failed to delete session: " + deleteSessionId);
                }
                return 0;
            }

            // 🔥 处理 MCP 列表命令
            if (mcpList) {
                context.printMCPInfo();
                return 0;
            }

            // 🔥 处理预定义 MCP 工具列表
            if (mcpPredefined) {
                showPredefinedMCPTools();
                return 0;
            }

            // 加载会话历史
            List<ChatMessage> history = new ArrayList<>();

            if (continueSession) {
                currentSessionId = sessionService.getLatestSessionId();
                if (currentSessionId != null) {
                    history = sessionService.loadSession(currentSessionId);
                    ui.displayInfo("Continuing from previous session: " + currentSessionId);
                } else {
                    ui.displayInfo("No previous session found. Starting new session.");
                    currentSessionId = UUID.randomUUID().toString();
                }
            } else if (sessionId != null) {
                currentSessionId = sessionId;
                history = sessionService.loadSession(sessionId);
                ui.displayInfo("Loaded session: " + sessionId);
            } else {
                // 创建新会话
                currentSessionId = UUID.randomUUID().toString();
                ui.displayInfo("Created new session: " + currentSessionId);
            }

            // 创建Agent循环
            // AgentLoop启动和协调 ，AI对话、工具调用、会话管理等
            /*流程协调：管理从用户输入到AI响应的完整流程

            工具调度：协调AI模型与工具系统的交互

            状态管理：维护对话状态和上下文

            错误处理：处理整个流程中的异常情况*/
            currentAgentLoop = new AgentLoop(context, currentSessionId, modelToUse);
            currentAgentLoop.loadHistory(history);

            // 单次对话模式
            if (prompt != null) {
                try {

                    // 显示用户输入
                    ChatMessage userMessage = new ChatMessage("user", prompt);
                    ui.displayUserMessage(userMessage);

                    // 处理AI响应，协调整个处理流程
                    currentAgentLoop.processInput(prompt);

                    return 0;
                } catch (Exception e) {
                    context.getUi().displayError("Failed to process prompt: " + e.getMessage());
                    e.printStackTrace();
                    return 1;
                }
            }

            // Picocli自动解析命令行参数并注入到字段中
            // 交互式模式
            if (interactive) {
                return startInteractiveMode(currentAgentLoop, ui);
            }

            return 0;

        } catch (Exception e) {
            context.getUi().displayError("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 🔥 处理 MCP 选项
     */
    private void handleMCPOptions() {
        ThoughtCodingUI ui = context.getUi();

        if (mcpTools != null) {
            ui.displayInfo("Connecting predefined MCP tools: " + mcpTools);
            boolean success = context.usePredefinedMCPTools(mcpTools);
            if (success) {
                ui.displaySuccess("MCP tools connected successfully");
            } else {
                ui.displayError("Failed to connect MCP tools");
            }
        }

        if (mcpConnect != null && mcpCommand != null) {
            ui.displayInfo("Connecting MCP server: " + mcpConnect);
            boolean success = context.connectMCPServer(mcpConnect, mcpCommand, Collections.emptyList());
            if (success) {
                ui.displaySuccess("MCP server connected successfully");
            } else {
                ui.displayError("Failed to connect MCP server");
            }
        }
    }

    /**
     * 🔥 显示预定义 MCP 工具列表
     */
    private void showPredefinedMCPTools() {
        ThoughtCodingUI ui = context.getUi();
        ui.getTerminal().writer().println("\n🔧 Predefined MCP Tools:");
        ui.getTerminal().writer().println("──────────────────────");
        ui.getTerminal().writer().println("• github-search    - GitHub repository search");
        ui.getTerminal().writer().println("• sql-query        - PostgreSQL database queries");
        ui.getTerminal().writer().println("• file-system      - Local file system operations");
        ui.getTerminal().writer().println("• web-search       - Web search using Brave");
        ui.getTerminal().writer().println("• calculator       - Mathematical calculations");
        ui.getTerminal().writer().println("• weather          - Weather information");
        ui.getTerminal().writer().println("• memory           - Memory operations");
        ui.getTerminal().writer().println();
        ui.getTerminal().writer().println("Usage: --mcp-tools tool1,tool2,tool3");
        ui.getTerminal().writer().flush();
    }

    private Integer startInteractiveMode(AgentLoop agentLoop, ThoughtCodingUI ui) {
        ui.displayInfo("Entering interactive mode. Type 'exit' to quit, 'help' for commands.");

        // 回合后台化：agent 在虚拟线程上跑，主线程保持可输入（stop 可中断）
        AgentTurnRunner runner = new AgentTurnRunner(agentLoop, context);

        try {
            while (true) {
                try {
                    // 🔥 在读取输入前输出一个换行，确保 thought> 提示符在新的一行
                    ui.getTerminal().writer().println();
                    ui.getTerminal().writer().flush();

                    String input = ui.readInput(
                            com.thoughtcoding.security.PlanMode.isActive() ? "plan> " : "thought> ");

                    if (input == null || input.trim().isEmpty()) {
                        continue;
                    }

                    String trimmedInput = input.trim();

                    // ── 输入路由：agent 线程的工具确认框正在等待输入 → 优先投递给它 ──
                    com.thoughtcoding.core.ConsoleInputRouter router = context.getConsoleInputRouter();
                    if (router != null && router.hasPending()) {
                        if (trimmedInput.equalsIgnoreCase("stop") || trimmedInput.equalsIgnoreCase("停止")) {
                            // stop 在确认等待期间：拒绝确认（投递 2）并取消整个回合
                            router.deliverLine("2");
                            cancelCurrentTurn(runner, ui);
                        } else {
                            router.deliverLine(trimmedInput);
                        }
                        continue;
                    }

                    // 退出命令
                    if (trimmedInput.equalsIgnoreCase("exit") || trimmedInput.equalsIgnoreCase("quit")) {
                        ui.displayInfo("Goodbye!");
                        break;
                    }

                    // 帮助命令
                    if (trimmedInput.equalsIgnoreCase("help")) {
                        showHelp();
                        continue;
                    }

                    // 清屏命令
                    if (trimmedInput.equalsIgnoreCase("clear")) {
                        ui.clearScreen();
                        continue;
                    }

                    // 🛑 停止生成命令：取消当前回合（流式提前结束、bash 被 kill、子代理中断）
                    if (trimmedInput.equalsIgnoreCase("stop") || trimmedInput.equalsIgnoreCase("停止")) {
                        cancelCurrentTurn(runner, ui);
                        continue;
                    }

                    // 回合运行中：其余操作要求先 stop（它们会改动共享服务/会话状态）
                    if (runner.isRunning()) {
                        ui.displayWarning("⚠️  任务执行中，输入 stop 可中断后再操作");
                        continue;
                    }

                    // 🔧 直接命令帮助
                    if (trimmedInput.equalsIgnoreCase("/commands") || trimmedInput.equalsIgnoreCase("/cmds")) {
                        directCommandExecutor.listSupportedCommands();
                        continue;
                    }

                    // 🔥 MCP 相关命令 - 直接在这里处理
                    if (trimmedInput.startsWith("/mcp")) {
                        handleMCPCommand(trimmedInput);
                        continue;
                    }

                    // SubAgent worktree 生命周期管理
                    if (trimmedInput.equals("/agents") || trimmedInput.startsWith("/agents ")) {
                        handleAgentsCommand(trimmedInput);
                        continue;
                    }

                    // 📋 计划模式（Plan Mode）：只读研究 → 产出计划 → 批准后执行
                    if (trimmedInput.equals("/plan") || trimmedInput.startsWith("/plan ")) {
                        handlePlanCommand(trimmedInput, agentLoop);
                        continue;
                    }

                    // 🧠 长期记忆管理：查看/检索/删除记忆，手动触发整理
                    if (trimmedInput.equals("/memory") || trimmedInput.startsWith("/memory ")) {
                        handleMemoryCommand(trimmedInput);
                        continue;
                    }

                    // 🚀 新增：检查是否是直接命令执行
                    //TODO ：优化正则检测，降低误判率
                    if (directCommandExecutor.shouldExecuteDirectly(trimmedInput)) {
                        directCommandExecutor.executeDirectCommand(trimmedInput);
                        continue;
                    }

                    // 🚨 关键修复：检查是否是参数格式
                    if (isParameterFormat(trimmedInput)) {
                        handleParameterInInteractiveMode(trimmedInput, agentLoop);
                        continue;
                    }

                    // 处理普通对话：提交为后台回合（立即返回，可继续输入 stop 中断）
                    runner.submit(trimmedInput);

                } catch (Exception e) {
                    ui.displayError("Error: " + e.getMessage());
                }
            }
            return 0;
        } finally {
            // 命令级资源在自己的作用域回收；应用级资源由 ThoughtCodingContext.close() 负责。
            runner.shutdown();
        }
    }

    /**
     * 取消当前 agent 回合：先触发回合 CancelToken（跨层传播），
     * 再走 LangChainService 的共享生成状态兜底。
     */
    private void cancelCurrentTurn(AgentTurnRunner runner, ThoughtCodingUI ui) {
        if (runner.cancelCurrent()) {
            ui.displayWarning("⏸️  正在取消当前任务...");
        }
        stopCurrentGeneration();
    }

    private void auditSubAgentWorktrees(ThoughtCodingUI ui) {
        if (!context.getAppConfig().getAi().isSubagentWorktreeIsolation()) return;
        try {
            WorktreeManager manager = worktreeManager();
            if (!manager.isGitRepository()) return;
            WorktreeManager.AuditReport report = manager.audit();
            if (report.needsAttention()) {
                ui.displayWarning("⚠️  SubAgent Worktree 巡检：共 " + report.total()
                        + " 个任务，需检查 " + report.attention()
                        + " 个，已合并可清理 " + report.merged()
                        + " 个，超过 7 天未合并 " + report.aged()
                        + (report.imported() > 0 ? "，导入旧记录 " + report.imported() + " 个" : "")
                        + "。使用 /agents list 查看。");
            }
        } catch (WorktreeManager.WorktreeException e) {
            ui.displayWarning("⚠️  SubAgent Worktree 启动巡检失败: " + e.getMessage());
        }
    }

    /**
     * 计划模式（Plan Mode）命令入口。
     *
     * <pre>
     *   /plan            未激活则进入；已激活显示用法
     *   /plan on         进入计划模式（提示符变 plan>，模型只能只读研究并产出计划）
     *   /plan approve    批准计划：把最近一轮 assistant 输出（计划正文）注入为 user 消息，
     *                    退出计划模式——下一轮用户输入即按计划执行
     *   /plan exit       放弃计划并退出计划模式（已产出的计划留在历史中但不会被标记执行）
     *   /plan status     显示当前模式
     * </pre>
     *
     * <p>权限约束由 {@code PermissionGate} 在计划模式下强制（写类工具 DENY、bash 只读白名单），
     * 本命令只负责模式切换与计划批准的编排。
     */
    private void handlePlanCommand(String command, AgentLoop agentLoop) {
        ThoughtCodingUI ui = context.getUi();
        String[] parts = command.trim().split("\\s+");
        String action = parts.length < 2 ? "" : parts[1].toLowerCase();

        switch (action) {
            case "" -> {
                if (com.thoughtcoding.security.PlanMode.isActive()) {
                    displayPlanHelp(ui);
                } else {
                    com.thoughtcoding.security.PlanMode.enter();
                    ui.displayInfo("""
                            📋 已进入计划模式 (Plan Mode)，提示符已切换为 plan>。
                               模型现在只能读代码、跑只读命令，产出实施计划后停止。
                               审阅计划后：/plan approve 批准并执行 | /plan exit 放弃""");
                }
            }
            case "on" -> {
                com.thoughtcoding.security.PlanMode.enter();
                ui.displayInfo("📋 已进入计划模式 (Plan Mode)。输入 /plan approve 批准计划，/plan exit 放弃。");
            }
            case "exit", "off" -> {
                com.thoughtcoding.security.PlanMode.exit();
                ui.displayInfo("✅ 已退出计划模式，恢复正常执行。");
            }
            case "approve", "yes", "y" -> {
                if (!com.thoughtcoding.security.PlanMode.isActive()) {
                    ui.displayWarning("⚠️  当前不在计划模式。先用 /plan 进入。");
                    return;
                }
                String plan = extractLastAssistantText(agentLoop);
                if (plan == null) {
                    ui.displayWarning("⚠️  历史中没有可批准的计划文本。让模型先完成研究并输出计划，"
                            + "或用 /plan exit 退出。");
                    return;
                }
                agentLoop.injectUserMessage(
                        "[用户已批准以下实施计划，请严格按计划开始执行]\n\n" + plan);
                com.thoughtcoding.security.PlanMode.exit();
                ui.displaySuccess("✅ 计划已批准并注入上下文，已退出计划模式。发送消息即可按计划开始执行。");
            }
            case "status" -> ui.displayInfo(
                    "📋 当前模式: " + com.thoughtcoding.security.PlanMode.current());
            default -> displayPlanHelp(ui);
        }
    }

    private void displayPlanHelp(ThoughtCodingUI ui) {
        ui.displayInfo("""
                📋 计划模式 (Plan Mode) 用法：
                   /plan            进入计划模式（已激活时显示本帮助）
                   /plan on         进入计划模式
                   /plan approve    批准最近产出的计划并退出计划模式
                   /plan exit       放弃计划并退出
                   /plan status     查看当前模式""");
    }

    /** 倒序找最近一条纯文本 assistant 消息（无工具调用）作为计划正文；没有则返回 null。 */
    private String extractLastAssistantText(AgentLoop agentLoop) {
        List<ChatMessage> history = agentLoop.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m != null && "assistant".equals(m.getRole())
                    && !m.hasToolCalls()
                    && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }

    private void handleAgentsCommand(String command) {
        ThoughtCodingUI ui = context.getUi();
        String[] parts = command.trim().split("\\s+");
        String action = parts.length < 2 ? "list" : parts[1].toLowerCase();
        try {
            if ("list".equals(action)) {
                displayWorktreeTasks(worktreeManager().listTasks());
                return;
            }
            if ("help".equals(action)) {
                displayAgentsHelp();
                return;
            }
            if (!"cleanup".equals(action)) {
                ui.displayWarning("未知 /agents 子命令: " + action);
                displayAgentsHelp();
                return;
            }

            String taskId = null;
            Integer olderThanDays = null;
            boolean force = false;
            for (int i = 2; i < parts.length; i++) {
                String part = parts[i];
                if ("--force".equalsIgnoreCase(part)) {
                    force = true;
                } else if (part.startsWith("--older-than=")) {
                    olderThanDays = parseNonNegativeInt(part.substring("--older-than=".length()), "--older-than");
                } else if ("--older-than".equalsIgnoreCase(part)) {
                    if (++i >= parts.length) throw new IllegalArgumentException("--older-than 后需要天数");
                    olderThanDays = parseNonNegativeInt(parts[i], "--older-than");
                } else if (taskId == null) {
                    taskId = part;
                } else {
                    throw new IllegalArgumentException("无法识别的参数: " + part);
                }
            }
            if (force && (taskId == null || taskId.isBlank())) {
                throw new IllegalArgumentException("--force 必须同时指定一个任务 id，拒绝批量强制删除");
            }

            WorktreeManager.CleanupReport report = worktreeManager().cleanup(
                    new WorktreeManager.CleanupOptions(taskId, olderThanDays, force));
            for (String message : report.messages()) {
                if (message.startsWith("已清理")) ui.displaySuccess(message);
                else ui.displayWarning(message);
            }
            ui.displayInfo("Worktree 清理完成：成功 " + report.cleaned()
                    + "，跳过/失败 " + report.skipped());
        } catch (Exception e) {
            ui.displayError("/agents 执行失败: " + e.getMessage());
        }
    }

    private void displayWorktreeTasks(List<WorktreeManager.TaskRecord> tasks) {
        ThoughtCodingUI ui = context.getUi();
        if (tasks.isEmpty()) {
            ui.displayInfo("当前仓库没有待管理的 SubAgent Worktree 任务。");
            return;
        }
        var writer = ui.getTerminal().writer();
        writer.println("\nSubAgent Worktree 任务（" + tasks.size() + "）");
        writer.println("────────────────────────────────────────");
        for (WorktreeManager.TaskRecord task : tasks) {
            writer.println(task.id() + "  [" + task.status() + "]  " + task.label());
            writer.println("  创建: " + task.createdLocal() + "（" + task.ageDays() + " 天前）");
            if (task.branch() != null) writer.println("  分支: " + task.branch());
            if (task.commit() != null) writer.println("  提交: " + task.commit());
            if (task.worktree() != null) writer.println("  目录: " + task.worktree());
            if (task.note() != null && !task.note().isBlank()) writer.println("  状态: " + task.note());
        }
        writer.println("\n安全清理已合并项: /agents cleanup");
        writer.println("查看命令说明: /agents help");
        writer.flush();
    }

    private void displayAgentsHelp() {
        context.getUi().displayInfo("""
                SubAgent Worktree 命令：
                  /agents list                         查看当前仓库的任务
                  /agents cleanup                      清理已合并或无产物任务
                  /agents cleanup <id>                 安全清理指定任务（支持唯一前缀）
                  /agents cleanup --older-than 7       仅处理超过 7 天的安全项
                  /agents cleanup <id> --force         强制删除未合并分支/worktree

                注意：--force 会永久删除指定任务尚未合并的本地成果。
                """);
    }

    private int parseNonNegativeInt(String value, String option) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " 需要非负整数，实际为: " + value);
        }
    }

    private WorktreeManager worktreeManager() {
        return new WorktreeManager(Sandbox.baseWorkspaceRoot());
    }

// 删除 handleInternalCommand 方法，因为我们已经直接处理了 MCP 命令
// 删除 handleSinglePrompt 方法，因为单次提示模式已经在 call() 方法中处理了

    /**
     * 🔥 处理 MCP 相关命令
     */
    private void handleMCPCommand(String command) {
        String[] parts = command.substring(4).trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1] : "";

        ThoughtCodingUI ui = context.getUi();

        switch (cmd) {
            case "list":
                context.printMCPInfo();
                break;
            case "connect":
                if (!argument.isEmpty()) {
                    String[] connectArgs = argument.split("\\s+");
                    if (connectArgs.length >= 1) {
                        String serverName = connectArgs[0];
                        String serverCommand = connectArgs.length >= 2 ? connectArgs[1] : "npx";

                        // 🔥 根据服务器名自动构建参数
                        List<String> args = buildMCPArgs(serverName);

                        boolean success = context.connectMCPServer(serverName, serverCommand, args);
                        if (success) {
                            ui.displaySuccess("MCP server connected: " + serverName);
                        } else {
                            ui.displayError("Failed to connect MCP server");
                        }
                    } else {
                        ui.displayError("Usage: /mcp connect <server-name> [command]");
                        ui.displayInfo("Example: /mcp connect filesystem");
                        ui.displayInfo("         /mcp connect filesystem npx");
                    }
                } else {
                    ui.displayError("Usage: /mcp connect <server-name> [command]");
                }
                break;
            case "tools":
                if (!argument.isEmpty()) {
                    boolean success = context.usePredefinedMCPTools(argument);
                    if (success) {
                        ui.displaySuccess("MCP tools connected: " + argument);
                    } else {
                        ui.displayError("Failed to connect MCP tools");
                    }
                } else {
                    ui.displayError("Usage: /mcp tools <tool1,tool2,tool3>");
                }
                break;
            case "disconnect":
                if (!argument.isEmpty()) {
                    context.disconnectMCPServer(argument);
                    ui.displaySuccess("MCP server disconnected: " + argument);
                } else {
                    ui.displayError("Usage: /mcp disconnect <server-name>");
                }
                break;
            case "predefined":
                showPredefinedMCPTools();
                break;
            default:
                ui.displayError("Unknown MCP command: " + cmd);
                ui.displayInfo("Available MCP commands:");
                ui.displayInfo("  /mcp list                    - List connected MCP tools");
                ui.displayInfo("  /mcp connect <name> <cmd>    - Connect to MCP server");
                ui.displayInfo("  /mcp tools <tool1,tool2>     - Use predefined tools");
                ui.displayInfo("  /mcp disconnect <name>       - Disconnect MCP server");
                ui.displayInfo("  /mcp predefined              - Show predefined tools");
                break;
        }
    }

    /**
     * 检查输入是否是参数格式（以 - 或 -- 开头）
     */
    private boolean isParameterFormat(String input) {
        return input.startsWith("-") && input.length() > 1;
    }

    /**
     * 在交互模式中处理参数命令
     */
    private void handleParameterInInteractiveMode(String parameter, AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        String[] args = parameter.split("\\s+");

        switch (args[0]) {
            case "-i":
            case "--interactive":
                ui.displayInfo("Already in interactive mode");
                break;

            case "-c":
            case "--continue":
                handleContinueInInteractive(currentAgentLoop);
                break;

            case "-S":
            case "--session":
                if (args.length > 1) {
                    handleLoadSession(args[1], currentAgentLoop);
                } else {
                    ui.displayError("Usage: -S <session-id>");
                }
                break;

            case "-p":
            case "--prompt":
                if (args.length > 1) {
                    // 重新组合提示内容（处理带空格的提示）
                    String prompt = parameter.substring(args[0].length()).trim();

                    // 🚨 关键修复：去掉引号
                    if (prompt.startsWith("\"") && prompt.endsWith("\"")) {
                        prompt = prompt.substring(1, prompt.length() - 1);
                    }

                    if (!prompt.isEmpty()) {
                        handleSinglePromptInInteractive(prompt, currentAgentLoop);
                    } else {
                        ui.displayError("Usage: -p \"your prompt\"");
                    }
                } else {
                    ui.displayError("Usage: -p \"your prompt\"");
                }
                break;

            // 🔥 新增 MCP 参数处理
            case "--mcp-tools":
                if (args.length > 1) {
                    boolean success = context.usePredefinedMCPTools(args[1]);
                    if (success) {
                        ui.displaySuccess("MCP tools connected: " + args[1]);
                    } else {
                        ui.displayError("Failed to connect MCP tools");
                    }
                } else {
                    ui.displayError("Usage: --mcp-tools <tool1,tool2,tool3>");
                }
                break;

            case "--mcp-list":
                context.printMCPInfo();
                break;

            case "--mcp-predefined":
                showPredefinedMCPTools();
                break;

            default:
                ui.displayError("Unknown parameter: " + args[0]);
                break;
        }
    }

    /**
     * 在交互模式中继续上次会话
     */
    private void handleContinueInInteractive(AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            //Command从Context获取服务
            String latestSessionId = context.getSessionService().getLatestSessionId();
            if (latestSessionId != null) {
                List<ChatMessage> history = context.getSessionService().loadSession(latestSessionId);

                // 🚨 关键修复：过滤空消息
                List<ChatMessage> filteredHistory = history.stream()
                        .filter(msg -> msg != null &&
                                msg.getContent() != null &&
                                !msg.getContent().trim().isEmpty())
                        .collect(Collectors.toList());

                currentAgentLoop.loadHistory(filteredHistory);
                currentSessionId = latestSessionId;
                ui.displaySuccess("Continued from session: " + latestSessionId);

                // 显示加载的消息数量
                ui.displayInfo("Loaded " + filteredHistory.size() + " messages from history");
            } else {
                ui.displayInfo("No previous session found.");
            }
        } catch (Exception e) {
            ui.displayError("Failed to continue session: " + e.getMessage());
        }
    }

    /**
     * 在交互模式中加载指定会话
     */
    private void handleLoadSession(String sessionId, AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            List<ChatMessage> history = context.getSessionService().loadSession(sessionId);
            currentAgentLoop.loadHistory(history);
            currentSessionId = sessionId;
            ui.displaySuccess("Loaded session: " + sessionId);
        } catch (Exception e) {
            ui.displayError("Failed to load session: " + e.getMessage());
        }
    }

    /**
     * 在交互模式中执行单次提示
     */
    private void handleSinglePromptInInteractive(String prompt, AgentLoop agentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            ui.displayUserMessage(new ChatMessage("user", prompt));
            agentLoop.processInput(prompt);
        } catch (Exception e) {
            ui.displayError("Failed to process prompt: " + e.getMessage());
        }
    }

    /**
     * 🛑 停止当前的 AI 生成
     */
    private void stopCurrentGeneration() {
        ThoughtCodingUI ui = context.getUi();
        try {
            if (context.getAiService().isGenerating()) {
                context.getAiService().stopCurrentGeneration();
                ui.displayWarning("⏸️  生成已停止");
            } else {
                ui.displayInfo("ℹ️  当前没有正在进行的生成");
            }
        } catch (Exception e) {
            ui.displayError("停止生成时出错: " + e.getMessage());
        }
    }

    /**
     * 长期记忆管理命令入口（用户可控性：CC 允许用户当场查看/删除记忆，这里对齐）。
     *
     * <pre>
     *   /memory           列出全部记忆（等价 /memory list）
     *   /memory show <名称>    显示某条记忆全文（按文件名或 name 匹配）
     *   /memory delete <名称>  删除某条记忆
     *   /memory dream      手动触发后台整理（跳过阈值检查）
     * </pre>
     */
    private void handleMemoryCommand(String command) {
        ThoughtCodingUI ui = context.getUi();
        MemoryStore store = context.getMemoryStore();
        if (store == null) {
            ui.displayWarning("⚠️  记忆功能未启用（config.yaml: memory.enabled=false）");
            return;
        }
        String[] parts = command.trim().split("\\s+");
        String action = parts.length < 2 ? "list" : parts[1].toLowerCase();

        switch (action) {
            case "list" -> {
                if (store.isEmpty()) {
                    ui.displayInfo("🧠 记忆库为空。对话中让模型记住的内容会自动沉淀到这里。");
                    return;
                }
                StringBuilder sb = new StringBuilder("🧠 记忆库（" + store.size() + " 条，.memory/ 目录）:\n");
                for (MemoryStore.Memory m : store.list()) {
                    sb.append("  - ").append(m.name())
                            .append(" [").append(m.type()).append("] — ")
                            .append(m.description()).append('\n');
                }
                ui.displayInfo(sb.toString());
            }
            case "show" -> {
                if (parts.length < 3) {
                    ui.displayWarning("用法: /memory show <名称>");
                    return;
                }
                MemoryStore.Memory m = findMemory(store, parts[2]);
                if (m == null) {
                    ui.displayWarning("⚠️  未找到记忆: " + parts[2] + "（用 /memory list 查看全部）");
                    return;
                }
                ui.displayInfo("🧠 " + m.name() + " [" + m.type() + "] (" + m.filename() + ")\n"
                        + m.body());
            }
            case "delete", "forget" -> {
                if (parts.length < 3) {
                    ui.displayWarning("用法: /memory delete <名称>");
                    return;
                }
                if (store.delete(parts[2])) {
                    ui.displaySuccess("✅ 已删除记忆: " + parts[2]);
                } else {
                    ui.displayWarning("⚠️  未找到记忆: " + parts[2] + "（用 /memory list 查看全部）");
                }
            }
            case "dream" -> {
                MemoryService memory = context.getMemoryService();
                if (memory == null) {
                    ui.displayWarning("⚠️  记忆服务不可用");
                    return;
                }
                memory.dreamAsync(true, msg -> ui.displayInfo(msg));
                ui.displayInfo("🧠 记忆整理已在后台启动...");
            }
            default -> ui.displayInfo("""
                    🧠 记忆命令：
                      /memory                列出全部记忆
                      /memory show <名称>    查看某条记忆全文
                      /memory delete <名称>  删除某条记忆
                      /memory dream          手动触发后台整理""");
        }
    }

    /** 按文件名（可省略 .md）或 name 精确匹配一条记忆。 */
    private MemoryStore.Memory findMemory(MemoryStore store, String target) {
        String filename = target.endsWith(".md") ? target : target + ".md";
        for (MemoryStore.Memory m : store.list()) {
            if (m.filename().equals(filename) || m.name().equals(target)) {
                return m;
            }
        }
        return null;
    }

    private void showHelp() {
        context.getUi().displayInfo("""
                        🚀 可用命令：
                                                               \s
                                                                💬 对话命令：
                                                                  <消息>         发送消息给AI助手
                                                                  stop / 停止   停止当前的AI生成
                                                                  /new          开始新会话
                                                                  /save <名称>  保存当前会话
                                                                  /list         查看所有会话
                                                                  /clear        清空屏幕
                                                                  /help         显示帮助信息
                                                               \s
                                                                📋 计划模式：
                                                                  /plan          进入计划模式（只读研究后产出计划）
                                                                  /plan approve  批准计划并开始执行
                                                                  /plan exit     放弃计划
                                                               \s
                                                                🧠 长期记忆：
                                                                  /memory                列出全部记忆
                                                                  /memory show <名称>    查看某条记忆
                                                                  /memory delete <名称>  删除某条记忆
                                                                  /memory dream          手动触发整理
                                                               \s
                                                                🔧 直接命令：
                                                                  java version  直接执行Java命令
                                                                  git status    直接执行Git命令
                                                                  pwd, ls, etc. 系统命令直接执行
                                                                  /commands     查看所有支持直接执行的命令
                                                               \s
                                                                🔧 MCP 命令：
                                                                  /mcp list             列出MCP工具
                                                                  /mcp connect <n> <c>  连接MCP服务器
                                                                  /mcp tools <t1,t2>    使用预定义工具
                                                                  /mcp disconnect <n>   断开MCP服务器
                                                                  /mcp predefined       显示预定义工具
                                                               \s
                                                                🌿 SubAgent Worktree：
                                                                  /agents list           查看隔离任务和残留
                                                                  /agents cleanup        安全清理已合并任务
                                                                  /agents help           查看清理选项
                                                               \s
                                                                ⚡ 快捷命令：
                                                                  -c, --continue       继续上次会话
                                                                  -S <id>             加载指定会话 \s
                                                                  -p "提示"           单次提问模式
                                                                  --list-sessions     列出所有会话
                                                                  --mcp-tools <t>     使用MCP工具
                                                                  --mcp-list          列出MCP状态
                                                               \s
                                                                ❌ 退出命令：
                                                                  exit / quit         退出程序
                """);
    }

    /**
     * 🔥 根据服务器名从配置文件读取 MCP 参数
     * 优先使用配置文件中的配置，如果没有则使用默认配置
     */
    private List<String> buildMCPArgs(String serverName) {
        // 🔥 优先从配置文件读取
        var mcpConfig = context.getMcpConfig();
        var serverConfig = mcpConfig.getServerConfig(serverName);

        if (serverConfig != null && serverConfig.getArgs() != null && !serverConfig.getArgs().isEmpty()) {
            // 使用配置文件中的参数
            context.getUi().displayInfo("✓ 使用配置文件中的 " + serverName + " 配置");
            return new ArrayList<>(serverConfig.getArgs());
        }

        // 🔥 如果配置文件中没有，使用默认配置（向后兼容）
        context.getUi().displayWarning("⚠ 配置文件中未找到 " + serverName + " 配置，使用默认配置");

        List<String> args = new ArrayList<>();

        switch (serverName.toLowerCase()) {
            case "filesystem":
            case "file-system":
                args.add("-y");
                args.add("@modelcontextprotocol/server-filesystem");
                args.add(System.getProperty("user.home")); // 用户主目录
                break;

            case "sqlite":
                args.add("-y");
                args.add("@modelcontextprotocol/server-sqlite");
                args.add("--database");
                args.add("./data.db");
                break;

            case "postgres":
            case "postgresql":
                args.add("-y");
                args.add("@modelcontextprotocol/server-postgres");
                args.add("--connectionString");
                args.add("postgresql://user:pass@localhost:5432/db");
                break;

            case "github":
                args.add("-y");
                args.add("@modelcontextprotocol/server-github");
                args.add("--token");
                args.add("your_github_token_here");  // 占位符
                break;

            case "mysql":
                args.add("-y");
                args.add("@modelcontextprotocol/server-mysql");
                args.add("--connectionString");
                args.add("mysql://user:pass@localhost:3306/db");
                break;

            case "weather":
                args.add("-y");
                args.add("@coding-squirrel/mcp-weather-server");
                args.add("--apiKey");
                args.add("your_weather_api_key");
                break;

            default:
                // 如果是未知服务器，尝试作为包名直接安装
                args.add("-y");
                args.add(serverName);
                break;
        }

        return args;
    }

    // ... 其他方法保持不变（handleInternalCommand, handleSinglePrompt, handleNewSession, handleSaveSession, handleListSessions, displaySessionList, handleClearScreen, saveCurrentSession）
    // 这些方法不需要修改，保持原有逻辑
}
