// src/main/java/com/thoughtcoding/util/StreamUtils.java
package com.thoughtcoding.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class StreamUtils {

    private StreamUtils() {
        // 工具类，防止实例化
    }

    /**
     * 将输入流转换为字符串
     */
    public static String toString(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }
            return result.toString().trim();
        }
    }

    /**
     * 异步读取流并处理每行内容
     */
    public static CompletableFuture<Void> processLinesAsync(InputStream inputStream, Consumer<String> lineProcessor) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineProcessor.accept(line);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to process stream", e);
            }
        });
    }

    /**
     * 将字符串写入输出流
     */
    public static void writeToStream(OutputStream outputStream, String content) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();
        }
    }

    /**
     * 复制输入流到输出流
     */
    public static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        output.flush();
    }

    /**
     * 安全关闭流
     */
    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 安全关闭多个流
     */
    public static void closeQuietly(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            closeQuietly(closeable);
        }
    }

    /**
     * 读取流到字节数组
     */
    public static byte[] toByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        copyStream(inputStream, buffer);
        return buffer.toByteArray();
    }

    /**
     * 创建带缓冲的读取器
     */
    public static BufferedReader createBufferedReader(InputStream inputStream) {
        return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * 创建带缓冲的写入器
     */
    public static BufferedWriter createBufferedWriter(OutputStream outputStream) {
        return new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
    }
}