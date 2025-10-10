// src/main/java/com/thoughtcoding/config/ConfigManager.java
package com.thoughtcoding.config;

import com.thoughtcoding.mcp.MCPService;

public class ConfigManager {
    private static ConfigManager instance;
    private AppConfig appConfig;
    private MCPService mcpService; // 添加 MCP 服务引用

    private ConfigManager() {
        // 私有构造函数
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    // 无 MCPService 的初始化方法
    public void initialize(String configPath) {
        ConfigLoader loader = new ConfigLoader(); // 使用无参构造函数
        this.appConfig = loader.loadConfig(configPath);
    }

    // 带 MCPService 的初始化方法（可选）
    public void initialize(String configPath, MCPService mcpService) {
        ConfigLoader loader = new ConfigLoader(mcpService);
        this.appConfig = loader.loadConfig(configPath);
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public void reloadConfig(String configPath) {
        initialize(configPath);
    }
}