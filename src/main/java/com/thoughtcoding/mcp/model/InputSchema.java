package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

/**
 * 输入模式模型，定义工具输入的结构
 */
@Data
public class InputSchema {
    @JsonProperty("type")
    private String type = "object";

    @JsonProperty("properties")
    private Map<String, Object> properties;

    @JsonProperty("required")
    private String[] required;
}