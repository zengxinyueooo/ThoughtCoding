// src/main/java/com/thoughtcoding/service/AIService.java
package com.thoughtcoding.service;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;

import java.util.List;
import java.util.function.Consumer;

public interface AIService {
    List<ChatMessage> chat(String input, List<ChatMessage> history, String modelName);
    List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName);
    void setMessageHandler(Consumer<ChatMessage> handler);
    void setToolCallHandler(Consumer<ToolCall> handler);
    boolean validateModel(String modelName);
    List<String> getAvailableModels();
}