package com.thoughtcoding.tools;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.tools.exec.CodeExecutorTool;
import com.thoughtcoding.tools.exec.CommandExecutorTool;
import com.thoughtcoding.tools.file.FileManagerTool;
import com.thoughtcoding.tools.search.GrepSearchTool;

import java.util.*;

/**
 * 负责工具注册、发现和生命周期管理
 *
 * 工具系统的核心管理者，维护了所有可用工具的映射表，并提供统一的调用接口，
 */
public class ToolRegistry implements ToolProvider {
    private final Map<String, BaseTool> tools;
    private final AppConfig appConfig;

    public ToolRegistry(AppConfig appConfig) {
        this.tools = new HashMap<>();
        this.appConfig = appConfig;
    }

    @Override
    public void registerTool(BaseTool tool) {
        if (isToolEnabled(tool.getName())) {
            tools.put(tool.getName(), tool);
        }
    }

    // 🔥 通用的 register 方法，接受 BaseTool（用于 MCP 工具）
    public void register(BaseTool tool) {
        registerTool(tool);
    }

    // 为每种工具类型添加对应的 register 方法（保持向后兼容）
    public void register(FileManagerTool tool) {
        registerTool(tool);
    }

    public void register(CommandExecutorTool tool) {
        registerTool(tool);
    }

    public void register(CodeExecutorTool tool) {
        registerTool(tool);
    }

    public void register(GrepSearchTool tool) {
        registerTool(tool);
    }

    @Override
    public BaseTool getTool(String toolName) {
        return tools.get(toolName);
    }

    @Override
    public boolean isToolAvailable(String toolName) {
        return tools.containsKey(toolName) && isToolEnabled(toolName);
    }

    public List<BaseTool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public Set<String> getAvailableToolNames() {
        return tools.keySet();
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    //内置工具直接实例化注册
    private boolean isToolEnabled(String toolName) {
        // 检查配置中是否启用了该工具
        if (appConfig == null || appConfig.getTools() == null) {
            return true; // 默认启用
        }

        switch (toolName) {
            case "file_manager":
                return appConfig.getTools().getFileManager().isEnabled();
            case "command_executor":
                return appConfig.getTools().getCommandExec().isEnabled();
            case "code_executor":
                return appConfig.getTools().getCodeExecutor().isEnabled();
            case "grep_search":
                return appConfig.getTools().getSearch().isEnabled();
            default:
                return true;
        }
    }
}