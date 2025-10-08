// src/main/java/com/thoughtcoding/tools/ToolProvider.java
package com.thoughtcoding.tools;

public interface ToolProvider {
    BaseTool getTool(String toolName);
    void registerTool(BaseTool tool);
    boolean isToolAvailable(String toolName);
}