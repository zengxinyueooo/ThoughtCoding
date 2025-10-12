package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * MCP错误模型，包含错误代码、消息和数据
 */
@Data
public class MCPError {
    @JsonProperty("code")
    private int code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("data")
    private Object data;

    // 手动添加 getter 方法（如果 Lombok 不工作）
    public int getCode() { return code; }
    public String getMessage() { return message; }
    public Object getData() { return data; }
}