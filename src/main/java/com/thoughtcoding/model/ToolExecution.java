package com.thoughtcoding.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 工具执行请求模型
 * 包含工具名称、描述和参数
 */
public record ToolExecution(
    String toolName,
    String description,
    Map<String, Object> parameters,
    boolean isComplete
) {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将参数转换为 JSON 字符串
     */
    public String arguments() {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (Exception e) {
            // 降级：简单的 JSON 转换
            return convertToSimpleJson(parameters);
        }
    }

    private String convertToSimpleJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }

        StringBuilder json = new StringBuilder("{");
        params.forEach((key, value) -> {
            json.append("\"").append(key).append("\":");
            if (value instanceof String) {
                json.append("\"").append(escape((String) value)).append("\",");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value).append(",");
            } else if (value == null) {
                json.append("null,");
            } else {
                json.append("\"").append(value.toString()).append("\",");
            }
        });
        if (json.length() > 1) {
            json.setLength(json.length() - 1); // 移除最后的逗号
        }
        json.append("}");
        return json.toString();
    }

    private String escape(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}

