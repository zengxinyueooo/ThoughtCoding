package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.model.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP工具适配器，将MCPTool封装为BaseTool以便系统调用
 */
public class MCPToolAdapter extends BaseTool {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MCPToolAdapter.class);
    private final MCPTool mcpTool;
    private final MCPClient mcpClient;

    // 构造函数
    public MCPToolAdapter(MCPTool mcpTool, MCPClient mcpClient) {
        super("mcp:" + mcpClient.getServerName() + "/" + mcpTool.getName(),
                mcpTool.getDescription());
        this.mcpTool = mcpTool;
        this.mcpClient = mcpClient;
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("执行MCP工具: {} 输入: {}", getName(), input);

            // 🔥 修复：正确解析JSON参数
            Map<String, Object> arguments = parseInputToArguments(input);

            Object result = mcpClient.callTool(mcpTool.getName(), arguments);

            long executionTime = System.currentTimeMillis() - startTime;

            if (result != null) {
                log.debug("MCP工具执行成功: {} 耗时: {}ms", getName(), executionTime);
                return success(result.toString(), executionTime);
            } else {
                log.debug("MCP工具执行完成(无返回): {} 耗时: {}ms", getName(), executionTime);
                return success("执行成功", executionTime);
            }

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("MCP工具执行失败: {} 错误: {} 耗时: {}ms", getName(), e.getMessage(), executionTime);
            return error("MCP工具执行失败: " + e.getMessage(), executionTime);
        }
    }

    /**
     * 🔥 解析输入字符串为参数Map
     * 如果输入是JSON格式，直接解析；否则作为单个参数
     */
    private Map<String, Object> parseInputToArguments(String input) {
        Map<String, Object> arguments = new HashMap<>();

        if (input == null || input.trim().isEmpty()) {
            return arguments;
        }

        // 尝试解析JSON
        if (input.trim().startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.readValue(input, Map.class);
            } catch (Exception e) {
                log.debug("输入不是有效JSON，使用默认解析: {}", e.getMessage());
            }
        }

        // 如果不是JSON，将整个输入作为单个参数
        arguments.put("input", input);
        return arguments;
    }

    @Override
    public String getCategory() {
        return "mcp";
    }

    @Override
    public boolean isEnabled() {
        return mcpClient != null && mcpClient.isConnected();
    }

    /**
     * 获取原始MCP工具信息
     */
    public MCPTool getOriginalTool() {
        return mcpTool;
    }

    /**
     * 获取关联的MCP客户端
     */
    public MCPClient getMCPClient() {
        return mcpClient;
    }
}