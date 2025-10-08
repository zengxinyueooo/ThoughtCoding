// src/main/java/com/thoughtcoding/model/ToolCall.java
package com.thoughtcoding.model;

import java.util.Map;

public class ToolCall {
    private final String id;
    private final String toolName;
    private final Map<String, Object> parameters;
    private final String result;
    private final boolean success;
    private final long executionTime;

    public ToolCall(String toolName, Map<String, Object> parameters, String result, boolean success, long executionTime) {
        this.id = java.util.UUID.randomUUID().toString();
        this.toolName = toolName;
        this.parameters = parameters;
        this.result = result;
        this.success = success;
        this.executionTime = executionTime;
    }

    // Getters
    public String getId() { return id; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getParameters() { return parameters; }
    public String getResult() { return result; }
    public boolean isSuccess() { return success; }
    public long getExecutionTime() { return executionTime; }

    @Override
    public String toString() {
        return String.format("ToolCall{name=%s, success=%s, time=%dms}", toolName, success, executionTime);
    }
}