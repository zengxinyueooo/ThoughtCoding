package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

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