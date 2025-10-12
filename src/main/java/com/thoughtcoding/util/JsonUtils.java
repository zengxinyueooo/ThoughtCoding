package com.thoughtcoding.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;


import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * JSON处理工具类，基于Jackson库
 */
public class JsonUtils {
    private static final ObjectMapper mapper;

    static {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private JsonUtils() {
        // 工具类，防止实例化
    }

    /**
     * 将对象转换为JSON字符串
     */
    public static String toJson(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert object to JSON", e);
        }
    }

    /**
     * 将JSON字符串转换为对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return mapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }

    /**
     * 从文件读取JSON并转换为对象
     */
    public static <T> T fromJsonFile(String filePath, Class<T> clazz) throws IOException {
        return fromJsonFile(Path.of(filePath), clazz);
    }

    public static <T> T fromJsonFile(Path filePath, Class<T> clazz) throws IOException {
        return mapper.readValue(filePath.toFile(), clazz);
    }

    /**
     * 将对象写入JSON文件
     */
    public static void toJsonFile(String filePath, Object object) throws IOException {
        toJsonFile(Path.of(filePath), object);
    }

    public static void toJsonFile(Path filePath, Object object) throws IOException {
        FileUtils.createDirectories(filePath.getParent().toString());
        mapper.writeValue(filePath.toFile(), object);
    }

    /**
     * 美化JSON字符串
     */
    public static String prettyPrint(String json) {
        try {
            Object jsonObject = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        } catch (JsonProcessingException e) {
            return json; // 如果美化失败，返回原始JSON
        }
    }

    /**
     * 检查字符串是否为有效JSON
     */
    public static boolean isValidJson(String json) {
        try {
            mapper.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 将对象转换为字节数组
     */
    public static byte[] toJsonBytes(Object object) {
        try {
            return mapper.writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert object to JSON bytes", e);
        }
    }

    /**
     * 从字节数组解析JSON对象
     */
    public static <T> T fromJsonBytes(byte[] jsonBytes, Class<T> clazz) {
        try {
            return mapper.readValue(jsonBytes, clazz);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse JSON bytes", e);
        }
    }

    /**
     * 获取ObjectMapper实例（用于高级操作）
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }
}