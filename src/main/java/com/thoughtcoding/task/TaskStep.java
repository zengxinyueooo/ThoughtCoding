package com.thoughtcoding.task;

/** Stable planning item, independent of the model's conversation history. */
public record TaskStep(String stepId, String content, String status, String evidence) {
    public TaskStep {
        if (stepId == null || stepId.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("步骤 ID 和内容不能为空");
        }
        if (!java.util.Set.of("pending", "in_progress", "completed").contains(status)) {
            throw new IllegalArgumentException("非法步骤状态: " + status);
        }
        evidence = evidence == null ? "" : evidence;
    }
}
