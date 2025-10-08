// src/main/java/com/thoughtcoding/model/ToolResult.java
package com.thoughtcoding.model;

public class ToolResult {
    private final boolean success;
    private final String output;
    private final String error;
    private final long executionTime;

    public ToolResult(boolean success, String output, String error, long executionTime) {
        this.success = success;
        this.output = output;
        this.error = error;
        this.executionTime = executionTime;
    }

    public static ToolResult success(String output, long executionTime) {
        return new ToolResult(true, output, null, executionTime);
    }

    public static ToolResult error(String error, long executionTime) {
        return new ToolResult(false, null, error, executionTime);
    }

    // Getters
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public long getExecutionTime() { return executionTime; }

    @Override
    public String toString() {
        if (success) {
            return String.format("Success: %s (%dms)", output, executionTime);
        } else {
            return String.format("Error: %s (%dms)", error, executionTime);
        }
    }
}