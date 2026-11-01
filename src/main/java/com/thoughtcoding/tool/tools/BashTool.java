package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.core.CancelledException;
import com.thoughtcoding.execution.CommandExecutor;
import com.thoughtcoding.execution.CommandExecutors;
import com.thoughtcoding.execution.ProcessRunner;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Shell tool; permission approval is upstream, OS isolation is selected by sandbox.mode. */
public class BashTool extends BaseTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_OUTPUT_CHARS = ProcessRunner.MAX_OUTPUT_CHARS;
    private final int defaultTimeoutSeconds;
    private final CommandExecutor executor;

    public BashTool(AppConfig config) {
        this(config, CommandExecutors.create(config.getSandbox()));
    }

    public BashTool(AppConfig config, CommandExecutor executor) {
        super("bash", "执行 " + (System.getProperty("os.name").toLowerCase().contains("win") ? "PowerShell" : "Shell")
                + " 命令，返回合并的 stdout/stderr。参数：command（必填）、timeout（可选，秒）。"
                + ("required".equals(config.getSandbox().getMode())
                ? "启用了 Shell 沙箱：文件和网络受策略限制，不能通过工具参数关闭；超时受宿主上限约束。" : ""));
        this.executor = Objects.requireNonNull(executor);
        Integer timeout = config.getTools().getBash().getTimeoutSeconds();
        defaultTimeoutSeconds = timeout == null || timeout <= 0 ? 60 : timeout;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("command", "要执行的 shell 命令")
                .addIntegerProperty("timeout", "超时秒数（可选；沙箱模式下受宿主上限约束）")
                .required("command").additionalProperties(false).build();
    }

    @Override
    public ToolResult execute(String input) { return execute(input, null); }

    @Override
    public ToolResult execute(String input, CancelToken token) {
        long start = System.currentTimeMillis();
        try {
            Map<?, ?> params = JSON.readValue(input, Map.class);
            Object rawCommand = params.get("command");
            if (!(rawCommand instanceof String command) || command.isBlank()) {
                return error("bash 需要 'command' 字段", elapsed(start));
            }
            int seconds = defaultTimeoutSeconds;
            if (params.get("timeout") instanceof Number timeout && timeout.longValue() > 0) {
                seconds = (int) Math.min(Integer.MAX_VALUE, timeout.longValue());
            }
            CommandExecutor.Result result = executor.execute(command, Sandbox.workspaceRoot(), seconds, token);
            String output = result.output();
            if (result.truncated()) output += "\n[输出已截断：仅保留前 " + MAX_OUTPUT_CHARS + " 字符]";
            if (result.exitCode() != 0) return error("命令退出码 " + result.exitCode() + ":\n" + output, elapsed(start));
            return success(output.isBlank() ? "命令执行成功（无输出）" : output, elapsed(start));
        } catch (TimeoutException e) {
            return error(e.getMessage() + "，已清理执行进程", elapsed(start));
        } catch (CancelledException e) {
            return error("命令已被用户取消", elapsed(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("命令执行已中断，已清理执行进程", elapsed(start));
        } catch (Exception e) {
            return error("命令执行失败: " + e.getMessage(), elapsed(start));
        }
    }

    private long elapsed(long start) { return System.currentTimeMillis() - start; }
}
