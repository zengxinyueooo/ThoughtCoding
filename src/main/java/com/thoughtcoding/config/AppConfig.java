package com.thoughtcoding.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用配置类，包含模型和工具的配置
 * 具体包括：ToolsConfig, ToolConfig, ModelConfig, MCPConfig, MCPServerConfig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AppConfig {

    @JsonProperty("models")
    private Map<String, ModelConfig> models;

    @JsonProperty("defaultModel")
    private String defaultModel;

    @JsonProperty("tools")
    private ToolsConfig tools = new ToolsConfig(); // Ensure tools is initialized

    // 🔥 添加 MCP 配置字段
    @JsonProperty("mcp")
    private MCPConfig mcp = new MCPConfig();

    // Getters and Setters
    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }

    public ModelConfig getModelConfig(String modelName) {
        return models != null ? models.get(modelName) : null;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public ToolsConfig getTools() {
        if (tools == null) {
            tools = new ToolsConfig(); // Ensure tools is not null
        }
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    // 🔥 添加 MCP 的 getter 和 setter
    public MCPConfig getMcp() {
        if (mcp == null) {
            mcp = new MCPConfig();
        }
        return mcp;
    }

    public void setMcp(MCPConfig mcp) {
        this.mcp = mcp;
    }

    public String getDefaultModel() {
        // 如果配置了defaultModel，使用配置的值
        if (defaultModel != null && !defaultModel.trim().isEmpty()) {
            return defaultModel;
        }

        // 如果没有配置defaultModel，使用第一个模型作为默认
        if (models != null && !models.isEmpty()) {
            return models.keySet().iterator().next();
        }

        // 如果连模型都没有配置，返回null或抛出异常
        return null;
    }

    @Data
    public static class MCPServerConfig {
        private String name; // 服务器名称
        private String command;  // 启动命令（如："npx @modelcontextprotocol/server-filesystem"）
        private boolean enabled = true; // 是否启用
        private List<String> args = new ArrayList<>(); // 命令行参数

        // 手动添加 getter/setter
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
    }

    @Data
    public static class MCPConfig {
        private boolean enabled = false;
        private List<MCPServerConfig> servers = new ArrayList<>();
        private boolean autoDiscover = true;
        private int connectionTimeout = 30;

        // 手动添加 getter/setter
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<MCPServerConfig> getServers() { return servers; }
        public void setServers(List<MCPServerConfig> servers) { this.servers = servers; }
        public boolean isAutoDiscover() { return autoDiscover; }
        public void setAutoDiscover(boolean autoDiscover) { this.autoDiscover = autoDiscover; }
        public int getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    }

    @Data
    public static class ModelConfig {
        @JsonProperty("name")
        private String name;

        @JsonProperty("baseURL")
        private String baseURL;

        @JsonProperty("apiKey")
        private String apiKey;

        @JsonProperty("streaming")
        private boolean streaming = true;

        @JsonProperty("maxTokens")
        private Integer maxTokens = 4096;

        @JsonProperty("temperature")
        private Double temperature = 0.7;

        @JsonProperty("topP")
        private Double topP = 0.9;

        @JsonProperty("timeout")
        private Integer timeout = 60;

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseURL() {
            return baseURL;
        }
        public Double getTemperature() {
            return temperature != null ? temperature : 0.7;
        }

        public void setBaseURL(String baseURL) {
            this.baseURL = baseURL;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    @Data
    public static class ToolsConfig {
        @JsonProperty("fileManager")
        private ToolConfig fileManager = new ToolConfig(); // Ensure fileManager is initialized

        @JsonProperty("commandExec")
        private ToolConfig commandExec = new ToolConfig();

        @JsonProperty("codeExecutor")
        private ToolConfig codeExecutor = new ToolConfig();

        @JsonProperty("search")
        private ToolConfig search = new ToolConfig();

        // Getters and Setters
        public ToolConfig getFileManager() {
            if (fileManager == null) {
                fileManager = new ToolConfig(); // Ensure fileManager is not null
            }
            return fileManager;
        }

        public void setFileManager(ToolConfig fileManager) {
            this.fileManager = fileManager;
        }

        public ToolConfig getCommandExec() {
            if (commandExec == null) {
                commandExec = new ToolConfig();
            }
            return commandExec;
        }

        public void setCommandExec(ToolConfig commandExec) {
            this.commandExec = commandExec;
        }

        public ToolConfig getCodeExecutor() {
            if (codeExecutor == null) {
                codeExecutor = new ToolConfig();
            }
            return codeExecutor;
        }

        public void setCodeExecutor(ToolConfig codeExecutor) {
            this.codeExecutor = codeExecutor;
        }

        public ToolConfig getSearch() {
            if (search == null) {
                search = new ToolConfig();
            }
            return search;
        }

        public void setSearch(ToolConfig search) {
            this.search = search;
        }
    }

    @Data
    public static class ToolConfig {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("maxFileSize")
        private Long maxFileSize= 10485760L; // 设置默认值

        @JsonProperty("allowedCommands")
        private String[] allowedCommands;

        @JsonProperty("timeoutSeconds")
        private Integer timeoutSeconds = 30;

        @JsonProperty("allowedLanguages")
        private String[] allowedLanguages = {"java", "python", "javascript"};

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(Long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public String[] getAllowedCommands() {
            return allowedCommands;
        }

        public void setAllowedCommands(String[] allowedCommands) {
            this.allowedCommands = allowedCommands;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String[] getAllowedLanguages() {
            return allowedLanguages;
        }

        public void setAllowedLanguages(String[] allowedLanguages) {
            this.allowedLanguages = allowedLanguages;
        }
    }
}