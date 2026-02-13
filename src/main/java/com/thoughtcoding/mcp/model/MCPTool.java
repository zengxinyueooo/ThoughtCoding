package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * MCP工具模型，包含名称、描述和输入模式
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MCPTool {
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("inputSchema")
    private Object inputSchema;

    // 手动添加 getter 方法（如果 Lombok 不工作）
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Object getInputSchema() { return inputSchema; }
}