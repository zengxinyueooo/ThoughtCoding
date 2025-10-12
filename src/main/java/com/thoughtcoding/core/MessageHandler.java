package com.thoughtcoding.core;



import dev.langchain4j.data.message.ChatMessage;

import java.util.function.Consumer;

/**
 * MessageHandler类用于处理消息和工具调用，通过消费者接口将消息传递给外部处理逻辑。
 */
public class MessageHandler {
    private Consumer<ChatMessage> messageConsumer;
    private Consumer<String> toolCallConsumer;

    public void setMessageConsumer(Consumer<ChatMessage> consumer) {
        this.messageConsumer = consumer;
    }

    public void setToolCallConsumer(Consumer<String> consumer) {
        this.toolCallConsumer = consumer;
    }

    public void handleMessage(ChatMessage message) {
        if (messageConsumer != null) {
            messageConsumer.accept(message);
        }
    }

    public void handleToolCall(String toolCall) {
        if (toolCallConsumer != null) {
            toolCallConsumer.accept(toolCall);
        }
    }
}