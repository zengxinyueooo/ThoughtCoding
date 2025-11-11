# AI 路径识别问题诊断与解决方案

## 🔍 问题描述

**现象**：虽然加载了文件管理 MCP，并明确告诉 AI 项目路径 `/Users/zengxinyue/Desktop/仓库/ThoughtCoding`，但 AI 仍然无法识别和访问项目文件。

---

## 🎯 根本原因分析

### 原因 1：上下文截断导致路径信息丢失

**当前配置**：
```yaml
context:
  strategy: "token_based"
  maxContextTokens: 3000
  maxHistoryTurns: 10
```

**问题**：
- 在长对话中，早期提到的路径可能被截断
- AI 的"记忆窗口"只有 3000 tokens
- 第 1 轮提到的路径，在第 10 轮后可能已经不在上下文中

**验证方法**：
```bash
# 运行项目并观察统计信息
./bin/thought

# 进行多轮对话后，检查日志输出
📊 上下文统计:
  完整历史: 20 条消息 (~4500 tokens)
  发送历史: 12 条消息 (~2900 tokens)  # ❌ 早期消息被截断
  节省: 1600 tokens (36%)
```

---

### 原因 2：系统提示词不足

AI 可能缺少关于"如何使用 MCP 工具访问文件"的明确指令。

**检查位置**：
- `src/main/java/com/thoughtcoding/ai/PromptBuilder.java`
- 或系统提示词构建逻辑

**应该包含的指令**：
```
你是一个能够访问文件系统的 AI 助手。当用户提到项目路径时：
1. 立即使用 list_directory 工具查看目录结构
2. 使用 read_file 工具读取关键文件（README.md, pom.xml, config.yaml）
3. 主动分析项目内容，不要让用户手动提供信息
4. 记住：你有权限也有责任主动探索项目
```

---

### 原因 3：MCP 工具未正确注册或调用

**可能的问题**：
- MCP 服务器虽然启动，但工具未正确暴露给 AI
- AI 不知道有哪些工具可用
- 工具描述不够清晰，AI 不知道何时调用

**检查方法**：
```bash
# 启动 ThoughtCoding 后，查看日志
grep "MCP" logs/thoughtcoding.log

# 应该看到类似输出
✅ MCP Server [filesystem] connected: 15 tools registered
   - read_file
   - write_file
   - list_directory
   - create_directory
   ...
```

---

## 🔧 解决方案

### 方案 1：优化上下文配置（临时）

**调整策略**，增加上下文窗口：

```yaml
# config.yaml
context:
  enabled: true
  strategy: "token_based"
  maxContextTokens: 5000  # 从 3000 增加到 5000
  maxHistoryTurns: 15     # 从 10 增加到 15
  reserveTokens: 1000
  showStatistics: true
```

**优点**：
- ✅ 快速修复，无需改代码
- ✅ 保留更长的历史记录

**缺点**：
- ❌ 治标不治本
- ❌ 增加 Token 消耗和成本

---

### 方案 2：改进系统提示词（推荐）

找到系统提示词构建逻辑（通常在 `PromptBuilder.java` 或类似文件），添加明确的文件访问指令。

**修改示例**：

```java
// PromptBuilder.java
public String buildSystemPrompt(List<ToolSpecification> tools) {
    StringBuilder prompt = new StringBuilder();
    
    prompt.append("你是 ThoughtCoding AI 助手，具有以下能力：\n\n");
    
    // ⭐ 新增：明确的文件访问指令
    prompt.append("## 文件系统访问权限\n");
    prompt.append("当用户提到项目路径或要求分析项目时，你应该：\n");
    prompt.append("1. 立即使用 list_directory 工具查看目录结构\n");
    prompt.append("2. 读取关键文件（README.md, pom.xml, package.json 等）\n");
    prompt.append("3. 基于实际文件内容进行分析，而不是猜测或询问用户\n");
    prompt.append("4. 记住：你有权限也有责任主动探索文件系统\n\n");
    
    // 原有的工具列表
    prompt.append("## 可用工具\n");
    for (ToolSpecification tool : tools) {
        prompt.append("- ").append(tool.name()).append(": ")
              .append(tool.description()).append("\n");
    }
    
    return prompt.toString();
}
```

---

### 方案 3：添加"项目上下文"固定提示（最佳）

在每次 AI 调用时，动态添加**当前工作目录信息**到上下文中，确保这个信息永远不会被截断。

**实现位置**：`src/main/java/com/thoughtcoding/context/ContextManager.java`

```java
// ContextManager.java
public List<ChatMessage> getContextForAI(String userInput) {
    List<ChatMessage> messages = new ArrayList<>();
    
    // ⭐ 新增：固定的项目上下文（不会被截断）
    String projectContext = buildProjectContext();
    if (projectContext != null) {
        messages.add(SystemMessage.from(projectContext));
    }
    
    // 原有的历史消息处理
    List<ChatMessage> history = truncateHistory();
    messages.addAll(history);
    
    // 用户当前输入
    messages.add(UserMessage.from(userInput));
    
    return messages;
}

private String buildProjectContext() {
    String cwd = System.getProperty("user.dir");
    return String.format(
        "## 当前工作环境\n" +
        "- 工作目录: %s\n" +
        "- 你可以使用文件系统工具访问这个目录下的所有文件\n" +
        "- 当用户提到'这个项目'或'当前项目'时，指的就是这个目录\n",
        cwd
    );
}
```

**优点**：
- ✅ 工作目录信息永远存在
- ✅ 不受上下文截断影响
- ✅ AI 始终知道"当前在哪里"

---

### 方案 4：改进 MCP 工具描述

确保 MCP 工具的描述足够清晰，让 AI 知道何时调用。

**检查位置**：MCP 服务器的工具定义（可能在独立的 MCP 服务进程中）

**应该是这样的描述**：

```json
{
  "name": "read_file",
  "description": "读取指定路径的文件内容。当用户提到文件名或要求查看文件时，立即使用此工具。支持绝对路径和相对路径（相对于当前工作目录）。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "文件的绝对路径或相对路径，例如 './README.md' 或 '/Users/user/project/README.md'"
      }
    },
    "required": ["path"]
  }
}
```

**关键点**：
- 描述中明确"何时使用"
- 说明路径格式（绝对/相对）
- 提供示例

---

## 🧪 测试验证

### 测试 1：基础路径识别

```bash
./bin/thought

# 测试对话
User: 分析 /Users/zengxinyue/Desktop/仓库/ThoughtCoding 项目
AI: [应该立即调用工具] 正在读取项目文件...
    📂 使用工具: list_directory
    📄 使用工具: read_file (README.md)
    📄 使用工具: read_file (pom.xml)
```

**预期结果**：AI 应该主动调用工具，而不是让你提供文件内容。

---

### 测试 2：上下文保持

```bash
# 进行 10 轮对话后
User: 还记得我一开始让你分析的项目路径吗？
AI: 是的，是 /Users/zengxinyue/Desktop/仓库/ThoughtCoding 项目...
```

**预期结果**：即使在长对话后，AI 仍然记得路径（如果实施了方案 3）。

---

### 测试 3：工具调用日志

```bash
# 查看是否成功调用 MCP 工具
grep "Tool call" logs/thoughtcoding.log

# 应该看到
2025-11-11 10:30:15 [INFO] Tool call: list_directory(/Users/zengxinyue/Desktop/仓库/ThoughtCoding)
2025-11-11 10:30:16 [INFO] Tool result: [README.md, pom.xml, src/, ...]
2025-11-11 10:30:17 [INFO] Tool call: read_file(/Users/zengxinyue/Desktop/仓库/ThoughtCoding/README.md)
```

---

## 📊 诊断检查清单

### ✅ 配置检查

- [ ] `config.yaml` 中 `fileManager.enabled: true`
- [ ] MCP 服务器已启动并连接
- [ ] 工具列表包含 `read_file`, `list_directory` 等
- [ ] `maxContextTokens` 是否足够（建议 ≥ 4000）

### ✅ 代码检查

- [ ] 系统提示词是否包含文件访问指令
- [ ] 是否有固定的"当前工作目录"上下文
- [ ] MCP 工具描述是否清晰
- [ ] 工具调用日志是否正常

### ✅ 运行时检查

```bash
# 1. 检查 MCP 连接状态
ps aux | grep mcp

# 2. 检查日志
tail -f logs/thoughtcoding.log

# 3. 测试工具调用
./bin/thought
> 列出当前目录的文件
```

---

## 🎯 推荐的实施顺序

1. **立即实施 - 方案 3**（添加固定项目上下文）
   - 影响最小
   - 效果最好
   - 不依赖上下文窗口大小

2. **配合实施 - 方案 2**（改进系统提示词）
   - 让 AI 更主动
   - 提升用户体验

3. **可选实施 - 方案 1**（增加上下文窗口）
   - 仅作为临时补充
   - 不要依赖这个方案

4. **长期优化 - 方案 4**（改进 MCP 工具描述）
   - 需要修改 MCP 服务器
   - 提升整体工具可用性

---

## 🔗 相关文档

- [上下文策略详解](./上下文策略详解.md)
- [MCP 工具使用指南](./MCP_TOOLS_USAGE_GUIDE.md)
- [MCP 架构分析](./MCP_ARCHITECTURE_ANALYSIS.md)

---

## 📝 总结

**问题的本质**：AI 需要**明确的指令**和**持久的上下文**才能正确使用文件系统工具。

**核心解决思路**：
1. 确保关键信息（工作目录）永远不会被截断
2. 在系统提示词中明确告诉 AI "你有权限，应该主动使用工具"
3. 优化工具描述，让 AI 知道何时调用

**最佳实践**：
- ✅ 使用固定的项目上下文（方案 3）
- ✅ 改进系统提示词（方案 2）
- ⚠️ 适度增加上下文窗口（方案 1）
- 📚 持续优化工具描述（方案 4）

---

**开始修复**：建议先实施方案 3，然后测试验证。如果需要具体的代码修改，请告诉我！

