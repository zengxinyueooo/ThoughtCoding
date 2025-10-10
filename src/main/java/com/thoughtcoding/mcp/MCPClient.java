package com.thoughtcoding.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.mcp.model.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


public class MCPClient {
    private static final Logger log = LoggerFactory.getLogger(MCPClient.class); // 应该是 MCPClient.class
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final Map<String, MCPTool> availableTools = new ConcurrentHashMap<>();
    private boolean initialized = false;
    private final String serverName;

    public MCPClient(String serverName) {
        this.serverName = serverName;
    }

    public boolean connect(String command, List<String> args) {
        try {
            log.info("启动MCP服务器: {} - {}", serverName, command);
            log.info("参数列表: {}", args);

            List<String> commandList = new ArrayList<>();
            commandList.add(command);

            // 🔥 现在 serverConfig 是 List<String>，直接添加所有参数
            if (args != null && !args.isEmpty()) {
                for (String arg : args) {
                    log.info("添加参数: {}", arg);
                    commandList.add(arg);
                }
            }

            log.info("完整命令: {}", String.join(" ", commandList));
            log.info("工作目录: {}", System.getProperty("user.dir"));

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(new File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);

            process = pb.start();
            startOutputMonitoring();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // 等待进程启动
            Thread.sleep(3000);

            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                log.error("MCP服务器进程退出，退出码: {}", exitCode);
                return false;
            }

            if (initializeProtocol()) {
                listTools();
                initialized = true;
                log.info("✅ MCP客户端初始化成功: {} ({} 个工具)", serverName, availableTools.size());
                return true;
            }

        } catch (Exception e) {
            log.error("连接MCP服务器失败: {}", serverName, e);
        }
        return false;
    }

    private void startOutputMonitoring() {
        Thread monitorThread = new Thread(() -> {
            try (BufferedReader outputReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = outputReader.readLine()) != null) {
                    // 🔥 过滤掉 npm 相关的所有日志
                    if (line.contains("npm ERR!") || line.contains("npm WARN") ||
                            line.contains("node_cache") || line.contains("_cacache")) {
                        // 完全忽略 npm 错误、警告和缓存相关日志
                        continue;
                    }

                    // 🔥 过滤其他系统错误日志
                    if (line.contains("EPERM") || line.contains("operation not permitted") ||
                            line.contains("The operation was rejected by your operating system")) {
                        continue;
                    }

                    // 只记录真正的 MCP 服务器消息
                    if (line.contains("Secure MCP") || line.contains("running on stdio")) {
                        log.info("✅ MCP服务器已启动: {}", serverName);
                    } else if (line.contains("error") || line.contains("Error") || line.contains("ERROR")) {
                        log.error("MCP服务器错误: {}", line);
                    } else if (line.contains("warning") || line.contains("Warning")) {
                        log.warn("MCP服务器警告: {}", line);
                    } else if (!line.trim().isEmpty()) {
                        // 只记录非空的、非 npm 的输出
                        log.info("MCP服务器: {}", line);
                    }

                    // 检查服务器就绪消息
                    if (line.contains("running") || line.contains("ready") || line.contains("started")) {
                        log.info("✅ MCP服务器已就绪: {}", serverName);
                    }
                }
            } catch (Exception e) {
                if (process.isAlive()) {
                    log.debug("输出监控结束: {}", e.getMessage());
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.setName("MCP-Monitor-" + serverName);
        monitorThread.start();
    }

    private boolean initializeProtocol() throws IOException {
        try {
            // 新的初始化请求格式
            MCPRequest request = new MCPRequest(
                    "initialize",
                    Map.of(
                            "jsonrpc", "2.0",
                            "id", 1,
                            "method", "initialize",
                            "params", Map.of(
                                    "protocolVersion", "2024-11-05",
                                    "capabilities", Map.of(
                                            "roots", Map.of("listChanged", true),
                                            "tools", Map.of("listChanged", true)
                                    ),
                                    "clientInfo", Map.of(
                                            "name", "ThoughtCoding",
                                            "version", "1.0.0"
                                    )
                            )
                    )
            );

            log.info("发送初始化请求...");
            sendRequest(request);

            // 读取并记录所有输出，用于调试
            log.info("等待MCP服务器响应...");
            MCPResponse response = readResponse(15000);

            if (response != null) {
                log.info("收到初始化响应: {}", response);
                if (response.getError() == null) {
                    log.info("MCP协议初始化成功");
                    return true;
                } else {
                    log.error("MCP协议初始化错误: {}", response.getError().getMessage());
                }
            }

            return false;

        } catch (Exception e) {
            log.error("协议初始化异常: {}", e.getMessage());
            return false;
        }
    }

    private void listTools() throws IOException {
        MCPRequest request = new MCPRequest("tools/list", Map.of());
        sendRequest(request);

        MCPResponse response = readResponse(3000);
        if (response != null && response.getResult() != null) {
            Map<String, Object> result = (Map<String, Object>) response.getResult();
            List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

            if (toolsList != null) {
                for (Map<String, Object> toolData : toolsList) {
                    try {
                        MCPTool tool = objectMapper.convertValue(toolData, MCPTool.class);
                        availableTools.put(tool.getName(), tool);
                        log.debug("发现工具: {} - {}", tool.getName(), tool.getDescription());
                    } catch (Exception e) {
                        log.warn("解析工具失败: {}", toolData, e);
                    }
                }
            }
        }
    }

    public Object callTool(String toolName, Map<String, Object> arguments) throws IOException {
        if (!initialized) {
            throw new IllegalStateException("MCP客户端未初始化");
        }

        MCPRequest request = new MCPRequest(
                "tools/call",
                Map.of("name", toolName, "arguments", arguments)
        );

        sendRequest(request);
        MCPResponse response = readResponse(30000);

        if (response != null) {
            if (response.getError() != null) {
                throw new IOException("工具调用失败: " + response.getError().getMessage());
            }
            if (response.getResult() != null) {
                Map<String, Object> result = (Map<String, Object>) response.getResult();
                return result.get("content");
            }
        }

        throw new IOException("工具调用无响应");
    }

    private void sendRequest(MCPRequest request) throws IOException {
        String json = objectMapper.writeValueAsString(request);
        writer.write(json);
        writer.newLine();
        writer.flush();
        log.debug("发送MCP请求[{}]: {}", serverName, json);
    }

    private MCPResponse readResponse(long timeoutMs) throws IOException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                // 直接读取，不检查 ready()
                String line = reader.readLine();
                if (line != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        log.info("📨 收到MCP服务器原始输出: {}", line);

                        // 尝试解析为 JSON
                        try {
                            MCPResponse response = objectMapper.readValue(line, MCPResponse.class);
                            log.info("✅ 成功解析MCP响应: {}", response);
                            return response;
                        } catch (Exception e) {
                            log.warn("⚠️ 响应不是有效JSON，但服务器有输出: {}", line);
                            // 继续等待有效JSON响应
                        }
                    }
                }

                // 检查进程状态
                if (process != null && !process.isAlive()) {
                    int exitCode = process.exitValue();
                    log.error("❌ MCP进程已退出，退出码: {}", exitCode);
                    throw new IOException("MCP进程异常退出");
                }

                Thread.sleep(100);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("读取响应被中断");
            }
        }

        // 超时前尝试读取最后一行
        try {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    log.info("最后收到的数据: {}", line);
                }
            }
        } catch (Exception e) {
            // 忽略
        }

        throw new IOException("读取响应超时");
    }

    public void disconnect() {
        try {
            if (writer != null) {
                MCPRequest request = new MCPRequest("shutdown", null);
                sendRequest(request);
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
            if (process != null) {
                process.destroy();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            initialized = false;
            availableTools.clear();
            log.info("MCP客户端已断开: {}", serverName);
        } catch (Exception e) {
            log.error("断开MCP连接时出错: {}", serverName, e);
        }
    }

    public List<MCPTool> getAvailableTools() {
        return new ArrayList<>(availableTools.values());
    }

    public boolean isConnected() {
        return process != null && process.isAlive() && initialized;
    }

    public String getServerName() {
        return serverName;
    }
}