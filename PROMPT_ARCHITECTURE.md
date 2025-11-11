# 📝 ThoughtCoding 提示词架构分析

## 🎯 提示词在项目中的位置

根据代码分析，你的项目中的提示词分布在以下位置：

---

## 1️⃣ **ContextManager.java** - 项目上下文提示词

**文件路径**: `src/main/java/com/thoughtcoding/service/ContextManager.java`

**方法**: `buildProjectContextMessage()`

**提示词内容**:
```
## 🏠 当前工作环境

工作目录: /Users/zengxinyue/Desktop/仓库/ThoughtCoding

### 📋 文件系统访问权限
1. 你拥有文件系统访问权限，可以使用 read_file 和 list_directory 等工具
2. 当用户提到「这个项目」、「当前项目」或提供路径时，应该主动使用工具读取文件
3. 不要让用户手动提供文件内容，你应该自己去读取
4. 支持绝对路径和相对路径（相对于上述工作目录）
5. 遇到项目分析请求时，优先读取 README.md、pom.xml、package.json 等关键文件

### ⚠️ 输出格式要求（重要！）
1. 不要输出你的思考过程（如 "让我先..."、"我来..."）
2. 不要输出代码块（如 ```python、```java 等）
3. 不要输出你想要执行的工具调用代码
4. 使用纯文本格式输出，可以使用简单的符号（如 -、•、数字）作为列表标记
5. 直接给出分析结果，工具调用会自动在后台执行
6. 如果需要结构化输出，使用缩进和换行，不要使用 Markdown 语法

记住：你有权限也有责任主动探索文件系统，但不要把执行过程展示给用户！
```

**类型**: SystemMessage

**加载时机**: 每次 AI 调用时动态生成

---

## 2️⃣ **LangChainService.java** - 消息准备和提示词注入

**文件路径**: `src/main/java/com/thoughtcoding/service/LangChainService.java`

**方法**: `prepareMessages(String input, List<ChatMessage> history)`

**提示词注入流程**:
```java
private List<dev.langchain4j.data.message.ChatMessage> prepareMessages(
        String input, List<ChatMessage> history) {
    List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

    // 第 1 步：添加固定的项目上下文（SystemMessage）
    if (contextManager != null) {
        ChatMessage projectContext = contextManager.buildProjectContextMessage();
        if (projectContext != null) {
            messages.add(dev.langchain4j.data.message.SystemMessage.from(projectContext.getContent()));
        }
    }

    // 第 2 步：添加经过截断的历史消息
    List<ChatMessage> managedHistory = history;
    if (contextManager != null && history != null && !history.isEmpty()) {
        managedHistory = contextManager.getContextForAI(history);
    }

    if (managedHistory != null && !managedHistory.isEmpty()) {
        messages.addAll(convertToLangChainHistory(managedHistory));
    }

    // 第 3 步：添加当前用户输入
    messages.add(dev.langchain4j.data.message.UserMessage.from(input));

    return messages;
}
```

**消息顺序**:
1. **SystemMessage** (项目上下文) - 永远不会被截断
2. **历史消息** (UserMessage + AiMessage) - 可能被截断
3. **当前输入** (UserMessage)

---

## 3️⃣ **ThoughtCodingContext.java** - 上下文初始化

**文件路径**: `src/main/java/com/thoughtcoding/core/ThoughtCodingContext.java`

**方法**: `initialize()`

**初始化流程**:
```java
public static ThoughtCodingContext initialize() {
    // 1. 加载配置
    ConfigManager configManager = ConfigManager.getInstance();
    configManager.initialize("config.yaml");
    AppConfig appConfig = configManager.getAppConfig();
    
    // 2. 创建工具注册表
    ToolRegistry toolRegistry = new ToolRegistry(appConfig);
    
    // 3. 创建上下文管理器（包含提示词生成逻辑）
    ContextManager contextManager = new ContextManager(appConfig);  // 🔥 这里创建
    
    // 4. 创建 AI 服务，注入 contextManager
    AIService aiService = new LangChainService(appConfig, toolRegistry, contextManager);  // 🔥 注入
    
    // ...其他初始化
}
```

**提示词的加载时机**: 
- ✅ **不是在上下文初始化时加载的**
- ✅ **是在每次 AI 调用时动态生成的**

---

## 🔄 提示词的完整生命周期

### 阶段 1: 应用启动
```
ThoughtCodingCLI.main()
  ↓
ThoughtCodingContext.initialize()
  ↓
创建 ContextManager (但不生成提示词)
  ↓
创建 LangChainService (注入 ContextManager)
```

### 阶段 2: 用户输入
```
用户输入: "帮我分析这个项目"
  ↓
ThoughtCodingCommand.runInteractiveMode()
  ↓
aiService.streamingChat(userInput, history, modelName)
```

### 阶段 3: 提示词生成和注入
```
LangChainService.streamingChat()
  ↓
prepareMessages(input, history)
  ↓
🔥 contextManager.buildProjectContextMessage()  ← 动态生成提示词
  ↓
将 SystemMessage 添加到消息列表
  ↓
发送给 AI 模型
```

### 阶段 4: 每次调用都重复
```
每次用户输入 → 重新生成项目上下文 → 注入 SystemMessage → 调用 AI
```

---

## 📊 提示词的类型

你的项目中目前只有 **1 种提示词**：

### 项目上下文提示词 (System Prompt)
- **位置**: `ContextManager.buildProjectContextMessage()`
- **类型**: SystemMessage
- **作用**: 
  - 告诉 AI 当前工作目录
  - 说明文件系统访问权限
  - 定义输出格式规范
- **加载**: 每次 AI 调用时动态生成
- **优先级**: 最高（位于消息列表第一位）

---

## 🎨 提示词的设计模式

你的项目使用了 **动态提示词注入** 模式：

### 优点 ✅
1. **实时性**: 每次调用都获取最新的工作目录
2. **灵活性**: 可以根据上下文动态调整提示词内容
3. **不占历史**: 不会被算入历史消息，不影响上下文截断
4. **始终可见**: 即使历史被截断，AI 仍然能看到项目上下文

### 与静态提示词的对比
```
静态提示词（传统方式）:
  - 在应用启动时加载一次
  - 整个会话期间保持不变
  - 如果工作目录变化，需要重启应用

动态提示词（你的方式）:
  - 每次 AI 调用时生成
  - 可以实时反映环境变化
  - 更加灵活和强大
```

---

## 🔧 如何修改提示词？

### 修改项目上下文提示词

**步骤 1**: 编辑 `ContextManager.java`

**步骤 2**: 找到 `buildProjectContextMessage()` 方法

**步骤 3**: 修改 `context.append()` 的内容

**示例**:
```java
// 添加新的提示内容
context.append("### 🎯 代码分析指南\n");
context.append("当分析代码时，请关注：\n");
context.append("1. 代码结构和设计模式\n");
context.append("2. 潜在的性能问题\n");
context.append("3. 安全漏洞\n\n");
```

**步骤 4**: 重新编译
```bash
mvn clean compile
```

**步骤 5**: 测试
```bash
./bin/thought
```

---

## 🚀 未来可能的提示词扩展点

### 1. 工具使用提示词
**位置**: 可以在 `ToolRegistry.java` 中添加

**作用**: 为每个工具生成使用说明
```java
public String generateToolPrompt() {
    StringBuilder prompt = new StringBuilder();
    prompt.append("### 可用工具\n");
    for (BaseTool tool : getAllTools()) {
        prompt.append("- ").append(tool.getName())
              .append(": ").append(tool.getDescription()).append("\n");
    }
    return prompt.toString();
}
```

### 2. 用户偏好提示词
**位置**: 可以在 `SessionService.java` 中添加

**作用**: 根据用户历史对话学习偏好
```java
public String buildUserPreferencePrompt(String sessionId) {
    // 分析用户历史对话
    // 生成个性化提示词
    return "用户偏好：简洁输出，重视代码质量";
}
```

### 3. 任务特定提示词
**位置**: 可以在 `LangChainService.java` 中添加

**作用**: 根据用户输入类型动态添加
```java
private String detectTaskType(String input) {
    if (input.contains("分析") || input.contains("analyze")) {
        return "请进行深入的代码分析，关注架构设计。";
    } else if (input.contains("修复") || input.contains("fix")) {
        return "请直接给出修复方案，包含代码示例。";
    }
    return "";
}
```

---

## 📈 提示词的优先级顺序

当前实现的消息顺序：

```
1. SystemMessage (项目上下文)           ← 优先级最高，永远可见
2. SystemMessage (历史消息中的 system)  ← 可能被截断
3. UserMessage (历史)                   ← 可能被截断
4. AiMessage (历史)                     ← 可能被截断
5. UserMessage (当前输入)               ← 优先级次高，永远可见
```

---

## 🎯 总结

### 你的项目提示词架构特点：

1. **集中管理**: 提示词逻辑集中在 `ContextManager.buildProjectContextMessage()`
2. **动态生成**: 不是在初始化时加载，而是每次调用时生成
3. **注入时机**: 在 `LangChainService.prepareMessages()` 中注入
4. **消息顺序**: SystemMessage → 历史 → 当前输入
5. **永不截断**: 项目上下文始终在消息列表第一位，不受历史截断影响

### 关键代码流程：

```
启动应用 → 创建 ContextManager → 创建 LangChainService
                                     ↓
用户输入 → prepareMessages() → buildProjectContextMessage() (动态生成)
                                     ↓
                            SystemMessage 注入 → 发送给 AI
```

### 如果要添加新的提示词：

1. **全局提示**: 在 `ContextManager.buildProjectContextMessage()` 中添加
2. **工具提示**: 在 `prepareMessages()` 中添加新的 SystemMessage
3. **动态提示**: 根据用户输入动态生成并注入

---

**结论**: 你的提示词是在 **每次 AI 调用时动态生成**的，而不是在上下文初始化时加载。这种设计非常灵活和强大！

