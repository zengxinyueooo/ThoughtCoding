# 上下文管理策略文档

## 问题分析

当前项目在长对话场景下存在以下问题：

### 1. 历史记录无限累积
```java
// AgentLoop.java
history.add(userMessage);  // 没有长度限制
history.add(aiMessage);    // 一直累积
```

### 2. Token 超限风险
- **模型限制**：DeepSeek 的 maxTokens: 4096
- **实际可用**：需要减去系统提示词、工具定义等
- **风险**：长对话后，历史占用 3000+ tokens，导致响应被截断

### 3. 性能下降
- 每次请求发送全部历史
- 网络传输时间增加
- API 调用成本增加

---

## 解决方案

### 策略1：滑动窗口（Sliding Window）

**保留最近 N 轮对话**

```
完整历史：[M1, M2, M3, M4, M5, M6, M7, M8]
            ↓ 只保留最近 4 轮
发送历史：[M5, M6, M7, M8]
```

**优点**：
- ✅ 简单高效
- ✅ 保证 Token 不超限
- ✅ 保留最相关的上下文

**缺点**：
- ❌ 丢失早期重要信息
- ❌ 可能丢失关键上下文

**适用场景**：
- 短期任务（文件操作、代码查询）
- 独立问题（每个问题相对独立）

---

### 策略2：智能截断（Smart Truncation）

**根据 Token 数量动态截断**

```java
while (estimateTokens(history) > maxContextTokens) {
    history.remove(0);  // 移除最早的消息
}
```

**优点**：
- ✅ 精确控制 Token 使用
- ✅ 最大化利用上下文窗口

**缺点**：
- ❌ 需要准确的 Token 估算
- ❌ 实现复杂度较高

---

### 策略3：分层保留（Hierarchical Retention）

**保留关键消息 + 最近消息**

```
完整历史：
  [系统消息] ← 始终保留
  [M1: 用户问题] ← 保留（首次问题）
  [M2: AI 回答]
  [M3, M4, M5] ← 截断
  [M6, M7, M8] ← 保留（最近 3 轮）
```

**优点**：
- ✅ 保留关键上下文
- ✅ 平衡历史和性能

**缺点**：
- ❌ 需要识别"关键消息"
- ❌ 实现复杂

---

### 策略4：摘要压缩（Summarization）

**定期压缩历史为摘要**

```
原始历史：
  User: 帮我查看 pom.xml
  AI: 这是一个 Maven 项目...（500 字）
  User: 分析依赖关系
  AI: 项目依赖了 LangChain4j...（800 字）

压缩后：
  摘要: 用户在分析一个使用 LangChain4j 的 Maven 项目
  User: [最新问题]
  AI: [最新回答]
```

**优点**：
- ✅ 大幅减少 Token 消耗
- ✅ 保留核心信息

**缺点**：
- ❌ 需要调用 AI 进行摘要（额外成本）
- ❌ 可能丢失细节

---

## 推荐实现方案

### 方案A：简单滑动窗口（立即可用）

**配置项**：
```yaml
# config.yaml
context:
  maxHistoryTurns: 10  # 保留最近 10 轮对话（20 条消息）
  strategy: "sliding_window"
```

**代码实现**：
```java
// ContextManager.java
public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
    int maxTurns = appConfig.getContext().getMaxHistoryTurns();
    int maxMessages = maxTurns * 2;  // 每轮包含用户+AI消息
    
    if (fullHistory.size() <= maxMessages) {
        return fullHistory;
    }
    
    // 保留最近 N 条消息
    return fullHistory.subList(fullHistory.size() - maxMessages, fullHistory.size());
}
```

---

### 方案B：智能 Token 控制（推荐）

**配置项**：
```yaml
context:
  maxContextTokens: 3000  # 为历史预留 3000 tokens
  reserveTokens: 1000     # 为响应预留 1000 tokens
  strategy: "token_based"
```

**代码实现**：
```java
public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
    int maxTokens = appConfig.getContext().getMaxContextTokens();
    List<ChatMessage> result = new ArrayList<>();
    int totalTokens = 0;
    
    // 从最新消息开始倒序添加
    for (int i = fullHistory.size() - 1; i >= 0; i--) {
        ChatMessage msg = fullHistory.get(i);
        int msgTokens = estimateTokens(msg.getContent());
        
        if (totalTokens + msgTokens > maxTokens) {
            break;  // 超过限制，停止添加
        }
        
        result.add(0, msg);  // 添加到开头
        totalTokens += msgTokens;
    }
    
    return result;
}

// Token 估算（简单方法：4 字符 ≈ 1 token）
private int estimateTokens(String text) {
    return text.length() / 4;
}
```

---

### 方案C：混合策略（最优）

**结合滑动窗口 + Token 控制**

```java
public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
    // 1. 先应用滑动窗口
    List<ChatMessage> windowedHistory = applyWindowStrategy(fullHistory);
    
    // 2. 再应用 Token 控制
    List<ChatMessage> tokenLimited = applyTokenLimit(windowedHistory);
    
    return tokenLimited;
}
```

---

## 实现步骤

### Step 1: 创建 ContextManager

```java
package com.thoughtcoding.service;

public class ContextManager {
    private final AppConfig appConfig;
    
    public ContextManager(AppConfig appConfig) {
        this.appConfig = appConfig;
    }
    
    /**
     * 获取适合发送给 AI 的上下文
     * 应用历史长度限制策略
     */
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
        // 实现策略
    }
}
```

### Step 2: 更新配置文件

```yaml
# config.yaml
context:
  enabled: true
  strategy: "token_based"  # sliding_window | token_based | hybrid
  maxHistoryTurns: 10      # 滑动窗口：保留轮数
  maxContextTokens: 3000   # Token 控制：最大 tokens
  reserveTokens: 1000      # 为响应预留的 tokens
  estimateMethod: "simple" # simple | accurate
```

### Step 3: 集成到 LangChainService

```java
// LangChainService.java
private List<dev.langchain4j.data.message.ChatMessage> prepareMessages(
        String input, List<ChatMessage> history) {
    
    // 应用上下文管理策略
    List<ChatMessage> managedHistory = contextManager.getContextForAI(history);
    
    // 转换为 LangChain4j 格式
    List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
    messages.addAll(convertToLangChainHistory(managedHistory));
    messages.add(dev.langchain4j.data.message.UserMessage.from(input));
    
    return messages;
}
```

---

## 测试场景

### 场景1：短对话（< 10 轮）
- 预期：全部历史发送
- 验证：检查发送的消息数量

### 场景2：长对话（> 20 轮）
- 预期：只发送最近 10 轮
- 验证：检查是否截断

### 场景3：超长单条消息
- 预期：即使单条消息超限也能处理
- 验证：不会崩溃，能正常截断

### 场景4：Token 限制
- 预期：总 Token 不超过 maxContextTokens
- 验证：计算实际 Token 数

---

## 监控指标

```java
// 添加监控日志
System.out.println("📊 上下文统计:");
System.out.println("  完整历史: " + fullHistory.size() + " 条消息");
System.out.println("  发送历史: " + managedHistory.size() + " 条消息");
System.out.println("  估算 Tokens: " + estimatedTokens);
System.out.println("  截断策略: " + strategy);
```

---

## 总结

| 策略 | 实现难度 | 效果 | 推荐指数 |
|------|---------|------|---------|
| 滑动窗口 | ⭐ 简单 | ⭐⭐⭐ 好 | ⭐⭐⭐⭐ 推荐 |
| Token 控制 | ⭐⭐ 中等 | ⭐⭐⭐⭐ 很好 | ⭐⭐⭐⭐⭐ 强烈推荐 |
| 分层保留 | ⭐⭐⭐ 复杂 | ⭐⭐⭐⭐ 很好 | ⭐⭐⭐ 可选 |
| 摘要压缩 | ⭐⭐⭐⭐ 很复杂 | ⭐⭐⭐⭐⭐ 极好 | ⭐⭐ 高级功能 |

**建议**：
1. **短期**：实现滑动窗口（1小时工作量）
2. **中期**：添加 Token 控制（半天工作量）
3. **长期**：考虑摘要压缩（需要额外 AI 调用）

