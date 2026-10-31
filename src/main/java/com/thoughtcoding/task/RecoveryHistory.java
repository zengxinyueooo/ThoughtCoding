package com.thoughtcoding.task;

import com.thoughtcoding.model.ChatMessage;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rebuild only the current tool batch; previous batches may reuse provider call IDs. */
public final class RecoveryHistory {
    private RecoveryHistory() { }

    public static void removeCurrentBatchResults(List<ChatMessage> history, ExecutionCheckpoint cp) {
        Set<String> ids = cp.invocations().stream().filter(i -> cp.batch().contains(i.invocationId()))
                .map(ExecutionCheckpoint.Invocation::providerCallId).collect(Collectors.toSet());
        int assistant = history.size() - 1;
        while (assistant >= 0 && !history.get(assistant).hasToolCalls()) assistant--;
        if (assistant < 0) throw new IllegalStateException("检查点工具批次缺少 assistant 消息");
        for (int i = history.size() - 1; i > assistant; i--) {
            if (history.get(i).isToolMessage() && ids.contains(history.get(i).getToolCallId())) history.remove(i);
        }
    }

    public static boolean isPrefix(List<ChatMessage> current, List<ExecutionCheckpoint.Message> saved) {
        if (current.size() > saved.size()) return false;
        Set<String> results = saved.stream().filter(m -> "tool".equals(m.role()))
                .map(ExecutionCheckpoint.Message::toolCallId).collect(Collectors.toSet());
        for (int i = 0; i < current.size(); i++) {
            var actual = ExecutionCheckpoint.Message.from(current.get(i));
            var expected = saved.get(i);
            if (actual.equals(expected)) continue;
            // SessionService strips unresolved tool references when loading legacy session JSON.
            var kept = expected.toolCalls() == null ? null : expected.toolCalls().stream()
                    .filter(r -> results.contains(r.id())).toList();
            if (kept != null && kept.isEmpty()) kept = null;
            var sanitized = new ExecutionCheckpoint.Message(expected.role(), expected.content(),
                    expected.toolCallId(), expected.toolName(), kept);
            if (!actual.equals(sanitized)) return false;
        }
        return true;
    }
}
