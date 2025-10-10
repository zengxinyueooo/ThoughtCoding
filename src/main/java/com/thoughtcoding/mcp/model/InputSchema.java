package com.thoughtcoding.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

@Data
public class InputSchema {
    @JsonProperty("type")
    private String type = "object";

    @JsonProperty("properties")
    private Map<String, Object> properties;

    @JsonProperty("required")
    private String[] required;
}