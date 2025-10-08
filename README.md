# ThoughtCoding CLI

![1759907149614](1759907149614.jpg)

一个基于 LangChain 的交互式代码助手 CLI 工具，支持流式输出、工具调用和智能对话。

## 🚀 项目特性

- **智能对话** - 基于多种 AI 模型的智能代码助手
- **流式输出** - 支持实时流式响应，提供更好的交互体验
- **丰富工具** - 支持文件管理、代码执行、搜索等多种工具调用
- **配置管理** - 灵活的 YAML 配置文件系统
- **类型安全** - 完整的 Java 类型定义和封装
- **终端 UI** - 基于 JLine + ANSI 颜色的现代化终端界面
- **会话管理** - 会话保存、加载和会话继续功能
- **性能监控** - 内置性能监控和 Token 使用统计
- **智能搜索**：具备代码搜索和文件内容搜索能力
- **跨平台支持** - 支持 Windows、Linux、macOS 系统

## 🏗项目结构

```
ThoughtCoding/
├── 📁 src/main/java/com/thoughtcoding/
│   ├── 📁 cli/                          # 🎯 命令行接口
│   │   ├── ThoughtCodingCommand.java    # 主命令处理器
│   │   ├── SessionCommand.java          # 会话管理命令
│   │   └── ConfigCommand.java           # 配置管理命令
│   ├── 📁 core/                         # 🔧 核心功能
│   │   ├── ThoughtCodingContext.java    # 应用上下文
│   │   ├── AgentLoop.java               # Agent 循环引擎
│   │   ├── MessageHandler.java          # 消息处理器
│   │   └── StreamingOutput.java         # 流式输出处理
│   ├── 📁 service/                      # 🛠️ 服务层
│   │   ├── LangChainService.java        # AI 服务核心
│   │   ├── SessionService.java          # 会话数据管理
│   │   ├── AIService.java               # AI 服务接口
│   │   └── PerformanceMonitor.java      # 性能监控
│   ├── 📁 tools/                        # 🔨 工具集合
│   │   ├── BaseTool.java                # 工具基类
│   │   ├── ToolRegistry.java            # 工具注册中心
│   │   ├── 📁 exec/                     # 执行工具
│   │   │   ├── CommandExecutorTool.java # 命令执行
│   │   │   └── CodeExecutorTool.java    # 代码执行
│   │   ├── 📁 file/                     # 文件工具
│   │   │   └── FileManagerTool.java     # 文件管理
│   │   └── 📁 search/                   # 搜索工具
│   │       └── GrepSearchTool.java      # 文本搜索
│   ├── 📁 ui/                           # 🎨 用户界面
│   │   ├── ThoughtCodingUI.java         # UI 主控制器
│   │   ├── TerminalManager.java         # 终端管理
│   │   ├── AnsiColors.java              # ANSI 颜色工具
│   │   └── 📁 component/                # UI 组件
│   │       ├── ChatRenderer.java        # 聊天渲染器
│   │       ├── InputHandler.java        # 输入处理器
│   │       ├── ProgressIndicator.java   # 进度指示器
│   │       └── StatusBar.java           # 状态栏
│   ├── 📁 config/                       # ⚙️ 配置管理
│   │   ├── AppConfig.java               # 应用配置
│   │   ├── ConfigLoader.java            # 配置加载器
│   │   └── ConfigManager.java           # 配置管理器
│   └── 📁 model/                        # 📊 数据模型
│       ├── ChatMessage.java             # 聊天消息
│       ├── SessionData.java             # 会话数据
│       ├── ToolCall.java                # 工具调用
│       └── ToolResult.java              # 工具结果
├── 📁 bin/                              # 🚀 启动脚本
│   ├── thought                         # Linux/macOS 脚本
│   └── thought.bat                     # Windows 脚本
├── 📁 sessions/                         # 💾 会话存储
├── ⚙️ config.yaml                       # 配置文件
├── 📜 pom.xml                          # Maven 配置
└── 📖 README.md                        # 项目说明
```

## 📁 模块说明

### `src/main/java/com/thoughtcoding/ThoughtCodingCLI.java` - CLI 入口

**功能**: 命令行界面入口，处理应用启动

**特性**:

- 初始化应用上下文和配置
- 设置命令行参数解析
- 启动主命令执行流程

### `src/main/java/com/thoughtcoding/cli/` - 命令行处理

**功能**: 管理所有 CLI 命令和参数解析

`SessionCommand.java`

- **功能**：会话管理命令类

`ConfigCommand.java`

- **功能**：配置管理命令类

`ThoughtCodingCommand.java`

**特性**:

- 支持交互式模式 (`-i, --interactive`)
- 支持继续上次会话 (`-c, --continue`)
- 支持指定会话 (`-S, --session`)
- 支持单次提问 (`-p, --prompt`)
- 支持模型选择 (`-m, --model`)
- 会话管理功能 (列表、删除会话)

### `src/main/java/com/thoughtcoding/config/` - 配置管理

**功能**: 管理应用配置

`AppConfig.java`

- **功能**：应用配置类

`ConfigLoader.java`

- **功能**：配置加载器
- **特性**：自动读取 `config.yaml` 文件

`ConfigManager.java`

- **功能**：配置管理器

### `src/main/java/com/thoughtcoding/model/` - 数据模型

**功能**: 集中管理所有数据模型和类型定义

**主要类**:

- `ChatMessage.java` - 聊天消息模型
- `ModelConfig.java` - 模型配置
- `SessionData.java` - 会话数据
- `ToolCall.java` - 工具调用
- `ToolResult.java` - 工具结果

### `src/main/java/com/thoughtcoding/core/` - 核心功能

**功能**: 提供核心业务逻辑

`ThoughtCodingContext.java`

- **功能**：应用上下文

`AgentLoop.java`

- **功能**：Agent 循环实现类
- **特性**：基于 LangChain4j 实现智能对话

`MessageHandler.java`

- **功能**：消息处理器

`StreamingOutput.java`

- **功能**：流式输出处理类

### `src/main/java/com/thoughtcoding/service/` - 服务层

**功能**: 业务逻辑和服务实现

**主要服务**:

- `LangChainService.java` - AI 服务核心实现
- `SessionService.java` - 会话数据管理
- `AIService.java` - AI 服务接口
- `PerformanceMonitor.java` - 性能监控

### `src/main/java/com/thoughtcoding/tools/` - 工具集合

**功能**: 各种功能工具的实现

`ToolProvider.java`

- **功能**：工具提供接口

`ToolRegistry.java`

- **功能**：工具注册中心

**主要工具**:

- **文件管理工具**: 文件读写、目录操作 (`FileManagerTool.java`)
- **命令执行工具**: 执行系统命令 (`CommandExecutorTool.java`)
- **代码执行工具**: 执行代码片段 (`CodeExecutorTool.java`)
- **搜索工具**: 文件内容搜索 (`GrepSearchTool.java`)
- **扩展性**: 容易添加新工具，基于 `BaseTool` 基类

### `src/main/java/com/thoughtcoding/ui/` - 用户界面

**功能**: 终端用户界面管理

**主要组件**:

`ThoughtCodingUI.java`

- **功能**：UI 主类

`TerminalManager.java`

- **功能**：终端管理器

`AnsiColors.java`

- **功能**：ANSI 颜色工具类

`component/`

- **`ChatRenderer.java`**：聊天渲染器
- **`InputHandler.java`**：输入处理器
- **`ProgressIndicator.java`**：进度指示器
- **`ToolDisplay.java`**：工具显示类
- **`StatusBar.java`**：状态栏类

`themes/`

- **`ColorScheme.java`**：颜色方案类

## ⚙ 配置说明

### 配置文件 (`config.yaml`)

```
# ThoughtCoding AI服务配置
models:
  # DeepSeek 模型
  deepseek-v1:
    name: "deepseek-chat"
    baseURL: "https://api.deepseek.com/v1"
    apiKey: "your-api-key-here"
    streaming: true
    maxTokens: 4096
    temperature: 0.7

    # 阿里云通义千问
  qwen-plus:
    name: "qwen-plus"
    baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1"
    apiKey: "your-api-key-here"
    streaming: true
    maxTokens: 4096
    temperature: 0.7

# 默认模型
defaultModel: "deepseek-v1"

# 工具配置
tools:
  fileManager:
    enabled: true
    maxFileSize: 10485760
    allowedCommands: ["read", "write", "list", "create", "delete", "info"]
    timeoutSeconds: 30
    allowedLanguages: ["java", "python", "javascript"]

  commandExec:
    enabled: true
    maxFileSize: 10485760
    timeoutSeconds: 30

  codeExecutor:
    enabled: true
    maxFileSize: 10485760
    timeoutSeconds: 60
    allowedLanguages: ["java", "python", "javascript", "bash"]

  search:
    enabled: true
    maxFileSize: 10485760
    timeoutSeconds: 30

# Session Configuration
session:
  autoSave: true
  maxSessions: 100
  sessionTimeout: 86400000  # 24 hours in milliseconds

# UI Configuration
ui:
  theme: "default"
  showTimestamps: true
  colorfulOutput: true
  progressAnimation: true

# Performance Configuration
performance:
  enableMonitoring: true
  logLevel: "INFO"
  cacheSize: 1000
```

### 配置项说明

- models : 支持的AI模型配置
  - `name`: 模型名称
  - `baseURL`: API 基础 URL
  - `apiKey`: API 密钥
  - `streaming`: 是否启用流式输出
  - `maxTokens` - 单次请求最大 Token 数
  - `temperature` - 生成温度
- `defaultModel`: 默认使用的模型
- tools : 工具配置
  - `fileManager`: 文件管理工具配置
  - `commandExec`: 命令执行工具配置
  - `codeExecutor` - 代码执行工具配置
  - `search` - 搜索工具配置

- `session` : 会话管理配置
- `ui` : 界面显示配置
- `performance` : 性能监控配置

## 🛠️ 快速开始

### 安装要求

- Java 17 或更高版本
- Maven 3.6+
- 至少 2GB 可用内存

### **克隆仓库**

```bash
git clone https://github.com/zengxinyueooo/ThoughtCoding.git
```

### 配置 API

#### **Linux/macOS**

```
cp config.yaml.example config.yaml
# 编辑 config.yaml，填入您的 DeepSeek API 密钥
```

#### **Windows**

```
copy config.yaml.example config.yaml
# 编辑 config.yaml，填入您的 DeepSeek API 密钥
```

### 构建项目

```
mvn clean package
```

### 运行应用

```
cd ThoughtCoding
```

#### **Linux/macOS**

```
# 交互模式
./bin/thought

# 继续上次对话
./bin/thought -c

# 指定会话
./bin/thought -S <session-id>

# 单次对话
./bin/thought -p "帮我写一个Java类"

# 指定模型
./bin/thought -m deepseek-chat

# 查看帮助
./bin/thought help
```

#### **Windows**

```
# 交互模式
.\bin\thought.bat

# 继续上次对话
.\bin\thought.bat -c

# 指定会话
.\bin\thought.bat -S <session-id>

# 单次对话
.\bin\thought.bat -p "帮我写一个Java类"

# 指定模型
.\bin\thought.bat -m deepseek-chat

# 查看帮助
.\bin\thought.bat help
```

## 🔧 开发指南

### 在 `src/main/java/com/thoughtcoding/tools/` 目录下创建新工具

继承 `BaseTool` 基类并实现核心方法：

```
package com.thoughtcoding.tools;

public class MyTool extends BaseTool {
    
    public MyTool() {
        super("my_tool", "工具描述");
    }
    
    @Override
    public ToolResult execute(String input) {
        // 工具实现逻辑
        return new ToolResult("工具结果", true);
    }
}
```

在 `ToolRegistry.java` 中注册新工具：

```
public void registerTools() {
    registerTool(new MyTool());
    // 其他工具注册...
}
```

### 使用类型定义

```
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SessionData;

// 使用类型安全的模型
ChatMessage message = new ChatMessage("user", "Hello");
SessionData session = new SessionData("session-id", "标题", "model");
```

### 编码规范

- **类型安全**：充分利用Java的类型系统
- **异常处理**：使用明确的异常处理机制
- **日志记录**: 使用SLF4J进行日志记录
- **代码文档**：使用JavaDoc注释重要的方法和类
- **单元测试**：为核心功能编写单元测试

### 项目结构最佳实践

- 按功能分包，保持包结构清晰
- 使用接口定义边界服务
- 依赖注入管理组件依赖
- 配置与代码分离
- 使用构建工具管理依赖和构建过程

## 📊 脚本说明

- `./bin/thought` - Linux/macOS 启动脚本
- `./bin/thought.bat` - Windows 启动脚本
- `mvn clean package` - 构建项目并打包
- `mvn test` - 运行测试套件

## 💡 使用示例

### 基本对话

```
./bin/thought
```

启动交互式对话模式

### 代码生成

```
./bin/thought -p "帮我写一个Java类，实现用户管理功能"
```

### 文件操作

```
./bin/thought -p "创建一个Spring Boot配置文件"
```

### 继续对话

```
./bin/thought -c
```

### 会话管理

```
# 列出所有会话
./bin/thought --list-sessions

# 删除指定会话
./bin/thought --delete-session <session-id>

# 加载指定会话
./bin/thought -S <session-id>
```

## 🎥 项目演示

[![观看演示](https://img.shields.io/badge/🎬_观看视频-FF6B6B?style=for-the-badge)](https://github.com/zengxinyueooo/ThoughtCoding/项目演示.mp4)

## 🤝 协作指南

### 代码规范

- **类型安全**: 所有代码必须使用 Java 强类型
- **包结构**: 遵循约定的包组织结构
- **错误处理**: 完善的异常处理机制
- **文档注释**: 重要函数和类需要 Javadoc 注释

### 提交规范

- 使用清晰的提交信息
- 每个功能一个分支
- 提交前运行测试

### 开发流程

1. Fork 项目
2. 创建功能分支
3. 实现功能
4. 添加测试
5. 提交 Pull Request

## 🛠️ 技术栈

- **语言**: Java 17+
- **构建工具**: Maven
- **AI 框架**: LangChain4j
- **UI 框架**: JLine + 自定义 ANSI 终端 UI
- **配置管理**: YAML + Jackson
- **命令行**: Picocli
- **工具调用**: LangChain Tools 集成
- **JSON 处理**: Jackson Databind

------

## 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 贡献

欢迎贡献代码！请查看 [贡献指南](CONTRIBUTING.md) 了解详情。

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 Issue
- 发送邮件
- 参与讨论

------

**ThoughtCoding** **CLI** - 让 AI 编程助手更智能、更易用！ 🚀

