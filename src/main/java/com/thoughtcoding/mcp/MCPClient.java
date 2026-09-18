package com.thoughtcoding.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.mcp.model.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP客户端，管理与单个MCP服务器的连接和通信
 */
public class MCPClient {
    private static final Logger log = LoggerFactory.getLogger(MCPClient.class); // 应该是 MCPClient.class
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final Map<String, MCPTool> availableTools = new ConcurrentHashMap<>();
    private final Deque<String> recentErrorOutput = new ArrayDeque<>();
    private boolean initialized = false;
    private final String serverName;

    public MCPClient(String serverName) {
        this.serverName = serverName;
    }

    public boolean connect(String fullCommand, List<String> args) {
        try {
            // 分割完整命令为命令和参数，并去除引号
            String[] parts = fullCommand.split("\\s+");
            String command = parts[0].replace("\"", "");  // 去除引号
            command = resolveExecutable(command);

            List<String> commandList = new ArrayList<>();
            commandList.add(command);

            // 添加命令的其他部分作为参数，并去除引号
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].replace("\"", "");  // 去除引号
                commandList.add(arg);
            }

            // 添加额外的参数
            if (args != null && !args.isEmpty()) {
                for (String arg : args) {
                    commandList.add(arg.replace("\"", ""));  // 去除引号
                }
            }

            log.debug("完整命令: {}", String.join(" ", commandList));
            log.debug("工作目录: {}", System.getProperty("user.dir"));

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(new File(System.getProperty("user.dir")));
            // 🔥 关键修复：不合并错误流，分开处理
            pb.redirectErrorStream(false);

            process = pb.start();

            // 🔥 启动错误流监控（只监控错误，不影响主输出）
            startErrorMonitoring();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // 等待进程启动
            log.debug("⏳ 等待 MCP 服务器启动...");
            Thread.sleep(2000);

            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                log.error("❌ MCP服务器进程退出，退出码: {}", exitCode);
                return false;
            }

            log.debug("✅ MCP 进程已启动，开始协议初始化...");

            if (initializeProtocol()) {
                listTools();
                initialized = true;
                log.debug("✅ MCP客户端初始化成功: {} ({} 个工具)", serverName, availableTools.size());
                return true;
            } else {
                log.error("❌ MCP 协议初始化失败");
            }

        } catch (Exception e) {
            log.error("❌ 连接MCP服务器失败: {}", serverName, e);
        }
        return false;
    }

    /**
     * ProcessBuilder 在 Windows 上不会像 PowerShell/cmd 一样可靠地为命令补全 .cmd。
     * npm/npx 的 Windows 启动器恰好是 .cmd，因此在启动子进程前按 PATH/PATHEXT
     * 解析一次，同时保留 Linux/macOS 的原有行为。
     */
    static String resolveExecutable(String command) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return command;
        }

        Path commandPath = Paths.get(command);
        String fileName = commandPath.getFileName().toString();
        if (fileName.contains(".")) {
            return command;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return command;
        }

        String pathExtEnv = System.getenv("PATHEXT");
        String[] extensions = (pathExtEnv == null || pathExtEnv.isBlank()
                ? ".COM;.EXE;.BAT;.CMD"
                : pathExtEnv).split(";");

        List<Path> searchDirectories = new ArrayList<>();
        Path parent = commandPath.getParent();
        if (parent != null) {
            searchDirectories.add(parent);
        } else {
            for (String directory : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!directory.isBlank()) {
                    searchDirectories.add(Paths.get(directory.replace("\"", "")));
                }
            }
        }

        for (Path directory : searchDirectories) {
            for (String extension : extensions) {
                Path candidate = directory.resolve(fileName + extension.toLowerCase(Locale.ROOT));
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
                candidate = directory.resolve(fileName + extension.toUpperCase(Locale.ROOT));
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }

        return command;
    }

    /**
     * 🔥 只监控错误流，避免和主输入流冲突
     */
    private void startErrorMonitoring() {
        Thread errorThread = new Thread(() -> {
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    if (!line.isBlank()) {
                        synchronized (recentErrorOutput) {
                            if (recentErrorOutput.size() >= 20) {
                                recentErrorOutput.removeFirst();
                            }
                            recentErrorOutput.addLast(line);
                        }
                        log.debug("MCP server stderr [{}]: {}", serverName, line);
                    }
                }
            } catch (Exception e) {
                // 正常结束
            }
        });
        errorThread.setDaemon(true);
        errorThread.setName("MCP-Error-" + serverName);
        errorThread.start();
    }

    private void startOutputMonitoring() {
        // 🔥 已废弃：不再使用此方法，避免和 reader 冲突
    }

    private boolean initializeProtocol() throws IOException {
        try {
            // 🔥 使用 MCPRequest 类来确保 JSON 序列化正确
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", "2024-11-05");

            Map<String, Object> capabilities = new LinkedHashMap<>();
            Map<String, Object> roots = new LinkedHashMap<>();
            roots.put("listChanged", true);
            capabilities.put("roots", roots);
            capabilities.put("sampling", new LinkedHashMap<>());
            params.put("capabilities", capabilities);

            Map<String, Object> clientInfo = new LinkedHashMap<>();
            clientInfo.put("name", "ThoughtCoding");
            clientInfo.put("version", "1.0.0");
            params.put("clientInfo", clientInfo);

            log.debug("发送初始化请求...");

            // 使用 MCPRequest 确保正确序列化
            MCPRequest request = new MCPRequest("initialize", params);
            request.setId("1");  // 使用字符串 ID

            String json = objectMapper.writeValueAsString(request);
            log.debug("发送的JSON: {}", json);
            writer.write(json);
            writer.newLine();
            writer.flush();

            // 读取并记录所有输出，用于调试
            log.debug("等待MCP服务器响应...");
            MCPResponse response = readResponse(15000);

            if (response != null) {
                log.debug("收到初始化响应: {}", response);
                if (response.getError() == null) {
                    log.debug("✅ MCP协议初始化成功");
                    return true;
                } else {
                    log.error("❌ MCP协议初始化错误: {}", response.getError().getMessage());
                }
            }

            return false;

        } catch (Exception e) {
            log.error("❌ 协议初始化异常: {}", e.getMessage(), e);
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

    /**
     * 同一 server 的请求串行锁。
     *
     * <p><b>串话修复（最小方案）</b>：{@link #readResponse} 按行读下一条响应、不按请求 id 匹配，
     * 并发调用同一 server 的两个工具会互相读走对方的响应。在 id 多路复用重构之前，
     * 先以 per-client 锁保证同一 server 同时只有一个 in-flight 请求——
     * MCP 调用频率低（工具调用非吞吐敏感），串行的代价可忽略，正确性优先。
     */
    private final Object requestLock = new Object();

    public Object callTool(String toolName, Map<String, Object> arguments) throws IOException {
        if (!initialized) {
            throw new IllegalStateException("MCP客户端未初始化");
        }

        MCPRequest request = new MCPRequest(
                "tools/call",
                Map.of("name", toolName, "arguments", arguments)
        );

        synchronized (requestLock) {
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
        int attemptCount = 0;

        log.debug("⏳ 开始等待响应，超时时间: {}ms", timeoutMs);

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                attemptCount++;

                // 检查进程状态
                if (process != null && !process.isAlive()) {
                    int exitCode = process.exitValue();
                    log.error("❌ MCP进程已退出，退出码: {}", exitCode);
                    logRecentErrorOutput();
                    throw new IOException("MCP进程异常退出");
                }

                // 非阻塞检查是否有数据可读
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            log.debug("📨 收到数据 [尝试#{}]: {}", attemptCount, line);

                            // 尝试解析为 JSON
                            try {
                                MCPResponse response = objectMapper.readValue(line, MCPResponse.class);
                                log.debug("✅ 成功解析MCP响应 (耗时: {}ms)", System.currentTimeMillis() - startTime);
                                return response;
                            } catch (Exception e) {
                                log.warn("⚠️ 响应不是有效JSON，继续等待: {}", line);
                            }
                        }
                    }
                } else {
                    // 每500ms输出一次等待状态
                    if (attemptCount % 5 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.debug("⏳ 等待中... (已等待 {}ms / {}ms)", elapsed, timeoutMs);
                    }
                }

                Thread.sleep(100);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("❌ 读取响应被中断");
                throw new IOException("读取响应被中断");
            }
        }

        // 超时
        long totalTime = System.currentTimeMillis() - startTime;
        log.error("❌ 读取响应超时！总等待时间: {}ms, 尝试次数: {}", totalTime, attemptCount);
        logRecentErrorOutput();

        // 尝试最后一次读取
        try {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    log.error("❌ 超时前收到的最后数据: {}", line);
                }
            }
        } catch (Exception e) {
            // 忽略
        }

        throw new IOException("读取响应超时 (等待了 " + totalTime + "ms)");
    }

    private void logRecentErrorOutput() {
        String summary;
        synchronized (recentErrorOutput) {
            summary = String.join(System.lineSeparator(), recentErrorOutput);
        }
        if (!summary.isBlank()) {
            log.error("MCP server stderr [{}]:{}{}", serverName, System.lineSeparator(), summary);
        }
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
