// src/main/java/com/thoughtcoding/service/SessionService.java
package com.thoughtcoding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SessionData;


import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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
                        return dto;
                    })
                    .collect(Collectors.toList());

            // 保存到文件
            File jsonFile = getSessionFilePath(sessionId).toFile();
            objectMapper.writeValue(jsonFile, sessionDTO);

        } catch (Exception e) {
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
            return messagesData.stream()
                    .map(messageMap -> {
                        String role = (String) messageMap.get("role");
                        String content = (String) messageMap.get("content");

                        // 🚨 关键修复：过滤空内容
                        if (content == null || content.trim().isEmpty()) {
                            return null;
                        }

                        ChatMessage msg = new ChatMessage(role, content, sessionId);

                        // 设置非 final 字段
                        Object timestamp = messageMap.get("timestamp");
                        if (timestamp != null) {
                            msg.setTimestamp(timestamp.toString());
                        }

                        return msg;
                    })
                    .filter(Objects::nonNull) // 过滤掉 null
                    .collect(Collectors.toList());

        } catch (Exception e) {
            throw new RuntimeException("Failed to load session from disk: " + e.getMessage(), e);
        }
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

        // 简单的实现：返回最后一个会话ID
        // 实际应该根据时间戳排序，这里先返回最后一个
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