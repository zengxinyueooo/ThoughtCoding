// src/main/java/com/thoughtcoding/core/ThoughtCodingContext.java
package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.config.ConfigManager;
import com.thoughtcoding.service.AIService;

import com.thoughtcoding.service.LangChainService;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.service.SessionService;

import com.thoughtcoding.tools.ToolRegistry;

import com.thoughtcoding.tools.exec.CodeExecutorTool;
import com.thoughtcoding.tools.exec.CommandExecutorTool;
import com.thoughtcoding.tools.file.FileManagerTool;
import com.thoughtcoding.tools.search.GrepSearchTool;
import com.thoughtcoding.ui.ThoughtCodingUI;


public class ThoughtCodingContext {
    private final AppConfig appConfig;
    private final AIService aiService;
    private final SessionService sessionService;
    private final ToolRegistry toolRegistry;
    private final ThoughtCodingUI ui;
    private final PerformanceMonitor performanceMonitor;

    private ThoughtCodingContext(Builder builder) {
        this.appConfig = builder.appConfig;
        this.aiService = builder.aiService;
        this.sessionService = builder.sessionService;
        this.toolRegistry = builder.toolRegistry;
        this.ui = builder.ui;
        this.performanceMonitor = builder.performanceMonitor;
    }

    public static ThoughtCodingContext initialize() {
        // 初始化配置管理器
        ConfigManager configManager = ConfigManager.getInstance();
        configManager.initialize("config.yaml");
        AppConfig appConfig = configManager.getAppConfig();

        // 创建工具注册表
        ToolRegistry toolRegistry = new ToolRegistry(appConfig);

        // 注册工具 - 传递整个 AppConfig 对象
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

        // 创建服务
        AIService aiService = new LangChainService(appConfig, toolRegistry);
        SessionService sessionService = new SessionService();
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();

        // 创建UI
        ThoughtCodingUI ui = new ThoughtCodingUI();

        // 构建上下文
        return new Builder()
                .appConfig(appConfig)
                .aiService(aiService)
                .sessionService(sessionService)
                .toolRegistry(toolRegistry)
                .ui(ui)
                .performanceMonitor(performanceMonitor)
                .build();
    }
    // Getter方法
    public AppConfig getAppConfig() { return appConfig; }
    public AIService getAiService() { return aiService; }
    public SessionService getSessionService() { return sessionService; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public ThoughtCodingUI getUi() { return ui; }
    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }

    // Builder模式
    public static class Builder {
        private AppConfig appConfig;
        private AIService aiService;
        private SessionService sessionService;
        private ToolRegistry toolRegistry;
        private ThoughtCodingUI ui;
        private PerformanceMonitor performanceMonitor;

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

        public ThoughtCodingContext build() {
            return new ThoughtCodingContext(this);
        }
    }
}