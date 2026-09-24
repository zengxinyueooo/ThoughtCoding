package com.thoughtcoding.hook;

import com.thoughtcoding.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部命令 Hook 的单元测试。
 *
 * <p>用 {@code exit 0} / {@code exit 2} / {@code echo} 三条在 cmd 与 sh 下行为一致的命令，
 * 验证退出码语义（0 放行、2 阻断/STOP 续跑、其他降级放行）、matcher 过滤与 stdout 注入。
 */
class ExternalCommandHookTest {

    private static ToolCall tool(String name) {
        return new ToolCall(name, new HashMap<>(), null, false, 0, false, "call-1");
    }

    private static HookContext preTool(ToolCall call) {
        return HookContext.forPreTool(null, List.of(), call);
    }

    @Test
    void 退出码0放行() {
        ExternalCommandHook hook = new ExternalCommandHook("exit 0", null, 10);

        assertEquals(HookResult.Decision.PROCEED, hook.execute(preTool(tool("bash"))).decision());
    }

    @Test
    void 退出码2阻断且携带原因() {
        ExternalCommandHook hook = new ExternalCommandHook("exit 2", null, 10);
        HookResult result = hook.execute(preTool(tool("bash")));

        assertTrue(result.isBlocked());
        assertTrue(result.message().contains("exit 2"));
    }

    @Test
    void 退出码2在STOP时机转为续跑() {
        ExternalCommandHook hook = new ExternalCommandHook("exit 2", null, 10);
        HookResult result = hook.execute(HookContext.forStop(null, List.of()));

        assertTrue(result.isContinueLoop());
    }

    @Test
    void 其他退出码降级放行() {
        ExternalCommandHook hook = new ExternalCommandHook("exit 7", null, 10);

        assertEquals(HookResult.Decision.PROCEED, hook.execute(preTool(tool("bash"))).decision());
    }

    @Test
    void matcher不匹配时不执行命令() {
        // matcher 只放行 read：若命令真的执行会 exit 2 阻断——断言 PROCEED 即证明未执行
        ExternalCommandHook hook = new ExternalCommandHook("exit 2", "read", 10);

        assertEquals(HookResult.Decision.PROCEED, hook.execute(preTool(tool("bash"))).decision());
        // 命中 matcher 的工具仍会阻断
        assertTrue(hook.execute(preTool(tool("read"))).isBlocked());
    }

    @Test
    void 非工具事件无视matcher恒执行() {
        ExternalCommandHook hook = new ExternalCommandHook("exit 2", "bash", 10);

        assertTrue(hook.execute(HookContext.forStop(null, List.of())).isContinueLoop());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void UserPromptSubmit的stdout注入模型上下文() {
        ExternalCommandHook hook = new ExternalCommandHook("echo hook-context-here", null, 10);
        HookContext context = HookContext.forUserPrompt(null, List.of(), "用户原始输入");

        assertEquals(HookResult.Decision.PROCEED, hook.execute(context).decision());
        String promptForModel = context.buildPromptForModel();
        assertTrue(promptForModel.contains("用户原始输入"));
        assertTrue(promptForModel.contains("hook-context-here"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 非UserPromptSubmit的stdout不进入上下文() {
        ExternalCommandHook hook = new ExternalCommandHook("echo should-not-inject", null, 10);
        HookContext context = preTool(tool("bash"));

        assertEquals(HookResult.Decision.PROCEED, hook.execute(context).decision());
        assertFalse(context.buildPromptForModel().contains("should-not-inject"));
    }
}
