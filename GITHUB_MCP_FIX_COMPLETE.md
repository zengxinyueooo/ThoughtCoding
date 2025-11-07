# GitHub MCP工具参数问题 - 完整修复报告

## 🎯 问题的真正根源

经过深入分析，我发现了**两层问题**：

### 问题1：参数传递链路错误 ✅ 已修复
**原因**：MCPToolAdapter将JSON参数错误地包装成 `{"input": "..."}`  
**修复**：添加智能JSON解析，直接传递原始参数Map

### 问题2：AI不知道需要什么参数 ✅ 已修复
**根本原因**：系统提示词中**没有包含MCP工具的参数schema信息**！

从错误日志看：
```
📝 参数: {}  // AI传递了空参数！
```

这是因为AI在生成工具调用时，不知道GitHub的`search_repositories`工具需要什么参数。

**为什么会这样？**

查看代码发现，`buildSystemPromptWithTools()`方法中：
- ✅ 内置工具（file_manager等）有硬编码的参数说明
- ❌ MCP工具没有参数说明 - 即使MCP工具包含了完整的`inputSchema`！

## 🔧 修复方案

### 修复1：MCPToolAdapter.java - 智能参数解析

```java
private Map<String, Object> parseInputToArguments(String input) {
    // 优先检测JSON格式
    if (input.trim().startsWith("{")) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(input, Map.class); // ✅ 直接解析
        } catch (Exception e) {
            // 降级处理
        }
    }
    
    // 非JSON才包装成input字段
    Map<String, Object> arguments = new HashMap<>();
    arguments.put("input", input);
    return arguments;
}
```

### 修复2：LangChainService.java - 添加MCP工具参数Schema

```java
for (var tool : toolRegistry.getAllTools()) {
    prompt.append("**").append(tool.getName()).append("**\n");
    prompt.append("- 描述: ").append(tool.getDescription()).append("\n");
    
    // 🔥 新增：为MCP工具添加参数schema
    if (tool instanceof com.thoughtcoding.mcp.MCPToolAdapter) {
        com.thoughtcoding.mcp.MCPToolAdapter mcpTool = 
            (com.thoughtcoding.mcp.MCPToolAdapter) tool;
        Object inputSchema = mcpTool.getOriginalTool().getInputSchema();
        
        if (inputSchema != null) {
            // 将schema格式化为JSON并添加到提示词
            String schemaJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(inputSchema);
            prompt.append("- 参数Schema:\n```json\n")
                  .append(schemaJson).append("\n```\n");
        }
    }
}
```

### 修复3：MCPService.java - 同步参数解析逻辑

确保通过MCPService调用的工具也能正确解析JSON参数。

## 📊 修复后的完整流程

```
用户输入: "帮我查看我的github仓库"
    ↓
系统提示词包含:
    工具: search_repositories
    参数Schema: {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Search query"
            }
        },
        "required": ["query"]
    }
    ↓
AI理解参数要求，生成:
    {
        "tool_name": "search_repositories",
        "parameters": {
            "query": "user:zengxinyue"  ✅ 正确的参数！
        }
    }
    ↓
LangChainService转换为JSON字符串: '{"query":"user:zengxinyue"}'
    ↓
MCPToolAdapter智能解析:
    检测到JSON格式 → 解析为 Map{"query": "user:zengxinyue"}
    ↓
MCP客户端调用GitHub API:
    传递正确参数 {"query": "user:zengxinyue"} ✅
    ↓
成功获取GitHub仓库列表！🎉
```

## 🚀 如何测试

### 方式1：直接运行（推荐）

```bash
cd /Users/zengxinyue/Desktop/仓库/ThoughtCoding
./bin/thought
```

然后输入：
```
帮我查看我的github仓库
```

### 方式2：查看系统提示词（验证修复）

启动程序后，输入：
```
/debug
```

检查输出中是否包含GitHub工具的参数Schema信息。

## ✅ 预期结果

修复后，你应该看到：

1. **系统提示词中包含MCP工具参数**：
   ```
   **search_repositories**
   - 描述: Search for repositories
   - 参数Schema:
   ```json
   {
     "type": "object",
     "properties": {
       "query": {
         "type": "string",
         "description": "Search query"
       }
     }
   }
   ```

2. **AI正确传递参数**：
   ```
   🔧 执行工具: search_repositories
   📝 参数: {query=user:zengxinyue}  ✅ 不再是空的{}
   ```

3. **成功调用GitHub API**：
   ```
   ✅ 工具执行成功:
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   [GitHub仓库列表]
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   ```

## 📝 修改的文件清单

1. ✅ `MCPToolAdapter.java` - 添加智能参数解析
2. ✅ `MCPService.java` - 同步参数解析逻辑  
3. ✅ `LangChainService.java` - 为MCP工具添加参数Schema到系统提示词

## 🔍 技术细节

**为什么要在系统提示词中包含参数Schema？**

AI模型（如DeepSeek）是通过理解系统提示词来决定：
- 调用哪个工具
- 传递什么参数

如果提示词中没有参数说明，AI只能"猜测"，通常会传递空参数`{}`。

MCP协议设计时就考虑到了这一点，每个工具都包含`inputSchema`字段来描述参数结构。我们只需要将这个schema信息传递给AI即可。

## 🎉 总结

这是一个典型的**信息传递问题**：
- MCP服务器知道需要什么参数（inputSchema）
- 但AI不知道（系统提示词中没有）
- 导致AI传递空参数
- GitHub API报错"Required字段缺失"

修复后，完整的信息链路已打通：
```
MCP Server → inputSchema → 系统提示词 → AI理解 → 正确参数 → 成功调用 ✅
```

---

**编译状态**: ✅ 成功  
**修复日期**: 2025-11-07  
**影响范围**: 所有MCP工具的参数传递

