package com.thoughtcoding.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.mcp.model.MCPRequest;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TestMCPJson {
    public static void main(String[] args) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            // 构建params对象
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", "2024-11-05");

            Map<String, Object> capabilities = new LinkedHashMap<>();
            Map<String, Object> roots = new LinkedHashMap<>();
            roots.put("listChanged", true);
            capabilities.put("roots", roots);
            capabilities.put("sampling", new LinkedHashMap<>());
            params.put("capabilities", capabilities);

            Map<String, Object> clientInfo = new LinkedHashMap<>();
            clientInfo.put("name", "ThoughtCoding");
            clientInfo.put("version", "1.0.0");
            params.put("clientInfo", clientInfo);

            // 创建MCPRequest
            MCPRequest request = new MCPRequest("initialize", params);
            request.setId("1");

            // 生成JSON（格式化）
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);

            // 生成JSON（单行）
            String compactJson = mapper.writeValueAsString(request);

            // 输出到控制台（使用System.err避免被Maven过滤）
            System.err.println("=== 格式化的MCP初始化请求JSON ===");
            System.err.println(prettyJson);
            System.err.println("\n=== 单行JSON（实际发送的格式）===");
            System.err.println(compactJson);

            // 保存到文件
            try (FileWriter writer = new FileWriter("/tmp/mcp-init-request.json")) {
                writer.write(prettyJson);
            }
            System.err.println("\n✅ JSON已保存到: /tmp/mcp-init-request.json");

            // 验证关键字段
            System.out.println("\n验证关键字段:");
            System.out.println("- jsonrpc: " + request.getJsonrpc());
            System.out.println("- id: " + request.getId());
            System.out.println("- method: " + request.getMethod());
            System.out.println("- params中是否包含protocolVersion: " + ((Map)request.getParams()).containsKey("protocolVersion"));
            System.out.println("- params中是否包含capabilities: " + ((Map)request.getParams()).containsKey("capabilities"));
            System.out.println("- params中是否包含clientInfo: " + ((Map)request.getParams()).containsKey("clientInfo"));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

