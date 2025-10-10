package com.thoughtcoding.mcp;

import com.thoughtcoding.tools.BaseTool; // 改为 BaseTool
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class MCPToolManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MCPToolManager.class);
    // 预定义的常用MCP工具映射
    private static final Map<String, String> PREDEFINED_TOOLS = Map.of(
            "github-search", "npx @modelcontextprotocol/server-github",
            "sql-query", "npx @modelcontextprotocol/server-postgres",
            "file-system", "npx @modelcontextprotocol/server-filesystem",
            "web-search", "npx @modelcontextprotocol/server-brave-search",
            "calculator", "npx @modelcontextprotocol/server-calculator",
            "weather", "npx @modelcontextprotocol/server-weather",
            "memory", "npx @modelcontextprotocol/server-memory"
    );

    private final MCPService mcpService;
    private final Map<String, String> dynamicServers = new ConcurrentHashMap<>();

    public MCPToolManager(MCPService mcpService) {
        this.mcpService = mcpService;
    }

    public List<String> getPredefinedToolNames() {
        return new ArrayList<>(PREDEFINED_TOOLS.keySet());
    }

    public List<BaseTool> connectPredefinedTools(List<String> toolNames) {
        List<BaseTool> connectedTools = new ArrayList<>();

        for (String toolName : toolNames) {
            String command = PREDEFINED_TOOLS.get(toolName.trim());
            if (command != null) {
                String serverName = "predefined-" + toolName.trim();
                // 🔥 修改：使用空的 List 而不是 Map.of()
                List<BaseTool> tools = mcpService.connectToServer(serverName, command, Collections.emptyList());
                if (!tools.isEmpty()) {
                    connectedTools.addAll(tools);
                    dynamicServers.put(serverName, command);
                    log.info("成功连接预定义工具: {}", toolName);
                }
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