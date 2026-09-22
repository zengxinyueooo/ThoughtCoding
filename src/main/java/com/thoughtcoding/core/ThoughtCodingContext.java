package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.config.ConfigManager;
import com.thoughtcoding.config.MCPConfig;
import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.mcp.MCPService;
import com.thoughtcoding.mcp.MCPToolManager;
import com.thoughtcoding.memory.MemoryService;
import com.thoughtcoding.memory.MemoryStore;
import com.thoughtcoding.service.AIService;
import com.thoughtcoding.service.ContextManager;
import com.thoughtcoding.service.LangChainService;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.service.SessionService;
import com.thoughtcoding.tool.*;
import com.thoughtcoding.tool.tools.BashTool;
import com.thoughtcoding.tool.tools.EditTool;
import com.thoughtcoding.tool.tools.GlobTool;
import com.thoughtcoding.tool.tools.ReadTool;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.skill.SkillRegistry;
import com.thoughtcoding.tool.tools.TodoWriteTool;
import com.thoughtcoding.tool.tools.SubAgentTool;
import com.thoughtcoding.tool.tools.SkillTool;
import com.thoughtcoding.tool.tools.WriteTool;
import com.thoughtcoding.ui.ThoughtCodingUI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 上下文初始化过程
 *
 * 依赖注入：确保各个组件都能获取到它们需要的依赖
 *
 * 生命周期管理：控制初始化顺序，避免循环依赖
 *
 * 资源配置：建立数据库连接、网络连接、文件句柄等
 */
public class ThoughtCodingContext implements AutoCloseable {
    private static final String MCP_OWNER_PREFIX = "mcp:";
    private final AppConfig appConfig;
    private final MCPConfig mcpConfig;
    private final AIService aiService;
    private final SessionService sessionService;
    private final ToolRegistry toolRegistry;
    private final ThoughtCodingUI ui;
    private final PerformanceMonitor performanceMonitor;
    /** 应用级 Hook 模板；各 Agent/命令执行实例从这里派生隔离动作链。 */
    private final HookRegistry hookRegistry;

    // 🔥 新增 MCP 相关服务
    private final MCPService mcpService;
    private final MCPToolManager mcpToolManager;

    // 🔥 新增上下文管理器
    private final ContextManager contextManager;

    // 并行/后台子代理调度器（虚拟线程 + Semaphore 限流）
    private final SubAgentExecutor subAgentExecutor;

    /** Context 是应用级资源所有者；关闭操作允许从多个退出路径安全重复调用。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean mcpShutdown = new AtomicBoolean(false);

    // 终端输入路由器（回合后台化后 agent 线程确认框的输入来源；由 AgentTurnRunner 构造时注册）
    private volatile ConsoleInputRouter consoleInputRouter;
    // 🔥 新增记忆系统（LLM 驱动：召回/储存/整理；非工具）
    private final MemoryService memoryService;
    private final MemoryStore memoryStore; // 可为 null = 记忆功能关闭；/memory 用户命令直接访问存储层

    private ThoughtCodingContext(Builder builder) {
        this.appConfig = builder.appConfig;
        this.mcpConfig = builder.mcpConfig;
        this.aiService = builder.aiService;
        this.sessionService = builder.sessionService;
        this.toolRegistry = builder.toolRegistry;
        this.ui = builder.ui;
        this.performanceMonitor = builder.performanceMonitor;
        this.hookRegistry = builder.hookRegistry != null
                ? builder.hookRegistry : new HookRegistry();
        this.mcpService = builder.mcpService;
        this.mcpToolManager = builder.mcpToolManager;
        this.contextManager = builder.contextManager;
        this.subAgentExecutor = builder.subAgentExecutor;
        this.memoryService = builder.memoryService;
        this.memoryStore = builder.memoryStore;
    }

    public static ThoughtCodingContext initialize() {
        // 分层初始化，确保依赖顺序正确

        // 初始化配置管理器
        ConfigManager configManager = ConfigManager.getInstance();
        configManager.initialize("config.yaml");
        AppConfig appConfig = configManager.getAppConfig();
        MCPConfig mcpConfig = configManager.getMCPConfig();

        // 初始化沙箱（以启动时的工作目录为 workspace 根）
        Sandbox.init(System.getProperty("user.dir"));

        // 启动时扫描一次 skills/ 目录（name+description 常驻 system prompt，正文按需加载）
        SkillRegistry skillRegistry = SkillRegistry.scan(
                java.nio.file.Paths.get(System.getProperty("user.dir"), "skills"));

        // 能力层初始化,创建工具注册表
        ToolRegistry toolRegistry = new ToolRegistry(appConfig);

        // 🔥 创建 MCP 服务
        MCPService mcpService = new MCPService();
        MCPToolManager mcpToolManager = new MCPToolManager(mcpService, mcpConfig);

        // 注册内置工具 - 传递整个 AppConfig 对象
        // 文件状态跟踪器在 read/write/edit 三个工具间共享，支撑「先读后改」与 stale-read 一致性校验
        com.thoughtcoding.tool.FileStateTracker fileStateTracker = new com.thoughtcoding.tool.FileStateTracker();
        if (appConfig.getTools().getBash().isEnabled()) {
            toolRegistry.register(new BashTool(appConfig));
        }

        if (appConfig.getTools().getRead().isEnabled()) {
            toolRegistry.register(new ReadTool(appConfig, fileStateTracker));
        }

        if (appConfig.getTools().getWrite().isEnabled()) {
            toolRegistry.register(new WriteTool(appConfig, fileStateTracker));
        }

        if (appConfig.getTools().getEdit().isEnabled()) {
            toolRegistry.register(new EditTool(appConfig, fileStateTracker));
        }

        if (appConfig.getTools().getGlob().isEnabled()) {
            toolRegistry.register(new GlobTool(appConfig));
        }

        // 规划工具（纯内存、无副作用），始终可用，无需 config 开关
        toolRegistry.register(new TodoWriteTool());

        // 技能加载工具：目录为空则不注册，不给模型一个永远查不到东西的工具
        if (!skillRegistry.isEmpty()) {
            toolRegistry.register(new SkillTool(skillRegistry));
        }

        // 服务层初始化
        // ── 记忆系统（非工具）：LLM 驱动召回/储存/整理；memory.enabled=false 或内存分配失败则整体为 null ──
        AppConfig.MemoryConfig memCfg = appConfig.getMemory();
        MemoryStore memoryStore = null;
        MemoryService memoryService = null;
        if (memCfg != null && memCfg.isEnabled()) {
            try {
                memoryStore = MemoryStore.load(
                        java.nio.file.Paths.get(System.getProperty("user.dir"), ".memory"),
                        memCfg.getMaxIndexEntries());
                memoryService = new MemoryService(appConfig, memoryStore, memCfg);
            } catch (Exception e) {
                // 记忆系统初始化失败不阻塞主对话
                memoryStore = null;
                memoryService = null;
            }
        }
        // 项目指令文件（CLAUDE.md 优先 / AGENTS.md 回退，对齐 Claude Code 语义）：
        // 启动扫描一次，注入 system prompt；无文件时 content 为空，ContextManager 自动跳过注入段
        com.thoughtcoding.service.ProjectInstructionsLoader.Loaded projectInstructions =
                com.thoughtcoding.service.ProjectInstructionsLoader.load(
                        java.nio.file.Paths.get(System.getProperty("user.dir")));
        if (!projectInstructions.sources().isEmpty()) {
            System.out.println("📋 已加载项目指令: " + String.join(", ", projectInstructions.sources()));
        }
        ContextManager contextManager = new ContextManager(appConfig, skillRegistry, memoryStore,
                projectInstructions.content());  // 🔥 创建上下文管理器
        AIService aiService = new LangChainService(appConfig, toolRegistry, contextManager);  // 🔥 注入 contextManager
        SessionService sessionService = new SessionService();
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();

        // UI层初始化
        ThoughtCodingUI ui = new ThoughtCodingUI();

        // 并行/后台子代理调度器：虚拟线程 + Semaphore（上限读 ai.maxConcurrentSubagents）
        SubAgentExecutor subAgentExecutor = new SubAgentExecutor(
                appConfig.getAi().getMaxConcurrentSubagents(), ui);

        // 构建上下文（核心层初始化）
        ThoughtCodingContext context = new Builder()
                .appConfig(appConfig)
                .mcpConfig(mcpConfig)
                .aiService(aiService)
                .sessionService(sessionService)
                .toolRegistry(toolRegistry)
                .ui(ui)
                .performanceMonitor(performanceMonitor)
                .hookRegistry(new HookRegistry())
                .mcpService(mcpService)
                .mcpToolManager(mcpToolManager)
                .contextManager(contextManager)  // 🔥 添加 contextManager
                .subAgentExecutor(subAgentExecutor)
                .memoryService(memoryService)
                .memoryStore(memoryStore)   // 🔥 添加 memoryService（可为 null = 记忆关闭）
                .build();

        try {
            // 子Agent 工具需持有已构建好的 context；ToolRegistry 是同一可变实例，
            // LangChainService 每次请求都会读取最新 ToolSpecification。
            context.getToolRegistry().register(new SubAgentTool(context));

            // 外部进程连接放在 Context 构建之后；后续初始化若失败，可由 close() 统一回收。
            if (mcpConfig != null && mcpConfig.isEnabled()) {
                initializeMCPTools(mcpConfig, mcpService, toolRegistry);
            }
            return context;
        } catch (RuntimeException | Error e) {
            context.close();
            throw e;
        }
    }

    /**
     * 🔥 初始化 MCP 工具
     */
    public static void initializeMCPTools(MCPConfig mcpConfig, MCPService mcpService, ToolRegistry toolRegistry) {
        // 🔥 简化输出：只在最后显示汇总信息

        if (mcpConfig != null && mcpConfig.isEnabled()) {
            int totalTools = 0;
            int successServers = 0;
            List<String> connectedServers = new ArrayList<>();

            for (var serverConfig : mcpConfig.getServers()) {
                if (serverConfig.isEnabled()) {
                    try {
                        // 静默连接，不输出中间过程
                        var tools = mcpService.connectToServer(
                                serverConfig.getName(),
                                serverConfig.getCommand(),
                                serverConfig.getArgs()
                        );

                        if (!tools.isEmpty()) {
                            int registered = registerMCPTools(
                                    toolRegistry, serverConfig.getName(), tools);
                            totalTools += registered;
                            successServers++;
                            connectedServers.add(serverConfig.getName());
                        }
                    } catch (Exception e) {
                        System.err.println("❌ 无法连接到 " + serverConfig.getName() + ": " + e.getMessage());
                    }
                }
            }

            // 🔥 输出汇总信息，包含已连接的 MCP 工具名称
            if (successServers > 0) {
                System.out.println("✅ 已加载 " + totalTools + " 个工具，已连接 MCP: " + String.join(", ", connectedServers));
            }
        }
    }

    /**
     * 🔥 动态连接 MCP 服务器（用于命令行调用）
     */
    public boolean connectMCPServer(String serverName, String command, List<String> args) {
        if (mcpService == null) {
            System.err.println("MCP 服务未初始化");
            return false;
        }

        try {
            toolRegistry.unregisterOwner(mcpOwner(serverName));
            // 🔥 直接传递三个参数，不再创建 Map
            var tools = mcpService.connectToServer(serverName, command, args);
            if (!tools.isEmpty()) {
                int registered = registerMCPTools(toolRegistry, serverName, tools);
                if (registered == 0) {
                    mcpService.disconnectServer(serverName);
                    System.err.println("✗ MCP 工具与现有能力重名，已拒绝覆盖并断开: " + serverName);
                    return false;
                }
                System.out.println("✓ 成功连接 MCP 服务器: " + serverName +
                        " (" + registered + " 个工具)");
                return true;
            }
        } catch (Exception e) {
            System.err.println("✗ 连接 MCP 服务器失败: " + serverName + " - " + e.getMessage());
        }
        return false;
    }



    /**
     * 🔥 使用预定义 MCP 工具
     */
    public boolean usePredefinedMCPTools(String toolsList) {
        if (mcpToolManager == null) {
            System.err.println("MCP 工具管理器未初始化");
            return false;
        }

        try {
            var toolNames = java.util.Arrays.asList(toolsList.split(","));
            for (String toolName : toolNames) {
                toolRegistry.unregisterOwner(mcpOwner("predefined-" + toolName.trim()));
            }
            mcpToolManager.connectPredefinedTools(toolNames);

            int registered = 0;
            for (String toolName : toolNames) {
                String serverName = "predefined-" + toolName.trim();
                registered += registerMCPTools(
                        toolRegistry, serverName, mcpService.getToolsForServer(serverName));
            }
            System.out.println("✓ 已连接 " + registered + " 个预定义 MCP 工具");
            return registered > 0;
        } catch (Exception e) {
            System.err.println("✗ 连接预定义 MCP 工具失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🔥 断开 MCP 服务器
     */
    public void disconnectMCPServer(String serverName) {
        if (mcpService != null) {
            mcpService.disconnectServer(serverName);
            toolRegistry.unregisterOwner(mcpOwner(serverName));
            System.out.println("✓ 已断开 MCP 服务器: " + serverName);
        }
    }

    /**
     * 🔥 获取 MCP 工具信息
     */
    public void printMCPInfo() {
        if (mcpService == null) {
            System.out.println("MCP 服务未初始化");
            return;
        }

        var servers = mcpService.getConnectedServers();
        var tools = mcpService.getMCPTools();

        System.out.println("MCP 服务器 (" + servers.size() + " 个):");
        servers.forEach(server -> System.out.println("  - " + server));

        System.out.println("MCP 工具 (" + tools.size() + " 个):");
        tools.forEach((name, tool) ->
                System.out.println("  - " + name + ": " + tool.getDescription())
        );
    }

    /**
     * 🔥 关闭 MCP 服务
     */
    public void shutdownMCP() {
        if (!mcpShutdown.compareAndSet(false, true)) return;
        closeSafely("MCP 工具管理器", () -> {
            if (mcpToolManager != null) mcpToolManager.shutdown();
        });
        closeSafely("MCP 服务", () -> {
            if (mcpService != null) mcpService.shutdown();
        });
        closeSafely("MCP 工具注册", () -> {
            if (toolRegistry != null) {
                toolRegistry.unregisterOwnersWithPrefix(MCP_OWNER_PREFIX);
            }
        });
    }

    /**
     * 统一关闭应用级 Runtime 资源。顺序遵循“先停止生产者，再关闭依赖”：
     * 取消确认等待和模型生成 → 停止后台 SubAgent → 断开 MCP → 关闭终端。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        ConsoleInputRouter router = consoleInputRouter;
        consoleInputRouter = null;
        closeSafely("确认输入路由", () -> {
            if (router != null) router.cancelPending();
        });
        closeSafely("AI 生成", () -> {
            if (aiService != null) aiService.stopCurrentGeneration();
        });
        closeSafely("SubAgent 调度器", () -> {
            if (subAgentExecutor != null) subAgentExecutor.shutdown();
        });
        shutdownMCP();
        closeSafely("终端", () -> {
            if (ui != null) ui.close();
        });
    }

    public boolean isClosed() {
        return closed.get();
    }

    private static void closeSafely(String resourceName, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            System.err.println("⚠️  关闭" + resourceName + "失败: " + e.getMessage());
        }
    }

    // Getter方法
    public AppConfig getAppConfig() { return appConfig; }
    public MCPConfig getMcpConfig() { return mcpConfig; }
    public AIService getAiService() { return aiService; }
    public SessionService getSessionService() { return sessionService; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    // 🔥 新增 contextManager Getter
    public ContextManager getContextManager() { return contextManager; }
    public SubAgentExecutor getSubAgentExecutor() { return subAgentExecutor; }
    public ConsoleInputRouter getConsoleInputRouter() { return consoleInputRouter; }
    public void setConsoleInputRouter(ConsoleInputRouter router) { this.consoleInputRouter = router; }

    // 🔥 新增 memoryService Getter（可为 null = 记忆功能关闭）
    public MemoryService getMemoryService() { return memoryService; }
    public MemoryStore getMemoryStore() { return memoryStore; }
    public ThoughtCodingUI getUi() { return ui; }
    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }
    public HookRegistry getHookRegistry() { return hookRegistry; }

    // 🔥 新增 MCP 相关 Getter
    public MCPService getMcpService() { return mcpService; }
    public MCPToolManager getMcpToolManager() { return mcpToolManager; }
    public boolean isMCPEnabled() {
        return mcpConfig != null && mcpConfig.isEnabled();
    }
    public int getMCPToolCount() {
        return toolRegistry != null
                ? toolRegistry.countOwnersWithPrefix(MCP_OWNER_PREFIX)
                : 0;
    }

    private static String mcpOwner(String serverName) {
        return MCP_OWNER_PREFIX + serverName;
    }

    private static int registerMCPTools(ToolRegistry registry, String serverName,
                                        List<BaseTool> tools) {
        if (registry == null || serverName == null || tools == null) return 0;
        int registered = 0;
        for (BaseTool tool : tools) {
            if (registry.register(mcpOwner(serverName), tool)) {
                registered++;
            } else if (tool != null) {
                System.err.println("⚠️  跳过重名 MCP 工具 " + serverName + "/" + tool.getName()
                        + "，现有所有者: " + registry.ownerOf(tool.getName()));
            }
        }
        return registered;
    }

    // Builder模式
    public static class Builder {
        private AppConfig appConfig;
        private MCPConfig mcpConfig;
        private AIService aiService;
        private SessionService sessionService;
        private ToolRegistry toolRegistry;
        private ThoughtCodingUI ui;
        private PerformanceMonitor performanceMonitor;
        private HookRegistry hookRegistry;
        // 🔥 新增 MCP 字段
        private MCPService mcpService;
        private MCPToolManager mcpToolManager;
        // 🔥 新增上下文管理器字段
        private ContextManager contextManager;
        // 🔥 并行/后台子代理调度器
        private SubAgentExecutor subAgentExecutor;
        // 🔥 新增记忆系统字段
        private MemoryService memoryService;
        private MemoryStore memoryStore;

        public Builder appConfig(AppConfig appConfig) {
            this.appConfig = appConfig;
            return this;
        }

        public Builder mcpConfig(MCPConfig mcpConfig) {
            this.mcpConfig = mcpConfig;
            return this;
        }

        public Builder aiService(AIService aiService) {
            this.aiService = aiService;
            return this;
        }

        public Builder sessionService(SessionService sessionService) {
            this.sessionService = sessionService;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        public Builder ui(ThoughtCodingUI ui) {
            this.ui = ui;
            return this;
        }

        public Builder performanceMonitor(PerformanceMonitor performanceMonitor) {
            this.performanceMonitor = performanceMonitor;
            return this;
        }

        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        // 🔥 新增 MCP Builder 方法
        public Builder mcpService(MCPService mcpService) {
            this.mcpService = mcpService;
            return this;
        }

        public Builder mcpToolManager(MCPToolManager mcpToolManager) {
            this.mcpToolManager = mcpToolManager;
            return this;
        }

        // 🔥 新增 contextManager Builder 方法
        public Builder contextManager(ContextManager contextManager) {
            this.contextManager = contextManager;
            return this;
        }

        // 🔥 子代理调度器 Builder 方法
        public Builder subAgentExecutor(SubAgentExecutor subAgentExecutor) {
            this.subAgentExecutor = subAgentExecutor;
            return this;
        }
        // 🔥 新增 memoryService Builder 方法
        public Builder memoryService(MemoryService memoryService) {
            this.memoryService = memoryService;
            return this;
        }

        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = memoryStore;
            return this;
        }

        public ThoughtCodingContext build() {
            return new ThoughtCodingContext(this);
        }
    }
}
