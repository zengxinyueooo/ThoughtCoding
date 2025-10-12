package com.thoughtcoding.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型配置类，包含模型名称、基础URL、API密钥、流式传输选项、最大令牌数和温度
 */
public class ModelConfig {
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


    public ModelConfig(String name, String baseURL, String apiKey, boolean streaming,
                       int maxTokens, double temperature) {
        this.name = name;
        this.baseURL = baseURL;
        this.apiKey = apiKey;
        this.streaming = streaming;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    // Getters
    public String getName() { return name; }
    public String getBaseURL() { return baseURL; }
    public String getApiKey() { return apiKey; }
    public boolean isStreaming() { return streaming; }
    public Integer getMaxTokens() { return maxTokens; }
    public double getTemperature() { return temperature; }

    public static class Builder {
        private String name;
        private String baseURL;
        private String apiKey;
        private boolean streaming = true;
        private int maxTokens = 4096;
        private double temperature = 0.7;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder baseURL(String baseURL) {
            this.baseURL = baseURL;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public ModelConfig build() {
            return new ModelConfig(name, baseURL, apiKey, streaming, maxTokens, temperature);
        }
    }
}