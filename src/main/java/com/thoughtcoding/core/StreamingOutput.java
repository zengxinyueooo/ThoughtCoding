package com.thoughtcoding.core;

import com.thoughtcoding.model.ChatMessage;

import java.util.function.Consumer;

/**
 * StreamingOutput类用于处理流式输出，将生成的内容逐步传递给消息处理器。
 */
public class StreamingOutput {
    private final Consumer<ChatMessage> messageHandler;
    private final StringBuilder currentContent;
    private String lastSentContent = "";


    public StreamingOutput(Consumer<ChatMessage> messageHandler) {
        this.messageHandler = messageHandler;
        this.currentContent = new StringBuilder();
    }

    public void appendContent(String token) {
        // 直接传递token，不累积内容
        ChatMessage tokenMessage = new ChatMessage("assistant", token);
        messageHandler.accept(tokenMessage);
    }

    public void complete() {
        // Send the final message
        ChatMessage finalMessage = ChatMessage.from(currentContent.toString());
        messageHandler.accept(finalMessage);

        // Reset state
        reset();
    }

    public void reset() {
        currentContent.setLength(0);
        lastSentContent = "";
    }
}