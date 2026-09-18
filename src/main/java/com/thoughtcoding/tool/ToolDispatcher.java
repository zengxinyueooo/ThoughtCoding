package com.thoughtcoding.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.core.CancelledException;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行的唯一收口。
 *
 * <p>原生 function calling 与 MCP 工具都通过 {@link #dispatch(ToolCall)} 执行。
 * Dispatcher 负责把查找、参数序列化及工具运行时异常统一收敛为非空 {@link ToolResult}，
 * 保证调用方总能为 provider tool call 写入配对结果。
 * 权限检查发生在 Dispatcher 之前，不由本类负责。
 *
 * <p><b>超时与取消兜底</b>：工具执行统一放到虚拟线程上跑，管线层施加兜底超时
 * （{@link #DEFAULT_TOOL_TIMEOUT_MS}）与取消中断。自带更短超时的工具（bash/MCP/git）
 * 以工具自身为准——本层超时只兜「工具自己卡死」的场景（如文件 I/O 挂在网络盘上），
 * 保证回合永远不会被单个工具无限挂起，且 {@code stop} 对所有工具生效
 * （此前 read/write/edit 等文件工具不响应取消令牌）。
 * 超时/取消后工具线程被中断，阻塞中的 I/O 尽量退出；无法中断的线程任其消亡，
 * 绝不拖住调用方。
 */
public class ToolDispatcher {

    /** 管线层兜底超时：大于 bash(60s)/MCP(30s) 等工具自管超时，只兜无自保能力的工具。 */
    static final long DEFAULT_TOOL_TIMEOUT_MS = 120_000;

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long timeoutMs;
    // 虚拟线程 per-task；虚拟线程均为 daemon，不阻止 JVM 退出，无需显式关闭
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolDispatcher(ToolRegistry registry) {
        this(registry, DEFAULT_TOOL_TIMEOUT_MS);
    }

    /** 测试可注入更短的兜底超时。 */
    ToolDispatcher(ToolRegistry registry, long timeoutMs) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.timeoutMs = timeoutMs;
    }

    /**
     * 执行一个工具调用：查表 → 序列化参数 → 执行（无取消令牌，等价于不可取消）。
     */
    public ToolResult dispatch(ToolCall call) {
        return dispatch(call, null);
    }

    /**
     * 执行一个工具调用：查表 → 序列化参数 → 虚拟线程上执行，带兜底超时与取消中断。
     * token 为 null 表示本调用不可取消（但超时兜底仍然生效）。
     */
    public ToolResult dispatch(ToolCall call, CancelToken token) {
        long start = System.currentTimeMillis();
        if (call == null) {
            return ToolResult.error("无效的工具调用：call 不能为空。", elapsedSince(start));
        }

        String toolName = call.getToolName();
        if (toolName == null || toolName.isBlank()) {
            return ToolResult.error("无效的工具调用：toolName 不能为空。", elapsedSince(start));
        }

        BaseTool tool = registry.getTool(toolName);
        if (tool == null) {
            return ToolResult.error("Tool not found: " + toolName, elapsedSince(start));
        }

        String argsJson;
        try {
            argsJson = toJson(call.getParameters());
        } catch (Exception e) {
            return ToolResult.error("工具参数序列化失败 [" + toolName + "]: " + exceptionMessage(e),
                    elapsedSince(start));
        }

        // 用 ExecutorService.submit（FutureTask）而非 CompletableFuture：
        // 后者的 cancel(true) 不会中断运行线程，取消语义是假的。
        Future<ToolResult> future = toolExecutor.submit(() -> tool.execute(argsJson, token));
        if (token != null) {
            // 取消传播：用户 stop 时中断工具线程（对已完成的 future 是 no-op，回调残留无害）
            token.onCancel(() -> future.cancel(true));
        }

        try {
            ToolResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null) {
                return ToolResult.error("工具返回了空结果 [" + toolName + "]。", elapsedSince(start));
            }
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.error("工具执行超时 [" + toolName + "]（超过 "
                            + (timeoutMs / 1000) + "s，已中断）。"
                            + "若任务本身耗时长，请拆分为更小的步骤分批执行。",
                    elapsedSince(start));
        } catch (CancellationException e) {
            return ToolResult.error("工具执行已取消 [" + toolName + "]。", elapsedSince(start));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CancelledException cancelled) {
                return ToolResult.error("工具执行已取消 [" + toolName + "]: " + exceptionMessage(cancelled),
                        elapsedSince(start));
            }
            return ToolResult.error("工具执行异常 [" + toolName + "]: " + exceptionMessageOf(cause),
                    elapsedSince(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.error("工具执行被中断 [" + toolName + "]。", elapsedSince(start));
        }
    }

    /** 把参数 Map 序列化为 JSON 字符串（各工具的 execute(String) 统一按 JSON 解析）。 */
    private String toJson(Map<String, Object> parameters) throws Exception {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        return mapper.writeValueAsString(parameters);
    }

    private long elapsedSince(long start) {
        return System.currentTimeMillis() - start;
    }

    private String exceptionMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private String exceptionMessageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }
}
