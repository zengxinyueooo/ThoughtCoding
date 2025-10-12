package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.config.ConfigManager;
import com.thoughtcoding.mcp.MCPService;
import com.thoughtcoding.mcp.MCPToolManager;
import com.thoughtcoding.service.AIService;
import com.thoughtcoding.service.LangChainService;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.service.SessionService;
import com.thoughtcoding.tools.*;
import com.thoughtcoding.tools.exec.CodeExecutorTool;
import com.thoughtcoding.tools.exec.CommandExecutorTool;
import com.thoughtcoding.tools.file.FileManagerTool;
import com.thoughtcoding.tools.search.GrepSearchTool;
import com.thoughtcoding.ui.ThoughtCodingUI;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文初始化过程
 *
 * 依赖注入：确保各个组件都能获取到它们需要的依赖
 *
 * 生命周期管理：控制初始化顺序，避免循环依赖
 *
 * 资源配置：建立数据库连接、网络连接、文件句柄等
 */
public class ThoughtCodingContext {
    private final AppConfig appConfig;
    private final AIService aiService;
    private final SessionService sessionService;
    private final ToolRegistry toolRegistry;
    private final ThoughtCodingUI ui;
    private final PerformanceMonitor performanceMonitor;

    // 🔥 新增 MCP 相关服务
    private final MCPService mcpService;
    private final MCPToolManager mcpToolManager;

    private ThoughtCodingContext(Builder builder) {
        this.appConfig = builder.appConfig;
        this.aiService = builder.aiService;
        this.sessionService = builder.sessionService;
        this.toolRegistry = builder.toolRegistry;
        this.ui = builder.ui;
        this.performanceMonitor = builder.performanceMonitor;
        this.mcpService = builder.mcpService;
        this.mcpToolManager = builder.mcpToolManager;
    }

    public static ThoughtCodingContext initialize() {
        // 分层初始化，确保依赖顺序正确

        // 初始化配置管理器
        ConfigManager configManager = ConfigManager.getInstance();
        configManager.initialize("config.yaml");
        AppConfig appConfig = configManager.getAppConfig();

        // 能力层初始化,创建工具注册表
        ToolRegistry toolRegistry = new ToolRegistry(appConfig);

        // 🔥 创建 MCP 服务
        MCPService mcpService = new MCPService(toolRegistry);
        MCPToolManager mcpToolManager = new MCPToolManager(mcpService);

        // 注册内置工具 - 传递整个 AppConfig 对象
        if (appConfig.getTools().getFileManager().isEnabled()) {
            toolRegistry.register(new FileManagerTool(appConfig));
        }

        if (appConfig.getTools().getCommandExec().isEnabled()) {
            toolRegistry.register(new CommandExecutorTool(appConfig));
        }

        if (appConfig.getTools().getCodeExecutor().isEnabled()) {
            toolRegistry.register(new CodeExecutorTool(appConfig));
        }

        if (appConfig.getTools().getSearch().isEnabled()) {
            toolRegistry.register(new GrepSearchTool(appConfig));
        }

        // 🔥 初始化 MCP 服务（如果启用）
        if (appConfig.getMcp() != null && appConfig.getMcp().isEnabled()) {
            initializeMCPTools(appConfig, mcpService);
        }

        // 服务层初始化
        AIService aiService = new LangChainService(appConfig, toolRegistry);
        SessionService sessionService = new SessionService();
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();

        // UI层初始化
        ThoughtCodingUI ui = new ThoughtCodingUI();

        // 构建上下文（核心层初始化）
        return new Builder()
                .appConfig(appConfig)
                .aiService(aiService)
                .sessionService(sessionService)
                .toolRegistry(toolRegistry)
                .ui(ui)
                .performanceMonitor(performanceMonitor)
                .mcpService(mcpService)
                .mcpToolManager(mcpToolManager)
                .build();
    }

    /**
     * 🔥 初始化 MCP 工具
     */
    public static void initializeMCPTools(AppConfig appConfig, MCPService mcpService) {
        System.out.println("初始化 MCP 工具...");

        var mcpConfig = appConfig.getMcp();
        if (mcpConfig != null && mcpConfig.isEnabled()) {
            for (var serverConfig : mcpConfig.getServers()) {
                if (serverConfig.isEnabled()) {
                    try {
                        // 🔥 直接传递命令和参数列表，不再创建 Map
                        var tools = mcpService.connectToServer(
                                serverConfig.getName(),
                                serverConfig.getCommand(),  // 直接传递命令
                                serverConfig.getArgs()      // 直接传递参数列表
                        );
                        if (!tools.isEmpty()) {
                            System.out.println("✓ MCP 服务器 " + serverConfig.getName() +
                                    " 初始化成功 (" + tools.size() + " 个工具)");
                        } else {
                            System.out.println("⚠ MCP 服务器 " + serverConfig.getName() +
                                    " 初始化失败或未发现工具");
                        }
                    } catch (Exception e) {
                        System.err.println("✗ MCP 服务器 " + serverConfig.getName() +
                                " 初始化异常: " + e.getMessage());
                    }
                }
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
            // 🔥 直接传递三个参数，不再创建 Map
            var tools = mcpService.connectToServer(serverName, command, args);
            if (!tools.isEmpty()) {
                System.out.println("✓ 成功连接 MCP 服务器: " + serverName +
                        " (" + tools.size() + " 个工具)");
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
            var tools = mcpToolManager.connectPredefinedTools(toolNames);
            System.out.println("✓ 已连接 " + tools.size() + " 个预定义 MCP 工具");
            return !tools.isEmpty();
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
        if (mcpService != null) {
            mcpService.shutdown();
        }
        if (mcpToolManager != null) {
            mcpToolManager.shutdown();
        }
        System.out.println("MCP 服务已关闭");
    }

    // Getter方法
    public AppConfig getAppConfig() { return appConfig; }
    public AIService getAiService() { return aiService; }
    public SessionService getSessionService() { return sessionService; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public ThoughtCodingUI getUi() { return ui; }
    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }

    // 🔥 新增 MCP 相关 Getter
    public MCPService getMcpService() { return mcpService; }
    public MCPToolManager getMcpToolManager() { return mcpToolManager; }
    public boolean isMCPEnabled() {
        return appConfig.getMcp() != null && appConfig.getMcp().isEnabled();
    }
    public int getMCPToolCount() {
        return mcpService != null ? mcpService.getMCPTools().size() : 0;
    }

    // Builder模式
    public static class Builder {
        private AppConfig appConfig;
        private AIService aiService;
        private SessionService sessionService;
        private ToolRegistry toolRegistry;
        private ThoughtCodingUI ui;
        private PerformanceMonitor performanceMonitor;
        // 🔥 新增 MCP 字段
        private MCPService mcpService;
        private MCPToolManager mcpToolManager;

        public Builder appConfig(AppConfig appConfig) {
            this.appConfig = appConfig;
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

        // 🔥 新增 MCP Builder 方法
        public Builder mcpService(MCPService mcpService) {
            this.mcpService = mcpService;
            return this;
        }

        public Builder mcpToolManager(MCPToolManager mcpToolManager) {
            this.mcpToolManager = mcpToolManager;
            return this;
        }

        public ThoughtCodingContext build() {
            return new ThoughtCodingContext(this);
        }
    }
}