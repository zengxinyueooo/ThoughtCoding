// src/main/java/com/thoughtcoding/config/ConfigManager.java
package com.thoughtcoding.config;

public class ConfigManager {
    private static ConfigManager instance;
    private AppConfig appConfig;

    private ConfigManager() {
        // 私有构造函数
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public void initialize(String configPath) {
        ConfigLoader loader = new ConfigLoader();
        this.appConfig = loader.loadConfig(configPath);
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public void reloadConfig(String configPath) {
        initialize(configPath);
    }
}