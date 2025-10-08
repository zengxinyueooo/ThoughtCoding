// src/main/java/com/thoughtcoding/config/ConfigLoader.java
package com.thoughtcoding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConfigLoader {

    public AppConfig loadConfig(String configPath) {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

            // 尝试从文件系统加载
            Path filePath = Paths.get(configPath);
            if (Files.exists(filePath)) {
                return mapper.readValue(filePath.toFile(), AppConfig.class);
            }

            // 尝试从类路径加载
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(configPath);
            if (inputStream != null) {
                return mapper.readValue(inputStream, AppConfig.class);
            }

            // 加载默认配置
            return createDefaultConfig();

        } catch (Exception e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            return createDefaultConfig();
        }
    }

    private AppConfig createDefaultConfig() {
        AppConfig config = new AppConfig();
        config.setDefaultModel("deepseek-v3");
        return config;
    }
}