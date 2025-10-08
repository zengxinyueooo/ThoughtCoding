// src/main/java/com/thoughtcoding/model/SessionData.java
package com.thoughtcoding.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SessionData {
    private final String sessionId;
    private final String title;
    private final LocalDateTime createdTime;
    private LocalDateTime lastAccessTime;
    private final List<ChatMessage> messages;
    private final String modelName;

    public SessionData(String sessionId, String title, String modelName) {
        this.sessionId = sessionId;
        this.title = title;
        this.modelName = modelName;
        this.createdTime = LocalDateTime.now();
        this.lastAccessTime = LocalDateTime.now();
        this.messages = new ArrayList<>();
    }

    public SessionData(String sessionId, String title, String modelName,
                       LocalDateTime createdTime, LocalDateTime lastAccessTime,
                       List<ChatMessage> messages) {
        this.sessionId = sessionId;
        this.title = title;
        this.modelName = modelName;
        this.createdTime = createdTime;
        this.lastAccessTime = lastAccessTime;
        this.messages = new ArrayList<>(messages);
    }

    // Getters
    public String getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public String getModelName() { return modelName; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public LocalDateTime getLastAccessTime() { return lastAccessTime; }
    public List<ChatMessage> getMessages() { return new ArrayList<>(messages); }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        lastAccessTime = LocalDateTime.now();
    }

    public void updateLastAccessTime() {
        lastAccessTime = LocalDateTime.now();
    }

    public int getMessageCount() {
        return messages.size();
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("Session{id=%s, title=%s, messages=%d, lastAccess=%s}",
                sessionId.substring(0, 8), title, messages.size(), lastAccessTime);
    }
}