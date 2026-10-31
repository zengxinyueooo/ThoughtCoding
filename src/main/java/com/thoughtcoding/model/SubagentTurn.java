package com.thoughtcoding.model;

import java.util.Collections;
import java.util.List;

/**
 * 子Agent一轮模型往返的结果载体：模型这一轮产出的文本 + 它请求的工具调用。
 *
 * <p>由 {@code AIService.chatOnceForSubagent} 返回，供 {@code SubAgent} 循环判断：
 * {@link #hasToolCalls()} 为 false → 本轮 {@link #text} 即最终结论；
 * 否则逐个执行 {@link #toolCalls} 后再问下一轮。
 */
public class SubagentTurn {

    private final String text;
    private final List<ToolCallRef> toolCalls;
    private final boolean failed;

    public SubagentTurn(String text, List<ToolCallRef> toolCalls) {
        this(text, toolCalls, false);
    }

    private SubagentTurn(String text, List<ToolCallRef> toolCalls, boolean failed) {
        this.text = text == null ? "" : text;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : toolCalls;
        this.failed = failed;
    }

    public static SubagentTurn failure(String message) {
        return new SubagentTurn(message, Collections.emptyList(), true);
    }

    public boolean isFailed() { return failed; }

    public String getText() {
        return text;
    }

    public List<ToolCallRef> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
