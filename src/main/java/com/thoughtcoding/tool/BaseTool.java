package com.thoughtcoding.tool;

import com.thoughtcoding.model.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * 工具的抽象基类，定义了工具的基本属性和行为
 *
 * <p><b>新增只读工具时注意：</b>若该工具的返回内容体积大、只是给模型用的（如读文件/查目录/加载技能正文），
 * 记得把工具名加进 {@code AgentLoop.QUIET_OUTPUT_TOOLS}，否则完整内容会 dump 到用户终端刷屏。
 * 该集合决定“结果只回喂模型、不在用户端显示”。（漏加不影响正确性，只是变啰嗦。）
 */
public abstract class BaseTool {
    protected final String name;
    protected final String description;

    public BaseTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public abstract ToolResult execute(String input);

    /**
     * 带取消令牌的执行入口（协作式取消传播）。
     * 默认忽略 token、直接委托 {@link #execute(String)}——老工具零改动；
     * 支持取消的工具（bash、subAgent 等）覆写本方法。
     */
    public ToolResult execute(String input, com.thoughtcoding.core.CancelToken token) {
        return execute(input);
    }

    /** Explicit execution ownership, propagated across dispatcher threads without global task state. */
    public ToolResult execute(String input, com.thoughtcoding.core.CancelToken token,
                              com.thoughtcoding.task.TaskService.Run task) {
        return execute(input, token);
    }

    public ToolResult execute(String input, com.thoughtcoding.core.CancelToken token,
                              com.thoughtcoding.task.TaskService.Run task, String invocationId) {
        return execute(input, token, task);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 🔥 获取输入参数的Schema定义（用于MCP工具）
     * 子类可以重写此方法以提供参数schema信息给AI
     */
    public Object getInputSchema() {
        return null; // 默认返回null，MCP工具可以重写
    }

    /**
     * 工具自带的参数 schema（内置工具覆写，与 name/description/execute 同处一个类）。
     * 返回 null 表示无自带 schema —— 例如 MCP 工具改用 {@link #getInputSchema()} 的原始 JSON schema。
     */
    public JsonObjectSchema inputSchema() {
        return null;
    }

    protected ToolResult success(String output) {
        return ToolResult.success(output, 0);
    }

    protected ToolResult error(String error) {
        return ToolResult.error(error, 0);
    }

    protected ToolResult success(String output, long executionTime) {
        return ToolResult.success(output, executionTime);
    }

    protected ToolResult error(String error, long executionTime) {
        return ToolResult.error(error, executionTime);
    }

    /** 展开路径中的 ~ 为用户主目录（~ 或 ~/xxx）。 */
    protected String expandUserHome(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    @Override
    public String toString() {
        return String.format("Tool{name=%s, description=%s}", name, description);
    }
}
