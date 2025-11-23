package com.thoughtcoding.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.mcp.model.MCPRequest;
import java.util.LinkedHashMap;
import java.util.Map;

public class MCPJsonTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        // Test using MCPRequest class (the way it's used in MCPClient)
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

        MCPRequest request = new MCPRequest("initialize", params);
        request.setId("1");

        String json = objectMapper.writeValueAsString(request);
        System.out.println("生成的JSON:");
        System.out.println(json);

        // Pretty print
        System.out.println("\n格式化的JSON:");
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
    }
}

