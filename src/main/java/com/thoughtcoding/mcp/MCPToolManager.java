package com.thoughtcoding.mcp;

import com.thoughtcoding.config.MCPConfig;
import com.thoughtcoding.config.MCPServerConfig;
import com.thoughtcoding.tools.BaseTool;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * MCP工具管理器，负责管理预定义和自定义的MCP工具连接
 */
public class MCPToolManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MCPToolManager.class);

    private final MCPService mcpService;
    private final MCPConfig mcpConfig;
    private final Map<String, String> dynamicServers = new ConcurrentHashMap<>();

    public MCPToolManager(MCPService mcpService, MCPConfig mcpConfig) {
        this.mcpService = mcpService;
        this.mcpConfig = mcpConfig;
    }

    /**
     * 🔥 从配置文件获取预定义工具的配置
     * 优先使用配置文件，如果没有则使用默认配置
     */
    private Map<String, List<String>> getPredefinedToolConfig(String toolName) {
        Map<String, List<String>> config = new HashMap<>();

        // 🔥 优先从配置文件读取
        var serverConfig = mcpConfig.getServerConfig(toolName);

        if (serverConfig != null && serverConfig.getArgs() != null && !serverConfig.getArgs().isEmpty()) {
            // 使用配置文件中的配置
            config.put("command", List.of(serverConfig.getCommand()));
            config.put("args", new ArrayList<>(serverConfig.getArgs()));
            log.info("✓ 使用配置文件中的 {} 配置", toolName);
            return config;
        }

        // 🔥 如果配置文件中没有，使用默认配置（向后兼容）
        log.warn("⚠ 配置文件中未找到 {} 配置，使用默认配置", toolName);

        switch (toolName.toLowerCase().trim()) {
            case "github-search":
            case "github":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@modelcontextprotocol/server-github",
                    "--token",
                    "your_github_token_here"  // 占位符
                ));
                break;

            case "file-system":
            case "filesystem":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@modelcontextprotocol/server-filesystem",
                    System.getProperty("user.home")  // 用户主目录
                ));
                break;

            case "sql-query":
            case "postgres":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@modelcontextprotocol/server-postgres",
                    "--connectionString",
                    "postgresql://user:pass@localhost:5432/db"
                ));
                break;

            case "sqlite":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@modelcontextprotocol/server-sqlite",
                    "--database",
                    "./data.db"
                ));
                break;

            case "web-search":
            case "brave-search":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@modelcontextprotocol/server-brave-search",
                    "--apiKey",
                    "your_brave_api_key"
                ));
                break;

            case "calculator":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@modelcontextprotocol/server-calculator"
                ));
                break;

            case "weather":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@coding-squirrel/mcp-weather-server",
                    "--apiKey",
                    "your_weather_api_key"
                ));
                break;

            case "memory":
                config.put("command", List.of("npx"));
                config.put("args", Arrays.asList(
                    "-y",
                    "@modelcontextprotocol/server-memory"
                ));
                break;

            default:
                return null;
        }

        return config;
    }

    public List<String> getPredefinedToolNames() {
        return Arrays.asList(
            "github-search", "file-system", "sql-query", "sqlite",
            "web-search", "calculator", "weather", "memory"
        );
    }

    public List<BaseTool> connectPredefinedTools(List<String> toolNames) {
        List<BaseTool> connectedTools = new ArrayList<>();

        for (String toolName : toolNames) {
            String trimmedName = toolName.trim();
            Map<String, List<String>> config = getPredefinedToolConfig(trimmedName);

            if (config != null) {
                String serverName = "predefined-" + trimmedName;
                String command = config.get("command").get(0);
                List<String> args = config.get("args");

                log.info("正在连接预定义工具: {} (命令: {}, 参数: {})", trimmedName, command, args);

                List<BaseTool> tools = mcpService.connectToServer(serverName, command, args);
                if (!tools.isEmpty()) {
                    connectedTools.addAll(tools);
                    dynamicServers.put(serverName, command);
                    log.info("✅ 成功连接预定义工具: {} ({} 个工具)", trimmedName, tools.size());
                } else {
                    log.warn("⚠️ 连接预定义工具失败: {}", trimmedName);
                }
            } else {
                log.warn("⚠️ 未知的预定义工具: {}", trimmedName);
            }
        }

        return connectedTools;
    }

    public List<BaseTool> connectCustomServer(String serverName, String command, List<String> args) { // 改为 BaseTool
        List<BaseTool> tools = mcpService.connectToServer(serverName, command, args);
        if (!tools.isEmpty()) {
            dynamicServers.put(serverName, command);
        }
        return tools;
    }

    public void disconnectServer(String serverName) {
        mcpService.disconnectServer(serverName);
        dynamicServers.remove(serverName);
    }

    public Map<String, String> getConnectedServers() {
        return new HashMap<>(dynamicServers);
    }

    public void shutdown() {
        new ArrayList<>(dynamicServers.keySet()).forEach(this::disconnectServer);
    }
}