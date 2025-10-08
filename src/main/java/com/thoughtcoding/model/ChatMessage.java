// src/main/java/com/thoughtcoding/model/ChatMessage.java
package com.thoughtcoding.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ChatMessage {
    private final String id;
    private final String role; // "user", "assistant", "system"
    private final String content;
    private final String timestamp;
    private final String sessionId;
    @JsonIgnore  // 忽略这些字段的序列化
    private boolean userMessage;

    @JsonIgnore
    private boolean systemMessage;

    @JsonIgnore
    private boolean assistantMessage;
    // 无参构造函数 - 需要提供默认值
    public ChatMessage() {
        this.role = "user";
        this.content = "";
        this.sessionId = null;
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toString();
    }


    public ChatMessage(String role, String content) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.timestamp = String.valueOf(LocalDateTime.now());
        this.sessionId = null;
    }

    public ChatMessage(String role, String content, String sessionId) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.timestamp = String.valueOf(LocalDateTime.now());
        this.sessionId = sessionId;
    }

    // 添加静态工厂方法
    public static ChatMessage from(String content) {
        return new ChatMessage("assistant", content); // 默认角色为 "assistant"
    }

    // Getters
    public String getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getTimestamp() { return timestamp; }
    public String getSessionId() { return sessionId; }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s", timestamp, role, content);
    }

    public boolean isUserMessage() {
        return "user".equals(role);
    }

    public boolean isAssistantMessage() {
        return "assistant".equals(role);
    }

    public boolean isSystemMessage() {
        return "system".equals(role);
    }

    public void setRole() {
        String role = "user";
    }

    public void setContent() {
        String content = "";
    }

    public void setTimestamp(String s) {
        String toString = Instant.now().toString();
    }
}