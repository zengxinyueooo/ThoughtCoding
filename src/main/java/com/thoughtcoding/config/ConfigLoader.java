// src/main/java/com/thoughtcoding/config/ConfigLoader.java
package com.thoughtcoding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thoughtcoding.mcp.MCPService;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class ConfigLoader {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConfigLoader.class);

    private  MCPService mcpService;
    // 无参构造函数
    public ConfigLoader() {
    }
    //添加构造函数
    public ConfigLoader(MCPService mcpService) {
        this.mcpService = mcpService;
    }
    private AppConfig appConfig;



    // 在配置加载后初始化 MCP
    public void initializeMCP() {
        AppConfig.MCPConfig mcpConfig = appConfig.getMcp();
        if (mcpConfig != null && mcpConfig.isEnabled()) {
            log.info("初始化配置的MCP服务器...");
            for (AppConfig.MCPServerConfig serverConfig : mcpConfig.getServers()) {
                if (serverConfig.isEnabled()) {
                    mcpService.connectToServer(
                            serverConfig.getName(),
                            serverConfig.getCommand(),
                            serverConfig.getArgs()
                    );
                }
            }
        }
    }

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