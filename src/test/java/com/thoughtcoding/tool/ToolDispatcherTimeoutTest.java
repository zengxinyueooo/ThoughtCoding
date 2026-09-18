package com.thoughtcoding.tool;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管线层兜底超时与取消中断的单元测试。
 *
 * <p>铁律：任何工具卡死（无自保超时的文件 I/O、网络盘等）都不能挂死回合——
 * Dispatcher 必须在超时后收敛为可回喂模型的 error ToolResult；
 * 用户 stop 也必须能中断运行中的工具。
 */
class ToolDispatcherTimeoutTest {

    /** 永远执行不完的假工具（sleep 不可中断场景的替身）。 */
    private static class HangingTool extends BaseTool {
        volatile boolean interrupted = false;

        HangingTool() {
            super("hang", "测试用挂死工具");
        }

        @Override
        public ToolResult execute(String input) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("不应到达", 0);
        }

        @Override
        public JsonObjectSchema inputSchema() {
            return null;
        }
    }

    /** 正常完成的假工具。 */
    private static class OkTool extends BaseTool {
        OkTool() {
            super("ok", "测试用正常工具");
        }

        @Override
        public ToolResult execute(String input) {
            return ToolResult.success("done", 1);
        }

        @Override
        public JsonObjectSchema inputSchema() {
            return null;
        }
    }

    private static ToolCall call(String name) {
        return new ToolCall(name, new HashMap<>(), null, false, 0, false, "id-" + name);
    }

    @Test
    void 挂死工具在超时后收敛为error且不挂死调用方() {
        ToolRegistry registry = new ToolRegistry(new AppConfig());
        registry.register(new HangingTool());
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 300); // 300ms 注入超时

        long start = System.currentTimeMillis();
        ToolResult result = dispatcher.dispatch(call("hang"));
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("超时"), "错误应说明超时: " + result.getError());
        assertTrue(elapsed < 10_000, "应在超时后立即返回，而不是等工具跑完。实际: " + elapsed + "ms");
    }

    @Test
    void 正常工具不受超时影响() {
        ToolRegistry registry = new ToolRegistry(new AppConfig());
        registry.register(new OkTool());
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 5_000);

        ToolResult result = dispatcher.dispatch(call("ok"));
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("done"));
    }

    @Test
    void 用户取消能中断运行中的工具() throws Exception {
        ToolRegistry registry = new ToolRegistry(new AppConfig());
        HangingTool hanging = new HangingTool();
        registry.register(hanging);
        ToolDispatcher dispatcher = new ToolDispatcher(registry, 60_000);

        CancelToken token = new CancelToken();
        Thread runner = new Thread(() -> dispatcher.dispatch(call("hang"), token));
        runner.start();
        Thread.sleep(150); // 让工具先跑起来
        token.cancel();
        runner.join(5_000);

        assertFalse(runner.isAlive(), "取消后 dispatch 应 promptly 返回");
        assertTrue(hanging.interrupted, "工具线程应收到中断");
    }
}
