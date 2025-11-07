package com.thoughtcoding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thoughtcoding.mcp.MCPService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 配置管理单例类，负责加载和提供应用配置
 */
public class ConfigManager {
    private static ConfigManager instance;
    private AppConfig appConfig;
    private MCPConfig mcpConfig;
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
        this.mcpConfig = loadMCPConfig(configPath);
    }

    // 带 MCPService 的初始化方法（可选）
    public void initialize(String configPath, MCPService mcpService, MCPConfig mcpConfig) {
        ConfigLoader loader = new ConfigLoader(mcpService, mcpConfig);
        this.appConfig = loader.loadConfig(configPath);
        this.mcpConfig = mcpConfig;
    }

    /**
     * 加载独立的 MCP 配置
     */
    private MCPConfig loadMCPConfig(String configPath) {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

            // 尝试从文件系统加载
            Path filePath = Paths.get(configPath);
            if (Files.exists(filePath)) {
                // 读取整个配置文件，但只提取 mcp 部分
                var rootNode = mapper.readTree(filePath.toFile());
                if (rootNode.has("mcp")) {
                    return mapper.treeToValue(rootNode.get("mcp"), MCPConfig.class);
                }
            }

            // 尝试从类路径加载
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(configPath);
            if (inputStream != null) {
                var rootNode = mapper.readTree(inputStream);
                if (rootNode.has("mcp")) {
                    return mapper.treeToValue(rootNode.get("mcp"), MCPConfig.class);
                }
            }

            // 返回默认配置
            return new MCPConfig();

        } catch (Exception e) {
            System.err.println("加载 MCP 配置失败: " + e.getMessage());
            return new MCPConfig();
        }
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public MCPConfig getMCPConfig() {
        if (mcpConfig == null) {
            mcpConfig = new MCPConfig();
        }
        return mcpConfig;
    }

    public void reloadConfig(String configPath) {
        initialize(configPath);
    }
}