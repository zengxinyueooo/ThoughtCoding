# 🧪 AI 路径识别功能测试指南

## ✅ 修复完成

已成功实施以下修复：

### 1️⃣ 在 ContextManager 中添加固定的项目上下文

**修改文件**: `src/main/java/com/thoughtcoding/service/ContextManager.java`

**新增功能**:
- 添加了 `buildProjectContextMessage()` 方法
- 自动获取当前工作目录
- 构建包含文件访问权限说明的系统消息
- 明确告诉 AI "应该主动使用工具访问文件"

**关键特性**:
- ✅ 这个上下文**永远不会被截断**
- ✅ 每次 AI 调用都会注入
- ✅ 明确了 AI 的权限和责任

---

### 2️⃣ 在 LangChainService 中自动注入项目上下文

**修改文件**: `src/main/java/com/thoughtcoding/service/LangChainService.java`

**修改逻辑**:
```
消息准备顺序（prepareMessages）:
1. 🔥 固定的项目上下文（System Message）
2. 经过截断的历史消息
3. 当前用户输入
```

**优点**:
- ✅ AI 始终知道当前工作目录
- ✅ 不受上下文截断影响
- ✅ 无需用户重复告知路径

---

## 🧪 测试步骤

### 测试 1：基础路径识别

**启动项目**:
```bash
cd /Users/zengxinyue/Desktop/仓库/ThoughtCoding
./bin/thought
```

**测试对话**:
```
User: 帮我分析这个项目

预期行为:
✅ AI 应该自动识别工作目录为 /Users/zengxinyue/Desktop/仓库/ThoughtCoding
✅ AI 应该主动使用工具读取 README.md、pom.xml 等文件
✅ AI 不应该让你手动提供文件内容

错误行为（修复前）:
❌ AI 回答："请提供项目文件"
❌ AI 回答："我无法访问你的文件系统"
```

---

### 测试 2：明确路径分析

**测试对话**:
```
User: 分析 /Users/zengxinyue/Desktop/仓库/ThoughtCoding 项目

预期行为:
✅ AI 立即识别这就是当前工作目录
✅ 使用工具读取文件
✅ 基于实际内容进行分析

你应该在输出中看到类似信息:
📂 正在读取项目文件...
📄 README.md
📄 pom.xml
📊 这是一个基于 Java 17 的 CLI 工具项目...
```

---

### 测试 3：长对话后的记忆保持

**测试流程**:
```bash
# 1. 开始对话
User: 分析这个项目
AI: [分析项目内容...]

# 2. 进行 10-15 轮其他对话
User: Java 17 有什么新特性？
AI: [回答...]

User: Maven 如何配置多模块项目？
AI: [回答...]

# ... 继续多轮对话 ...

# 3. 再次提到项目
User: 刚才你分析的那个项目用了什么框架？

预期行为:
✅ AI 仍然记得项目位置
✅ AI 可以回答之前分析过的内容
✅ 或者 AI 会再次读取文件（如果历史被截断）

关键点: AI 不应该说"我不知道你在说哪个项目"
```

---

### 测试 4：验证项目上下文注入

**查看日志**:
```bash
# 在另一个终端查看详细日志
tail -f logs/thoughtcoding.log | grep -E "项目上下文|工作目录|SystemMessage"
```

**预期输出**:
```
[INFO] 构建项目上下文: /Users/zengxinyue/Desktop/仓库/ThoughtCoding
[DEBUG] SystemMessage: ## 🏠 当前工作环境 ...
```

---

### 测试 5：MCP 工具调用验证

**启用详细日志**:

在 `config.yaml` 中临时启用调试：
```yaml
logging:
  level: DEBUG  # 如果有这个配置
```

**测试对话**:
```
User: 列出项目的所有 Java 文件

预期行为:
✅ AI 调用 list_directory 或 read_file 工具
✅ 返回实际的文件列表

日志中应该看到:
🔧 Tool call: list_directory(path=/Users/zengxinyue/Desktop/仓库/ThoughtCoding/src)
📊 Tool result: [AIService.java, ContextManager.java, ...]
```

---

## 📊 对比测试结果

### 修复前 ❌

```
User: 分析这个项目
AI: 由于我无法直接访问你的本地文件系统，请提供以下信息：
    1. README.md 文件内容
    2. 项目结构
    3. 核心技术栈
    ...
```

**问题**: AI 不知道自己有文件访问能力

---

### 修复后 ✅

```
User: 分析这个项目
AI: 好的，我来分析 /Users/zengxinyue/Desktop/仓库/ThoughtCoding 项目。

    📂 正在读取项目信息...
    
    [使用工具: read_file(README.md)]
    [使用工具: read_file(pom.xml)]
    
    📊 项目分析：
    
    **项目名称**: ThoughtCoding
    **类型**: 企业级 AI 编程助手 CLI 工具
    **语言**: Java 17
    **构建工具**: Maven
    **核心框架**: LangChain4j 0.29.1
    
    **技术特点**:
    1. 基于 MCP 协议的工具生态
    2. 流式交互，Token-by-Token 实时输出
    3. 智能上下文管理
    ...
```

**改进**: AI 主动使用工具，直接分析项目

---

## 🔍 故障排查

### 问题 1: AI 仍然不识别路径

**检查点**:
```bash
# 1. 确认编译成功
mvn clean compile

# 2. 确认使用最新版本
./bin/thought --version

# 3. 查看日志
cat logs/thoughtcoding.log | grep "项目上下文"
```

**可能原因**:
- 使用了旧的编译版本（未重新编译）
- ContextManager 未正确注入到 LangChainService

---

### 问题 2: AI 说"无法访问文件系统"

**检查点**:
```bash
# 查看 MCP 服务器是否连接
ps aux | grep mcp

# 查看配置
cat src/main/resources/config.yaml | grep -A 5 "fileManager"
```

**确认配置**:
```yaml
tools:
  fileManager:
    enabled: true  # ✅ 必须是 true
```

---

### 问题 3: 工具调用失败

**测试 MCP 连接**:
```bash
# 如果有独立的 MCP 服务器进程
curl http://localhost:3000/mcp/tools  # 示例端口

# 或查看日志
grep "MCP" logs/thoughtcoding.log
```

**应该看到**:
```
✅ MCP Server [filesystem] connected: 15 tools registered
   - read_file
   - write_file
   - list_directory
   ...
```

---

## 📈 成功指标

修复成功的标志：

- ✅ AI 在第一次对话时就知道当前工作目录
- ✅ AI 主动使用工具读取文件，而不是让用户提供
- ✅ 长对话后 AI 仍然记得项目位置
- ✅ 用户体验：无需重复告知路径
- ✅ 日志中能看到项目上下文注入的痕迹

---

## 🎯 下一步优化建议

如果基础修复工作正常，可以进一步优化：

### 优化 1：增加工作目录环境变量支持

```java
// 支持从环境变量覆盖工作目录
String cwd = System.getenv("THOUGHTCODING_WORKSPACE");
if (cwd == null || cwd.isEmpty()) {
    cwd = System.getProperty("user.dir");
}
```

### 优化 2：添加项目类型自动识别

```java
// 自动识别项目类型（Java/Python/Node.js）
if (new File(cwd, "pom.xml").exists()) {
    context.append("- 项目类型: Maven Java 项目\n");
} else if (new File(cwd, "package.json").exists()) {
    context.append("- 项目类型: Node.js 项目\n");
}
```

### 优化 3：缓存项目上下文

```java
// 避免每次都重新构建
private ChatMessage cachedProjectContext = null;

public ChatMessage buildProjectContextMessage() {
    if (cachedProjectContext == null) {
        cachedProjectContext = buildProjectContextInternal();
    }
    return cachedProjectContext;
}
```

---

## 📚 相关文档

- [AI 路径识别问题诊断与解决方案](./AI_PATH_RECOGNITION_FIX.md)
- [上下文策略详解](./上下文策略详解.md)
- [MCP 工具使用指南](./MCP_TOOLS_USAGE_GUIDE.md)

---

## ✅ 测试检查清单

完成以下测试项：

- [ ] 基础路径识别测试
- [ ] 明确路径分析测试
- [ ] 长对话记忆保持测试
- [ ] 项目上下文注入验证
- [ ] MCP 工具调用验证
- [ ] 对比测试结果（修复前后）

**全部通过** = 修复成功！ 🎉

---

## 📝 反馈

如果测试过程中遇到任何问题，请记录：

1. **现象描述**: 
2. **测试输入**: 
3. **实际输出**: 
4. **预期输出**: 
5. **日志信息**: 

这将帮助进一步诊断和优化。

