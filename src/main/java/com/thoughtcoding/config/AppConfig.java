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
 * 具体包括：ToolsConfig, ToolConfig, ModelConfig
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

    @JsonProperty("ai")
    private AIConfig ai = new AIConfig(); // AI行为配置

    @JsonProperty("memory")
    private MemoryConfig memory = new MemoryConfig(); // 记忆系统配置


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

    public AIConfig getAi() {
        if (ai == null) {
            ai = new AIConfig();
        }
        return ai;
    }

    public void setAi(AIConfig ai) {
        this.ai = ai;
    }

    public MemoryConfig getMemory() {
        if (memory == null) {
            memory = new MemoryConfig();
        }
        return memory;
    }

    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class ToolsConfig {
        @JsonProperty("bash")
        private ToolConfig bash = new ToolConfig();

        @JsonProperty("read")
        private ToolConfig read = new ToolConfig();

        @JsonProperty("write")
        private ToolConfig write = new ToolConfig();

        @JsonProperty("edit")
        private ToolConfig edit = new ToolConfig();

        @JsonProperty("glob")
        private ToolConfig glob = new ToolConfig();

        public ToolConfig getBash() {
            if (bash == null) bash = new ToolConfig();
            return bash;
        }

        public void setBash(ToolConfig bash) {
            this.bash = bash;
        }

        public ToolConfig getRead() {
            if (read == null) read = new ToolConfig();
            return read;
        }

        public void setRead(ToolConfig read) {
            this.read = read;
        }

        public ToolConfig getWrite() {
            if (write == null) write = new ToolConfig();
            return write;
        }

        public void setWrite(ToolConfig write) {
            this.write = write;
        }

        public ToolConfig getEdit() {
            if (edit == null) edit = new ToolConfig();
            return edit;
        }

        public void setEdit(ToolConfig edit) {
            this.edit = edit;
        }

        public ToolConfig getGlob() {
            if (glob == null) glob = new ToolConfig();
            return glob;
        }

        public void setGlob(ToolConfig glob) {
            this.glob = glob;
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

    /**
     * AI行为配置
     */
    @Data
    public static class AIConfig {
        @JsonProperty("autoProcessToolResults")
        private boolean autoProcessToolResults = true; // 默认true：工具执行后把结果反馈给AI，形成 agentic 循环

        @JsonProperty("maxToolIterations")
        private int maxToolIterations = 10; // agentic 循环单次用户输入内的最大工具轮次上限

        @JsonProperty("maxConcurrentSubagents")
        private int maxConcurrentSubagents = 3; // 并行子代理上限（虚拟线程 + Semaphore 限流，防模型 API 限流）

        @JsonProperty("subagentWorktreeIsolation")
        private boolean subagentWorktreeIsolation = true; // 子代理默认在独立 Git worktree/本地分支中运行

        // ── 四层上下文压缩管线参数（见 ContextManager）——全部有默认值，旧 config 不填也能跑 ──
        @JsonProperty("maxContextTokens")
        private int maxContextTokens = 48000; // 完整请求预算：扣除 system/工具/记忆/输出预留后，余量供历史使用

        @JsonProperty("maxMessages")
        private int maxMessages = 50; // L1：消息条数超过则裁中段（保留头尾）

        @JsonProperty("snipKeepHead")
        private int snipKeepHead = 3; // L1：保留最前 N 条

        @JsonProperty("snipKeepTail")
        private int snipKeepTail = 20; // L1：保留最近 N 条

        @JsonProperty("keepRecentToolResults")
        private int keepRecentToolResults = 3; // L2：仅最近 N 条工具结果保留全文

        @JsonProperty("maxToolResultBytes")
        private int maxToolResultBytes = 200000; // L3：当轮工具结果聚合预算（字节），这批总量超过才触发落盘

        @JsonProperty("perResultPersistBytes")
        private int perResultPersistBytes = 30000; // L3：单块落盘阈值，触发后只落单条超过此值的结果

        @JsonProperty("l4KeepTail")
        private int l4KeepTail = 6; // L4：摘要后接回的最近 N 条

        public boolean isAutoProcessToolResults() {
            return autoProcessToolResults;
        }

        public void setAutoProcessToolResults(boolean autoProcessToolResults) {
            this.autoProcessToolResults = autoProcessToolResults;
        }

        public int getMaxToolIterations() {
            return maxToolIterations;
        }

        public void setMaxToolIterations(int maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }

        public int getMaxConcurrentSubagents() {
            return maxConcurrentSubagents;
        }

        public void setMaxConcurrentSubagents(int maxConcurrentSubagents) {
            this.maxConcurrentSubagents = maxConcurrentSubagents;
        }

        public boolean isSubagentWorktreeIsolation() {
            return subagentWorktreeIsolation;
        }

        public void setSubagentWorktreeIsolation(boolean subagentWorktreeIsolation) {
            this.subagentWorktreeIsolation = subagentWorktreeIsolation;
        }

        public int getMaxContextTokens() {
            return maxContextTokens;
        }

        public void setMaxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
        }

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        public int getSnipKeepHead() {
            return snipKeepHead;
        }

        public void setSnipKeepHead(int snipKeepHead) {
            this.snipKeepHead = snipKeepHead;
        }

        public int getSnipKeepTail() {
            return snipKeepTail;
        }

        public void setSnipKeepTail(int snipKeepTail) {
            this.snipKeepTail = snipKeepTail;
        }

        public int getKeepRecentToolResults() {
            return keepRecentToolResults;
        }

        public void setKeepRecentToolResults(int keepRecentToolResults) {
            this.keepRecentToolResults = keepRecentToolResults;
        }

        public int getMaxToolResultBytes() {
            return maxToolResultBytes;
        }

        public void setMaxToolResultBytes(int maxToolResultBytes) {
            this.maxToolResultBytes = maxToolResultBytes;
        }

        public int getPerResultPersistBytes() {
            return perResultPersistBytes;
        }

        public void setPerResultPersistBytes(int perResultPersistBytes) {
            this.perResultPersistBytes = perResultPersistBytes;
        }

        public int getL4KeepTail() {
            return l4KeepTail;
        }

        public void setL4KeepTail(int l4KeepTail) {
            this.l4KeepTail = l4KeepTail;
        }
    }

    /**
     * 记忆(Memory)系统配置 —— 注意：记忆<b>不作为工具</b>，由 AgentLoop 生命周期编排 +
     * system prompt 索引注入驱动（见 MemoryService / MemoryStore）。
     */
    @Data
    public static class MemoryConfig {
        @JsonProperty("enabled")
        private boolean enabled = true; // 总开关：装配记忆系统（加载 .memory/、注入索引、召回/储存/整理）

        @JsonProperty("autoExtract")
        private boolean autoExtract = true; // 每轮对话结束后自动抽取新记忆（需一次模型往返，可关）

        @JsonProperty("consolidateThreshold")
        private int consolidateThreshold = 10; // 记忆文件数达到此值触发 LLM 整合去重（0=禁用）

        @JsonProperty("maxIndexEntries")
        private int maxIndexEntries = 200; // MEMORY.md 索引条数上限（超限裁掉最早写入的）

        @JsonProperty("maxPerTurnInjections")
        private int maxPerTurnInjections = 5; // 单轮自动注入的相关记忆条数上限

        @JsonProperty("maxInjectionChars")
        private int maxInjectionChars = 12000; // 单轮相关记忆正文的字符预算，避免挤占主对话上下文

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoExtract() {
            return autoExtract;
        }

        public void setAutoExtract(boolean autoExtract) {
            this.autoExtract = autoExtract;
        }

        public int getConsolidateThreshold() {
            return consolidateThreshold;
        }

        public void setConsolidateThreshold(int consolidateThreshold) {
            this.consolidateThreshold = consolidateThreshold;
        }

        public int getMaxIndexEntries() {
            return maxIndexEntries;
        }

        public void setMaxIndexEntries(int maxIndexEntries) {
            this.maxIndexEntries = maxIndexEntries;
        }

        public int getMaxPerTurnInjections() {
            return maxPerTurnInjections;
        }

        public void setMaxPerTurnInjections(int maxPerTurnInjections) {
            this.maxPerTurnInjections = maxPerTurnInjections;
        }

        public int getMaxInjectionChars() {
            return maxInjectionChars;
        }

        public void setMaxInjectionChars(int maxInjectionChars) {
            this.maxInjectionChars = maxInjectionChars;
        }
    }
}
