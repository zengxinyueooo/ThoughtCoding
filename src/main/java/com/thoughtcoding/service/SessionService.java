package com.thoughtcoding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SessionData;
import com.thoughtcoding.model.ToolCallRef;


import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话管理服务，负责创建、保存、加载和删除会话
 *
 * 支持自动保存
 */
public class SessionService {

    private static final String SESSIONS_DIR = "sessions";
    private final Map<String, SessionData> activeSessions;
    private final ObjectMapper objectMapper;

    // 简化的 DTO 用于序列化
    private static class SessionDTO {
        public String sessionId;
        public String title;
        public String createdTime;
        public String lastAccessTime;
        public List<MessageDTO> messages = new ArrayList<>();
    }

    private static class MessageDTO {
        public String role;
        public String content;
        public String timestamp;
        // 🔥 原生工具调用字段（可空，旧会话没有）
        public String toolCallId;
        public String toolName;
        public List<ToolCallDTO> toolCalls;
    }

    private static class ToolCallDTO {
        public String id;
        public String name;
        public String arguments;
    }

    public SessionService() {
        this.activeSessions = new HashMap<>();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        ensureSessionsDirectory();
    }

    public String createNewSession() {
        return createNewSession("Untitled Session", "default-model");
    }

    public String createNewSession(String title, String modelName) {
        String sessionId = generateSessionId();
        SessionData session = new SessionData(sessionId, title, modelName);
        activeSessions.put(sessionId, session);
        saveSessionToDisk(session);
        return sessionId;
    }

    public void saveSession(String sessionId, List<ChatMessage> messages) {
        try {
            SessionDTO sessionDTO = new SessionDTO();
            sessionDTO.sessionId = sessionId;
            sessionDTO.title = "Untitled Session";
            sessionDTO.createdTime = Instant.now().toString();
            sessionDTO.lastAccessTime = Instant.now().toString();

            // 转换消息
            sessionDTO.messages = messages.stream()
                    .map(msg -> {
                        MessageDTO dto = new MessageDTO();
                        dto.role = msg.getRole();
                        dto.content = msg.getContent();
                        dto.timestamp = msg.getTimestamp() != null ? String.valueOf(msg.getTimestamp()) : Instant.now().toString();
                        // 🔥 原生工具字段往返
                        dto.toolCallId = msg.getToolCallId();
                        dto.toolName = msg.getToolName();
                        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                            dto.toolCalls = msg.getToolCalls().stream()
                                    .map(ref -> {
                                        ToolCallDTO t = new ToolCallDTO();
                                        t.id = ref.getId();
                                        t.name = ref.getName();
                                        t.arguments = ref.getArguments();
                                        return t;
                                    })
                                    .collect(Collectors.toList());
                        }
                        return dto;
                    })
                    .collect(Collectors.toList());

            // 原子写：先写临时文件再原子 rename，崩溃不会留下半截 JSON
            // （半截 JSON 会导致该会话永久无法加载，历史全部丢失）
            try {
                Path target = getSessionFilePath(sessionId);
                Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
                objectMapper.writeValue(tmp.toFile(), sessionDTO);
                try {
                    java.nio.file.Files.move(tmp, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    java.nio.file.Files.move(tmp, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to save session to disk: " + e.getMessage(), e);
            }

        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to save session to disk: " + e.getMessage(), e);
        }
    }

    public List<ChatMessage> loadSession(String sessionId) {
        try {
            // 首先检查内存中的会话
            SessionData session = activeSessions.get(sessionId);
            if (session != null) {
                return session.getMessages();
            }

            // 从磁盘加载
            File jsonFile = getSessionFilePath(sessionId).toFile();

            if (!jsonFile.exists()) {
                throw new RuntimeException("Session file not found: " + sessionId);
            }

            // 使用 Map 解析 JSON，避免 SessionDTO
            Map<String, Object> sessionData = objectMapper.readValue(jsonFile, Map.class);

            // 获取 messages 数组
            List<Map<String, Object>> messagesData = (List<Map<String, Object>>) sessionData.get("messages");

            if (messagesData == null) {
                return new ArrayList<>();
            }

            // 转换为 ChatMessage 列表 - 添加过滤
            List<ChatMessage> loaded = messagesData.stream()
                    .map(messageMap -> {
                        String role = (String) messageMap.get("role");
                        String content = (String) messageMap.get("content");
                        String toolCallId = (String) messageMap.get("toolCallId");
                        String toolName = (String) messageMap.get("toolName");
                        Object toolCallsObj = messageMap.get("toolCalls");
                        boolean hasToolCalls = toolCallsObj instanceof List && !((List<?>) toolCallsObj).isEmpty();
                        boolean isTool = "tool".equals(role);

                        // 🚨 过滤空内容 —— 但豁免工具消息：role=tool 或携带 toolCalls 的 assistant 消息即使正文为空也必须保留
                        if ((content == null || content.trim().isEmpty()) && !isTool && !hasToolCalls) {
                            return null;
                        }

                        ChatMessage msg = new ChatMessage(role, content == null ? "" : content, sessionId);

                        // 设置非 final 字段
                        Object timestamp = messageMap.get("timestamp");
                        if (timestamp != null) {
                            msg.setTimestamp(timestamp.toString());
                        }

                        // 🔥 重建工具字段
                        msg.setToolCallId(toolCallId);
                        msg.setToolName(toolName);
                        if (hasToolCalls) {
                            List<ToolCallRef> refs = new ArrayList<>();
                            for (Object o : (List<?>) toolCallsObj) {
                                if (o instanceof Map) {
                                    Map<?, ?> m = (Map<?, ?>) o;
                                    refs.add(new ToolCallRef(
                                            (String) m.get("id"),
                                            (String) m.get("name"),
                                            (String) m.get("arguments")));
                                }
                            }
                            msg.setToolCalls(refs);
                        }

                        return msg;
                    })
                    .filter(Objects::nonNull) // 过滤掉 null
                    .collect(Collectors.toList());

            // 🔥 清理孤立的工具调用/结果，避免加载残缺会话后触发模型 400
            return sanitizeToolPairs(loaded);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load session from disk: " + e.getMessage(), e);
        }
    }

    /**
     * 🔥 清理工具调用/结果的配对，防止加载残缺会话后违反 "assistant 工具调用必须紧跟同 id 的 tool 结果" 契约：
     *  - 丢弃没有对应 assistant 工具调用的孤立 role=tool 结果；
     *  - assistant 消息里剥掉没有对应结果的 toolCalls（若剥空则退化为普通 assistant 消息）。
     */
    private List<ChatMessage> sanitizeToolPairs(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        Set<String> resultIds = new HashSet<>();   // 出现过的 tool 结果 id
        Set<String> callIds = new HashSet<>();      // assistant 发起的工具调用 id
        for (ChatMessage m : history) {
            if (m.isToolMessage() && m.getToolCallId() != null) {
                resultIds.add(m.getToolCallId());
            }
            if (m.getToolCalls() != null) {
                for (ToolCallRef r : m.getToolCalls()) {
                    if (r.getId() != null) callIds.add(r.getId());
                }
            }
        }

        List<ChatMessage> result = new ArrayList<>(history.size());
        for (ChatMessage m : history) {
            if (m.isToolMessage()) {
                // 无配对 assistant 工具调用 → 丢弃
                if (m.getToolCallId() == null || !callIds.contains(m.getToolCallId())) {
                    continue;
                }
            } else if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                // 只保留有对应结果的工具调用
                List<ToolCallRef> kept = m.getToolCalls().stream()
                        .filter(r -> r.getId() != null && resultIds.contains(r.getId()))
                        .collect(Collectors.toList());
                m.setToolCalls(kept.isEmpty() ? null : kept);
            }
            result.add(m);
        }
        return result;
    }

    public boolean deleteSession(String sessionId) {
        // 从内存中移除
        SessionData removed = activeSessions.remove(sessionId);

        // 从磁盘删除
        Path sessionFile = getSessionFilePath(sessionId);
        try {
            return Files.deleteIfExists(sessionFile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + e.getMessage(), e);
        }
    }

    public List<String> listSessions() {
        Set<String> sessions = new HashSet<>();

        // 添加内存中的会话
        sessions.addAll(activeSessions.keySet());

        // 添加磁盘上的会话
        try {
            Path sessionsDir = Paths.get(SESSIONS_DIR);
            if (Files.exists(sessionsDir)) {
                Files.list(sessionsDir)
                        .filter(path -> path.toString().endsWith(".json"))
                        .map(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.substring(0, fileName.length() - 5); // 去掉 .json 后缀
                        })
                        .forEach(sessions::add);
            }
        } catch (Exception e) {
            // 忽略错误，继续执行
            System.err.println("Error listing sessions: " + e.getMessage());
        }

        return new ArrayList<>(sessions);
    }

    public String getLatestSessionId() {
        List<String> sessions = listSessions();
        if (sessions.isEmpty()) {
            return null;
        }

        // 按会话文件的修改时间取最新——`-c` 续接的必须是真正最近使用过的会话。
        // 旧实现「HashSet 后取最后一个」顺序不确定，可能续接到任意一个历史会话。
        String latest = null;
        long latestMtime = Long.MIN_VALUE;
        for (String id : sessions) {
            try {
                Path file = getSessionFilePath(id);
                if (Files.exists(file)) {
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    if (mtime > latestMtime) {
                        latestMtime = mtime;
                        latest = id;
                    }
                }
            } catch (Exception ignored) {
                // 单个文件 stat 失败不阻塞其余比较
            }
        }
        if (latest != null) {
            return latest;
        }
        // 全部 stat 失败的极端降级：保持旧行为
        return sessions.get(sessions.size() - 1);
    }

    public SessionData getSessionInfo(String sessionId) {
        return activeSessions.get(sessionId);
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    private void ensureSessionsDirectory() {
        try {
            Path sessionsDir = Paths.get(SESSIONS_DIR);
            if (!Files.exists(sessionsDir)) {
                Files.createDirectories(sessionsDir);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create sessions directory", e);
        }
    }

    private Path getSessionFilePath(String sessionId) {
        return Paths.get(SESSIONS_DIR, sessionId + ".json");
    }

    private void saveSessionToDisk(SessionData session) {
        try {
            // 使用 objectMapper 替代 JsonUtils
            String json = objectMapper.writeValueAsString(session);
            Path sessionFile = getSessionFilePath(session.getSessionId());
            Files.writeString(sessionFile, json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session to disk: " + e.getMessage(), e);
        }
    }

    private SessionData loadSessionFromDisk(String sessionId) {
        try {
            Path sessionFile = getSessionFilePath(sessionId);
            if (!Files.exists(sessionFile)) {
                return null;
            }

            String json = Files.readString(sessionFile);
            return objectMapper.readValue(json, SessionData.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load session from disk: " + e.getMessage(), e);
        }
    }
}