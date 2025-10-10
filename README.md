# ThoughtCoding CLI

![1759907149614](1759907149614.jpg)

一个基于 LangChain 的交互式代码助手 CLI 工具，支持流式输出、工具调用和智能对话。

## 🎥 项目演示

[![下载演示视频](https://img.shields.io/badge/📥_下载演示视频_(379MB)-blue?style=for-the-badge)](项目演示.mp4)

*点击按钮下载演示视频到本地观看*

## 🚀 项目特性

- **智能对话** - 基于多种 AI 模型的智能代码助手
- **流式输出** - 支持实时流式响应，提供更好的交互体验
- **MCP 集成** - 内置 Model Context Protocol 支持，可连接丰富的工具生态系统
- **工具扩展** - 通过 MCP 支持文件管理、数据库操作、搜索、GitHub 等 50+ 种工具
- **动态工具发现** - 自动发现和注册 MCP 服务器的可用工具
- **即插即用** - 无需重启即可动态连接新的 MCP 服务器
- **配置管理** - 灵活的 YAML 配置文件系统，支持 MCP 服务器动态配置
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
│   ├── 📁 mcp/                          # 🔌 MCP 功能模块
│   │   ├── MCPService.java              # MCP 服务管理器
│   │   ├── MCPClient.java               # MCP 客户端
│   │   ├── MCPToolAdapter.java          # MCP 工具适配器
│   │   ├── MCPToolManager.java          # MCP 工具管理器
│   │   └── 📁 model/                    # MCP 数据模型
│   │       ├── MCPRequest.java          # MCP 请求
│   │       ├── MCPResponse.java         # MCP 响应
│   │       ├── MCPError.java            # MCP 错误
│   │       ├── MCPTool.java             # MCP 工具定义
│   │       └── InputSchema.java         # 输入模式定义
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

### `src/main/java/com/thoughtcoding/mcp/` - MCP 功能

**功能**: 实现 Model Context Protocol 客户端功能，连接和管理外部 MCP 服务器

`MCPService.java` - MCP 服务管理器

- **功能**: MCP 服务的核心管理器

`MCPClient.java` - MCP 客户端

- **功能**: 单个 MCP 服务器的客户端实现

`MCPToolManager.java` - MCP 工具管理器

- **功能**: 管理所有 MCP 工具的统一入口

`MCPToolAdapter.java` - MCP 工具适配器

- **功能**: 将 MCP 工具适配为内部 BaseTool 格式

**功能**: 定义 MCP 协议的数据结构和类型

**`model/`**

`MCPRequest.java` : MCP 请求

`MCPResponse.java` : MCP 响应

`MCPError.java` : MCP 错误

`MCPTool.java` : MCP 工具定义

`InputSchema.java` : 输入模式定义

### `src/main/java/com/thoughtcoding/ui/` - 用户界面

**功能**: 终端用户界面管理

**主要组件**:

`ThoughtCodingUI.java`

- **功能**：UI 主类

`TerminalManager.java`

- **功能**：终端管理器

`AnsiColors.java`

- **功能**：ANSI 颜色工具类

**`component/`**

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
  
# MCP 配置
mcp:
  enabled: true
  autoDiscover: true
  connectionTimeout: 30
  servers:
    #Filesystem
    - name: "filesystem"
      command: "D:\\Program Files\\node.js\\npx.cmd"
      enabled: true
      args:
        - "@modelcontextprotocol/server-filesystem"
        - "."

    # PostgreSQL
    - name: "postgres"
      command: "npx"
      enabled: false
      args:
        - "@modelcontextprotocol/server-postgres"
        - "--connectionString"
        - "postgresql://user:pass@localhost:5432/db"

    # SQLite
    - name: "sqlite"
      command: "npx"
      enabled: false
      args:
        - "@modelcontextprotocol/server-sqlite"
        - "--database"
        - "./data.db"

    # MySQL
    - name: "mysql"
      command: "npx"
      enabled: false
      args:
        - "@modelcontextprotocol/server-mysql"
        - "--connectionString"
        - "mysql://user:pass@localhost:3306/db"

    # GitHub
    - name: "github"
      command: "npx"
      enabled: false
      args:
        - "@modelcontextprotocol/server-github"
        - "--token"
        - "your_github_token_here"

    #Weather
    - name: "weather"
      command: "npx"
      enabled: false
      args:
        - "@coding-squirrel/mcp-weather-server"
        - "--apiKey"
        - "your_weather_api_key"
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

- `mcp` - MCP 功能配置

  - `enabled` : 是否启用 MCP 功能模块
  - `autoDiscover` : 是否自动发现和注册 MCP 服务器的工具
  - `connectionTimeout` : MCP 服务器连接和初始化的超时时间

  - `servers` : MCP 服务器列表配置

    - `name` - 服务器名称
    - `command` - 启动 MCP 服务器的命令或可执行文件路径

    - `enabled` - 是否启用该服务器连接

    - `args` - 传递给 MCP 服务器的命令行参数

## 🛠️ 快速开始

### 安装要求

- Java 17 或更高版本
- Maven 3.6+
- 至少 2GB 可用内存
- Node.js 环境 16.0+
- 配置 npm 镜像（如淘宝镜像）
- 下载 MCP 服务器包

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

### MCP 使用指南

支持两种方式使用 MCP 工具

#### 📁 配置文件方式 : 持久化配置，适合常用工具

编辑 `config.yaml` 文件中的 `mcp` 部分，重启应用，验证工具加载，`/mcp list` 

#### ⌨️ 终端命令方式 : 动态连接，适合临时工具

启动应用后，命令连接需要的工具，

```
/mcp connect github npx @modelcontextprotocol/server-github
/mcp connect postgres npx @modelcontextprotocol/server-postgres
```

```
/mcp predefined          # 显示可用的预定义工具
/mcp tools redis,docker  # 快捷连接预定义工具
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

### **MCP 相关技术说明**

#### **MCP 功能特性：**

- ✅ **多服务器支持** - 可同时连接多个 MCP 服务器
- ✅ **预定义工具** - 内置常用 MCP 工具快捷方式
- ✅ **动态连接** - 运行时按需连接/断开 MCP 服务器
- ✅ **协议兼容** - 支持标准 MCP 协议规范

#### **支持的 MCP 工具：**

- 🔧 **文件系统工具** - 本地文件操作
- 🗃️ **数据库工具** - SQLite、PostgreSQL 查询
- 🌐 **网络工具** - Web 搜索、API 调用
- 📊 **计算工具** - 数学计算、数据处理

#### **集成方式：**

- ⚙️ **配置文件预连接** - 启动时自动连接常用工具
- ⌨️ **命令行动态连接** - 交互模式下按需连接工具
- 🔄 **混合模式** - 配置+命令灵活组合使用

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

### 基础 MCP 工具使用

```
./bin/thought -p "读取config.yaml文件内容并分析配置结构"

./bin/thought -p "连接SQLite数据库并查询所有用户表"

./bin/thought -p "通过GitHub工具获取我的开源项目列表"
```

### MCP 服务器管理命令

```
# 查看已连接的 MCP 工具
/mcp list

# 显示可用的预定义工具
/mcp predefined

# 断开 MCP 服务器连接
/mcp disconnect filesystem

# 连接基础开发工具包
/mcp tools filesystem,sqlite,git

# 连接文件系统服务器
/mcp connect filesystem npx @modelcontextprotocol/server-filesystem
```

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
- **MCP 支持**: Model Context Protocol 客户端
- **UI 框架**: JLine + 自定义 ANSI 终端 UI
- **配置管理**: YAML + Jackson
- **命令行**: Picocli
- **工具调用**: LangChain Tools 集成
- **JSON 处理**: Jackson Databind
- **协议通信**: STDIO + 进程间通信

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

