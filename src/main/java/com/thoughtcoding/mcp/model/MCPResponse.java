package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * MCP响应模型，包含JSON-RPC版本、ID、结果和错误信息
 */
@Data
public class MCPResponse {
    @JsonProperty("jsonrpc")
    private String jsonrpc;

    @JsonProperty("id")
    private String id;

    @JsonProperty("result")
    private Object result;

    @JsonProperty("error")
    private MCPError error;

    // 手动添加 getter 方法（如果 Lombok 不工作）
    public String getJsonrpc() { return jsonrpc; }
    public String getId() { return id; }
    public Object getResult() { return result; }
    public MCPError getError() { return error; }
}