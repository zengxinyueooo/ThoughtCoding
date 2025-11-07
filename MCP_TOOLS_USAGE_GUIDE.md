# MCP 预定义工具使用指南

## 📋 问题说明

您遇到的错误 `Failed to connect MCP tools` 是因为：

1. ❌ **工具名称错误**：您使用了 `github`，但正确的名称是 `github-search`
2. ⚠️ **缺少配置**：GitHub 工具需要提供 GitHub Personal Access Token

## ✅ 正确的使用方式

### 🎯 推荐工具（无需额外配置）

这些工具可以直接使用，无需提供 API Keys：

```bash
# 文件系统操作（强烈推荐）
thought> /mcp tools file-system

# 数学计算器
thought> /mcp tools calculator

# 内存操作
thought> /mcp tools memory

# 组合使用（推荐）
thought> /mcp tools file-system,calculator,memory
```

### ⚠️ 需要配置的工具

这些工具需要先配置 API Keys 或数据库连接：

```bash
# GitHub 搜索（需要 Token）
thought> /mcp tools github-search
# 或简写
thought> /mcp tools github

# PostgreSQL 查询（需要数据库）
thought> /mcp tools sql-query

# SQLite 查询（需要数据库文件）
thought> /mcp tools sqlite

# 网页搜索（需要 Brave API Key）
thought> /mcp tools web-search

# 天气查询（需要天气 API Key）
thought> /mcp tools weather
```

## 📝 支持的工具名称映射

| 工具功能 | 主要名称 | 别名 | 状态 |
|---------|---------|------|------|
| 文件系统 | `file-system` | `filesystem` | ✅ 可直接使用 |
| GitHub | `github-search` | `github` | ⚠️ 需要配置 Token |
| PostgreSQL | `sql-query` | `postgres` | ⚠️ 需要配置连接 |
| SQLite | `sqlite` | - | ⚠️ 需要数据库文件 |
| 网页搜索 | `web-search` | `brave-search` | ⚠️ 需要 API Key |
| 计算器 | `calculator` | - | ✅ 可直接使用 |
| 天气 | `weather` | - | ⚠️ 需要 API Key |
| 内存 | `memory` | - | ✅ 可直接使用 |

## 🚀 快速开始

### 场景 1: 本地开发（推荐）

```bash
# 启动 ThoughtCoding
java -jar target/thoughtcoding.jar

# 连接文件系统工具
thought> /mcp tools file-system

# 现在可以使用文件操作了
thought> 帮我读取当前目录下的 README.md 文件
thought> 列出项目中所有的 Java 文件
```

### 场景 2: 数据分析

```bash
# 连接文件系统和计算器
thought> /mcp tools file-system,calculator

# 使用示例
thought> 读取 data.csv 文件并计算平均值
```

### 场景 3: 完整开发环境（需要 GitHub Token）

```bash
# 连接多个工具
thought> /mcp tools file-system,github-search,calculator

# 使用示例
thought> 搜索 GitHub 上关于 MCP 的仓库
```

## 🔧 如何配置需要 API Key 的工具

### 方法 1: 使用 `/mcp connect` 命令（推荐）

```bash
# 连接文件系统（无需配置）
thought> /mcp connect filesystem

# 连接 GitHub（自动配置，但需要修改源码中的 Token）
thought> /mcp connect github
```

### 方法 2: 修改配置文件

编辑 `src/main/resources/config.yaml`：

```yaml
mcp:
  servers:
    - name: "github"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-github"
        - "--token"
        - "ghp_your_actual_github_token_here"  # 替换为您的 Token
```

然后重新编译：
```bash
mvn clean package -DskipTests
```

### 获取 API Keys

- **GitHub Token**: https://github.com/settings/tokens
  - 创建 Personal Access Token
  - 选择 `repo` 权限
  
- **OpenWeather API**: https://openweathermap.org/api
  - 注册并创建免费 API Key
  
- **Brave Search API**: https://brave.com/search/api/
  - 申请 API Key

## 🎯 实战示例

### 示例 1: 文件操作

```bash
thought> /mcp tools file-system

thought> 列出当前目录的所有文件
thought> 读取 pom.xml 的内容
thought> 在 src 目录下搜索包含 "MCP" 的文件
```

### 示例 2: 计算器

```bash
thought> /mcp tools calculator

thought> 计算 (123 + 456) * 789
thought> 求平方根 144
```

### 示例 3: 组合使用

```bash
thought> /mcp tools file-system,calculator

thought> 读取 data.txt 中的数字并计算总和
thought> 分析项目中 Java 文件的数量
```

## ❓ 常见问题

### Q1: 为什么 `github` 连接失败？

**A**: 应该使用 `github-search`，并且需要配置 GitHub Token。

```bash
# ❌ 错误
thought> /mcp tools github

# ✅ 正确（但需要先配置 Token）
thought> /mcp tools github-search
```

### Q2: 如何查看可用的预定义工具？

```bash
thought> /mcp predefined
```

### Q3: 如何检查已连接的工具？

```bash
thought> /mcp list
```

### Q4: 连接失败怎么办？

1. 检查工具名称是否正确（使用 `/mcp predefined` 查看）
2. 如果需要 API Key，确认是否已配置
3. 查看控制台日志了解详细错误信息
4. 优先使用无需配置的工具：`file-system`, `calculator`, `memory`

## 📊 工具对比

| 特性 | `/mcp tools` | `/mcp connect` |
|-----|-------------|----------------|
| 使用场景 | 快速启用预定义工具 | 连接自定义 MCP 服务器 |
| 配置复杂度 | 低（内置配置） | 中（需要手动指定参数） |
| 灵活性 | 低 | 高 |
| 推荐用途 | 日常开发 | 高级定制 |

## 🎉 推荐工作流

```bash
# 1. 启动 ThoughtCoding
java -jar target/thoughtcoding.jar

# 2. 连接常用工具（无需配置）
thought> /mcp tools file-system,calculator

# 3. 开始工作
thought> 帮我分析项目结构
thought> 读取配置文件并解释其作用

# 4. 需要时添加更多工具
thought> /mcp tools memory

# 5. 查看已连接的工具
thought> /mcp list
```

---

**更新日期**: 2025-11-06  
**版本**: 2.0.0

