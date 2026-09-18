package com.thoughtcoding.core;

import com.thoughtcoding.hook.DuplicateToolCallGuard;
import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.hook.HookResult;
import com.thoughtcoding.hook.HookType;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionPipelineTest {

    @Test
    void shouldRunPreThenPostHookAndAppendSinglePairedResultOnSuccess() {
        List<String> events = new ArrayList<>();
        ToolRegistry tools = registryWith("echo", input -> {
            events.add("tool");
            return ToolResult.success("完成", 1);
        });
        HookRegistry hooks = new HookRegistry()
                .register(HookType.PRE_TOOL_USE, context -> {
                    events.add("pre");
                    return HookResult.proceed();
                })
                .register(HookType.POST_TOOL_USE, context -> {
                    events.add("post");
                    assertTrue(context.getToolResult().isSuccess());
                    return HookResult.proceed();
                });
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(null, hooks, tools);
        List<ChatMessage> history = new ArrayList<>();

        ToolExecutionPipeline.Outcome outcome =
                pipeline.executeAndRecord(call("echo", "call-1"), null, history);

        assertFalse(outcome.isBlocked());
        assertEquals(List.of("pre", "tool", "post"), events);
        assertEquals(1, history.size());
        assertEquals("call-1", history.getFirst().getToolCallId());
        // 工具输出统一包 <tool_output>（提示注入缓解），正文保留
        assertEquals("<tool_output tool=\"echo\">\n完成\n</tool_output>",
                history.getFirst().getContent());
    }

    @Test
    void shouldSkipToolAndPostHookButAppendPairedResultWhenPreHookBlocks() {
        AtomicInteger toolExecutions = new AtomicInteger();
        AtomicInteger postExecutions = new AtomicInteger();
        ToolRegistry tools = registryWith("write", input -> {
            toolExecutions.incrementAndGet();
            return ToolResult.success("unexpected", 0);
        });
        HookRegistry hooks = new HookRegistry()
                .register(HookType.PRE_TOOL_USE,
                        context -> HookResult.block("策略拒绝"))
                .register(HookType.POST_TOOL_USE, context -> {
                    postExecutions.incrementAndGet();
                    return HookResult.proceed();
                });
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(null, hooks, tools);
        List<ChatMessage> history = new ArrayList<>();

        ToolExecutionPipeline.Outcome outcome =
                pipeline.executeAndRecord(call("write", "call-2"), null, history);

        assertTrue(outcome.isBlocked());
        assertEquals(0, toolExecutions.get());
        assertEquals(0, postExecutions.get());
        assertEquals(1, history.size());
        assertTrue(history.getFirst().getContent().contains("策略拒绝"),
                "阻断原因应保留原文（可包标注）: " + history.getFirst().getContent());
    }

    @Test
    void shouldRunPostHookAndReturnNormalizedResultWhenToolFails() {
        AtomicInteger postExecutions = new AtomicInteger();
        ToolRegistry tools = registryWith("bash",
                input -> ToolResult.error("退出码 1", 2));
        HookRegistry hooks = new HookRegistry()
                .register(HookType.POST_TOOL_USE, context -> {
                    postExecutions.incrementAndGet();
                    assertFalse(context.getToolResult().isSuccess());
                    return HookResult.proceed();
                });
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(null, hooks, tools);
        List<ChatMessage> history = new ArrayList<>();

        ToolExecutionPipeline.Outcome outcome =
                pipeline.executeAndRecord(call("bash", "call-3"), null, history);

        assertFalse(outcome.result().isSuccess());
        assertEquals(1, postExecutions.get());
        assertTrue(history.getFirst().getContent().contains("执行失败: 退出码 1"),
                "失败原因应保留: " + history.getFirst().getContent());
    }

    @Test
    void shouldBlockSecondIdenticalCallAndExecuteToolOnlyOnce() {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = registryWith("echo", input -> {
            executions.incrementAndGet();
            return ToolResult.success("完成", 1);
        });
        HookRegistry hooks = new HookRegistry()
                .registerFirst(HookType.PRE_TOOL_USE, new DuplicateToolCallGuard());
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(null, hooks, tools);
        List<ChatMessage> history = new ArrayList<>();

        ToolExecutionPipeline.Outcome first =
                pipeline.executeAndRecord(call("echo", "call-a"), null, history);
        ToolExecutionPipeline.Outcome second =
                pipeline.executeAndRecord(call("echo", "call-b"), null, history);

        assertFalse(first.isBlocked());
        assertTrue(second.isBlocked());
        assertTrue(second.result().getError().contains("不可重复以相同的入参调用同一个工具"));
        assertEquals(1, executions.get());
        assertEquals(2, history.size());
        assertTrue(history.getFirst().getContent().contains("完成"));
        assertTrue(history.get(1).getContent().contains("不可重复以相同的入参调用同一个工具"));
    }

    private ToolRegistry registryWith(String name, ToolAction action) {
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(new BaseTool(name, "test") {
            @Override
            public ToolResult execute(String input) {
                return action.execute(input);
            }
        });
        return registry;
    }

    private ToolCall call(String name, String id) {
        return new ToolCall(name, Map.of(), null, false, 0, false, id);
    }

    @FunctionalInterface
    private interface ToolAction {
        ToolResult execute(String input);
    }
}
