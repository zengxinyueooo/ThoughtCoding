package com.thoughtcoding.core;

import com.thoughtcoding.memory.MemoryService;
import com.thoughtcoding.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆信号门控的单元测试。
 *
 * <p>remember 只在有信号的轮次触发，信号标记 <memory-signal/> 必须在抽取前
 * 从历史移除——既不进抽取 prompt，也不进持久会话与后续轮上下文。
 */
class AgentLoopMemorySignalTest {

    @Test
    void stripRemovesMarkerAndReportsFound() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("user", "记住我用 tab 缩进"));
        history.add(new ChatMessage("assistant", "好的，已注意。\n" + MemoryService.MEMORY_SIGNAL + "\n"));

        boolean found = AgentLoop.stripMemorySignal(history);

        assertTrue(found);
        assertEquals("好的，已注意。", history.get(1).getContent());
    }

    @Test
    void noMarkerReturnsFalseAndKeepsContentUntouched() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("user", "普通问题"));
        history.add(new ChatMessage("assistant", "普通回答"));

        boolean found = AgentLoop.stripMemorySignal(history);

        assertFalse(found);
        assertEquals("普通回答", history.get(1).getContent());
    }

    @Test
    void markerOnlyAssistantTextIsBlankedNotNulled() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("assistant", MemoryService.MEMORY_SIGNAL));

        assertTrue(AgentLoop.stripMemorySignal(history));
        assertNotNull(history.get(0).getContent());
    }

    @Test
    void explicitRequestPatternMatchesChineseAndEnglish() {
        assertTrue(AgentLoop.EXPLICIT_MEMORY_REQUEST.matcher("请记住这个偏好").find());
        assertTrue(AgentLoop.EXPLICIT_MEMORY_REQUEST.matcher("别忘了跑测试").find());
        assertTrue(AgentLoop.EXPLICIT_MEMORY_REQUEST.matcher("以后每次都要跑测试").find());
        assertTrue(AgentLoop.EXPLICIT_MEMORY_REQUEST.matcher("please remember this").find());
        assertTrue(AgentLoop.EXPLICIT_MEMORY_REQUEST.matcher("From now on use spaces").find());
        assertFalse(AgentLoop.EXPLICIT_MEMORY_REQUEST.matcher("今天天气如何").find());
        assertFalse(AgentLoop.EXPLICIT_MEMORY_REQUEST.matcher("帮我重构这段代码").find());
    }
}
