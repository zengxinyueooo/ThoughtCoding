package com.thoughtcoding.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器配置类
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class MCPServerConfig {

    @JsonProperty("name")
    private String name;

    @JsonProperty("command")
    private String command;

    @JsonProperty("enabled")
    private boolean enabled = false;

    @JsonProperty("args")
    private List<String> args = new ArrayList<>();

    // Lombok @Data 应该自动生成这些方法，但为了确保兼容性，手动添加
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }
}

