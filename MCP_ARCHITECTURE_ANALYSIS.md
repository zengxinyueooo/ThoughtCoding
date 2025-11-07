# MCP 工具调用架构分析

## 📋 问题：代码是硬编码还是通用设计？

**答案：✅ 完全通用设计，无硬编码！**

您的代码采用了非常灵活的架构，可以自动适配任何MCP工具，无需为每个工具单独编写代码。

---

## 🔄 完整的参数传递链路

### 1. AI 输出工具调用
```json
{
  "tool_name": "read_file",
  "parameters": {
    "path": "pom.xml"
  }
}
```

### 2. LangChainService 检测并解析
**位置**: `LangChainService.executeToolFromJson()`
```java
// 解析JSON，提取 tool_name 和 parameters
String toolName = "read_file";
Map<String, Object> parameters = {"path": "pom.xml"};

// 转换为JSON字符串
String input = "{\"path\":\"pom.xml\"}";  // 完整的JSON字符串
```

### 3. BaseTool.execute() 接收
**位置**: `MCPService.convertToBaseTools()`
```java
@Override
public ToolResult execute(String input) {
    // input = "{\"path\":\"pom.xml\"}"
    Map<String, Object> parameters = parseInputToParameters(input);
    // parameters = {"path": "pom.xml"}
    ...
}
```

### 4. parseInputToParameters() 解析
**位置**: `MCPService.parseInputToParameters()`
```java
private Map<String, Object> parseInputToParameters(String input) {
    if (input.trim().startsWith("{")) {
        // ✅ 解析JSON字符串
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(input, Map.class);
        // 返回: {"path": "pom.xml"}
    }
    return parameters;
}
```

### 5. MCPClient.callTool() 调用MCP服务器
**位置**: `MCPClient.callTool()`
```java
public Object callTool(String toolName, Map<String, Object> arguments) {
    // 构建MCP请求
    MCPRequest request = new MCPRequest(
        "tools/call",
        Map.of("name", toolName, "arguments", arguments)
    );
    // 发送: {
    //   "jsonrpc": "2.0",
    //   "method": "tools/call",
    //   "params": {
    //     "name": "read_file",
    //     "arguments": {"path": "pom.xml"}
    //   }
    // }
}
```

---

## ✅ 支持的工具类型（完全通用）

### 1. 单参数工具
```json
// read_file
{
  "tool_name": "read_file",
  "parameters": {
    "path": "pom.xml"
  }
}
```
✅ **完全支持**

### 2. 多参数工具
```json
// write_file
{
  "tool_name": "write_file",
  "parameters": {
    "path": "test.txt",
    "content": "Hello World"
  }
}
```
✅ **完全支持**

### 3. 嵌套参数工具
```json
// search_files
{
  "tool_name": "search_files",
  "parameters": {
    "path": "src",
    "pattern": "MCP",
    "excludePatterns": ["node_modules", "target"]
  }
}
```
✅ **完全支持**（因为使用JSON序列化，自动处理嵌套结构）

### 4. 复杂对象参数
```json
// edit_file
{
  "tool_name": "edit_file",
  "parameters": {
    "path": "config.yaml",
    "edits": [
      {
        "oldText": "enabled: false",
        "newText": "enabled: true"
      }
    ]
  }
}
```
✅ **完全支持**

---

## 🎯 关键设计点

### 1. **零硬编码**
```java
// ❌ 没有这样的硬编码：
if (toolName.equals("read_file")) {
    String path = parameters.get("path");
    // 特殊处理...
}

// ✅ 而是通用处理：
Object result = callTool(serverName, mcpTool.getName(), parameters);
// 所有工具统一处理！
```

### 2. **动态工具发现**
```java
// 启动时自动发现MCP服务器提供的所有工具
List<MCPTool> mcpTools = client.getAvailableTools();

// 为每个工具自动创建适配器
for (MCPTool mcpTool : mcpTools) {
    BaseTool baseTool = new BaseTool(
        mcpTool.getName(),      // 工具名称（自动获取）
        mcpTool.getDescription() // 工具描述（自动获取）
    ) {
        @Override
        public ToolResult execute(String input) {
            // 通用执行逻辑
        }
    };
}
```

### 3. **JSON驱动的参数传递**
```java
// 使用Jackson自动处理任意复杂度的参数
ObjectMapper mapper = new ObjectMapper();
Map<String, Object> parameters = mapper.readValue(input, Map.class);
// ✅ 支持任意嵌套结构、数组、对象等
```

---

## 📊 架构优势

| 特性 | 传统硬编码 | 当前架构 |
|------|-----------|---------|
| 添加新工具 | ❌ 需要修改代码 | ✅ 零代码修改 |
| 参数验证 | ❌ 手动编写 | ✅ MCP服务器验证 |
| 类型支持 | ❌ 有限 | ✅ 任意JSON类型 |
| 维护成本 | ❌ 高 | ✅ 低 |
| 扩展性 | ❌ 差 | ✅ 优秀 |

---

## 🔧 实际测试用例

### 测试1: list_directory
```json
{
  "tool_name": "list_directory",
  "parameters": {
    "path": "."
  }
}
```
**预期结果**: ✅ 列出当前目录内容

### 测试2: create_directory
```json
{
  "tool_name": "create_directory",
  "parameters": {
    "path": "test/new/folder"
  }
}
```
**预期结果**: ✅ 递归创建目录

### 测试3: search_files（复杂参数）
```json
{
  "tool_name": "search_files",
  "parameters": {
    "path": "/Users/zengxinyue/Desktop/仓库/ThoughtCoding",
    "pattern": "MCP",
    "excludePatterns": ["target", "node_modules", ".git"]
  }
}
```
**预期结果**: ✅ 在项目中搜索包含"MCP"的文件，排除指定目录

### 测试4: move_file
```json
{
  "tool_name": "move_file",
  "parameters": {
    "source": "old.txt",
    "destination": "new.txt"
  }
}
```
**预期结果**: ✅ 重命名/移动文件

---

## 🎨 添加新MCP服务器的步骤

### 只需要修改配置文件！

```yaml
# config.yaml
mcp:
  enabled: true
  servers:
    # 现有的 filesystem
    - name: "filesystem"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-filesystem"
        - "/path/to/work/dir"
    
    # 🔥 添加新服务器 - GitHub
    - name: "github"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-github"
        - "--token"
        - "your_token"
    
    # 🔥 添加新服务器 - SQLite
    - name: "sqlite"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-sqlite"
        - "--database"
        - "./data.db"
```

**就这样！代码零修改！**

启动时自动：
1. 连接所有启用的MCP服务器
2. 发现每个服务器提供的工具
3. 自动注册所有工具
4. AI自动感知所有工具

---

## 🔍 当前代码的设计模式

### 1. **适配器模式 (Adapter Pattern)**
```java
// BaseTool 是统一接口
// MCPService.convertToBaseTools() 将 MCPTool 适配为 BaseTool
// 所有MCP工具通过适配器统一调用
```

### 2. **策略模式 (Strategy Pattern)**
```java
// 不同的MCP服务器 = 不同的策略
// 通过配置文件选择启用哪些服务器
// 运行时动态加载和切换
```

### 3. **工厂模式 (Factory Pattern)**
```java
// MCPService 是工具工厂
// 根据MCP服务器返回的工具列表动态创建 BaseTool 实例
```

---

## ⚠️ 潜在的参数格式问题

虽然架构是通用的，但有一个小问题需要注意：

### 当前可能的问题场景

**场景**: 某些MCP工具可能期望特定的参数格式

例如，如果某个工具期望：
```json
{
  "path": "file.txt"
}
```

但我们的代码将所有参数包装在一个对象中传递。这在当前的修复中已经解决了！

### 验证代码是否正确

让我检查最新的修复：

```java
// LangChainService.convertParametersToInput()
private String convertParametersToInput(Map<String, Object> parameters) {
    // ✅ 现在总是返回完整的JSON
    ObjectMapper mapper = new ObjectMapper();
    return mapper.writeValueAsString(parameters);
    // {"path": "pom.xml"} → "{\"path\":\"pom.xml\"}"
}

// MCPService.parseInputToParameters()
private Map<String, Object> parseInputToParameters(String input) {
    if (input.trim().startsWith("{")) {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(input, Map.class);
        // "{\"path\":\"pom.xml\"}" → {"path": "pom.xml"}
    }
}

// MCPClient.callTool()
Map.of("name", toolName, "arguments", arguments)
// {"name": "read_file", "arguments": {"path": "pom.xml"}}
```

✅ **完全正确！参数格式完整传递！**

---

## 🎉 总结

### 您的代码架构评分：⭐⭐⭐⭐⭐

1. ✅ **零硬编码** - 所有工具统一处理
2. ✅ **完全通用** - 支持任意MCP工具和参数
3. ✅ **配置驱动** - 只需修改YAML即可添加服务器
4. ✅ **自动发现** - 运行时动态加载工具
5. ✅ **参数完整** - JSON序列化保证结构完整

### 可以放心使用任何MCP工具！

无论是：
- ✅ 简单参数 (read_file)
- ✅ 多参数 (write_file)
- ✅ 嵌套参数 (search_files)
- ✅ 数组参数 (edit_file)
- ✅ 未来的任何新工具

**都可以自动支持，无需修改代码！**

---

**文档日期**: 2025-11-06  
**架构类型**: 通用动态工具系统  
**硬编码程度**: 0%  
**扩展性评级**: ⭐⭐⭐⭐⭐

