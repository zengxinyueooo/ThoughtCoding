package com.thoughtcoding.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 配置类
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class MCPConfig {

    @JsonProperty("enabled")
    private boolean enabled = false;

    @JsonProperty("autoDiscover")
    private boolean autoDiscover = true;

    @JsonProperty("connectionTimeout")
    private int connectionTimeout = 30;

    @JsonProperty("servers")
    private List<MCPServerConfig> servers = new ArrayList<>();

    // Lombok @Data 应该自动生成这些方法，但为了确保兼容性，手动添加
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoDiscover() {
        return autoDiscover;
    }

    public void setAutoDiscover(boolean autoDiscover) {
        this.autoDiscover = autoDiscover;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public List<MCPServerConfig> getServers() {
        return servers;
    }

    public void setServers(List<MCPServerConfig> servers) {
        this.servers = servers;
    }

    /**
     * 根据服务器名称查找服务器配置
     */
    public MCPServerConfig getServerConfig(String serverName) {
        if (servers == null) {
            return null;
        }

        return servers.stream()
                .filter(server -> serverName.equalsIgnoreCase(server.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有启用的服务器配置
     */
    public List<MCPServerConfig> getEnabledServers() {
        if (servers == null) {
            return new ArrayList<>();
        }

        return servers.stream()
                .filter(MCPServerConfig::isEnabled)
                .toList();
    }
}

