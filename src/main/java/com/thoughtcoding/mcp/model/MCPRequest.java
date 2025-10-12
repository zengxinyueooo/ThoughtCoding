package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;
import java.util.UUID;

/**
 * MCP请求模型，包含JSON-RPC版本、ID、方法和参数
 */
@Data
public class MCPRequest {
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    @JsonProperty("id")
    private String id;

    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Object params;

    public MCPRequest() {}

    public MCPRequest(String method, Object params) {
        this.id = UUID.randomUUID().toString();
        this.method = method;
        this.params = params;
    }

    // 手动添加 getter 方法（如果 Lombok 不工作）
    public String getJsonrpc() { return jsonrpc; }
    public String getId() { return id; }
    public String getMethod() { return method; }
    public Object getParams() { return params; }
}