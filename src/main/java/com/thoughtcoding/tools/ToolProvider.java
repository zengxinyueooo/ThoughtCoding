package com.thoughtcoding.tools;

/**
 * 工具提供者接口，定义了获取和注册工具的方法
 */
public interface ToolProvider {
    BaseTool getTool(String toolName);
    void registerTool(BaseTool tool);
    boolean isToolAvailable(String toolName);
}