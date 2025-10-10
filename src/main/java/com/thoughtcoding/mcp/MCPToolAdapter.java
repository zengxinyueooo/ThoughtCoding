package com.thoughtcoding.mcp;

import com.thoughtcoding.mcp.model.MCPTool;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.model.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;


public class MCPToolAdapter extends BaseTool {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MCPToolAdapter.class);
    private final MCPTool mcpTool;
    private final MCPClient mcpClient;

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

            Map<String, Object> arguments = new HashMap<>();
            arguments.put("input", input);

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