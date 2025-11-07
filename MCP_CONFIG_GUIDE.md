# MCP 服务器配置指南

## 📌 概述
ThoughtCoding 支持通过配置文件动态连接各种 MCP 服务器，**无需修改代码**。只需在 `config.yaml` 中启用相应的服务器即可。

## 🚀 快速启用指南

### 1. 文件系统 (Filesystem) - 推荐首选
```yaml
- name: "filesystem"
  command: "npx"
  enabled: true  # ✅ 改为 true 启用
  args:
    - "-y"
    - "@modelcontextprotocol/server-filesystem"
    - "/your/work/directory"  # 🔥 修改为你的工作目录
```
**用途**: 文件读写、目录操作、文件搜索等
**依赖**: Node.js + npm

---

### 2. SQLite 数据库
```yaml
- name: "sqlite"
  command: "npx"
  enabled: true  # ✅ 启用
  args:
    - "-y"
    - "@modelcontextprotocol/server-sqlite"
    - "--database"
    - "./my-database.db"  # 🔥 修改为你的数据库路径
```
**用途**: SQLite 数据库操作（查询、插入、更新等）
**依赖**: Node.js + npm

---

### 3. PostgreSQL 数据库
```yaml
- name: "postgres"
  command: "npx"
  enabled: true  # ✅ 启用
  args:
    - "-y"
    - "@modelcontextprotocol/server-postgres"
    - "--connectionString"
    - "postgresql://username:password@localhost:5432/dbname"  # 🔥 修改连接字符串
```
**用途**: PostgreSQL 数据库操作
**依赖**: Node.js + npm + 运行中的 PostgreSQL 服务器

---

### 4. MySQL 数据库
```yaml
- name: "mysql"
  command: "npx"
  enabled: true  # ✅ 启用
  args:
    - "-y"
    - "@modelcontextprotocol/server-mysql"
    - "--connectionString"
    - "mysql://username:password@localhost:3306/dbname"  # 🔥 修改连接字符串
```
**用途**: MySQL 数据库操作
**依赖**: Node.js + npm + 运行中的 MySQL 服务器

---

### 5. GitHub
```yaml
- name: "github"
  command: "npx"
  enabled: true  # ✅ 启用
  args:
    - "-y"
    - "@modelcontextprotocol/server-github"
    - "--token"
    - "ghp_your_github_personal_access_token"  # 🔥 替换为你的 GitHub Token
```
**用途**: GitHub 仓库操作（查看 issues、PR、代码等）
**依赖**: Node.js + npm + GitHub Personal Access Token
**获取 Token**: https://github.com/settings/tokens

---

### 6. 天气服务 (Weather)
```yaml
- name: "weather"
  command: "npx"
  enabled: true  # ✅ 启用
  args:
    - "-y"
    - "@coding-squirrel/mcp-weather-server"
    - "--apiKey"
    - "your_openweather_api_key"  # 🔥 替换为你的天气 API Key
```
**用途**: 查询天气信息
**依赖**: Node.js + npm + OpenWeather API Key
**获取 API Key**: https://openweathermap.org/api

---

## ✅ 配置验证

启用服务器后，运行以下命令验证：

```bash
# 查看所有已连接的 MCP 服务器
java -jar target/thoughtcoding.jar mcp list

# 查看某个服务器的可用工具
java -jar target/thoughtcoding.jar mcp tools filesystem
```

---

## 🎯 推荐配置组合

### 开发者模式
```yaml
filesystem: enabled: true
github: enabled: true
sqlite: enabled: true
```

### 数据分析模式
```yaml
filesystem: enabled: true
postgres: enabled: true
mysql: enabled: true
```

### 基础模式（最小依赖）
```yaml
filesystem: enabled: true
```

---

## 📝 注意事项

1. **依赖检查**: 所有 MCP 服务器都需要 Node.js 环境，首次运行会自动通过 `npx -y` 安装
2. **连接字符串**: 数据库类服务器需要正确的连接字符串和运行中的数据库服务
3. **API Keys**: GitHub 和 Weather 需要有效的 API Key
4. **工作目录**: Filesystem 需要指定实际存在的目录路径
5. **同时启用**: 可以同时启用多个 MCP 服务器，ThoughtCoding 会管理所有连接

---

## 🐛 故障排查

### 问题: MCP 服务器初始化失败
**解决方案**:
1. 检查 Node.js 是否安装: `node --version`
2. 检查 npx 是否可用: `npx --version`
3. 查看详细日志获取错误信息

### 问题: 数据库连接失败
**解决方案**:
1. 确认数据库服务正在运行
2. 验证连接字符串格式正确
3. 检查用户名密码是否正确
4. 确认网络连接和防火墙设置

### 问题: GitHub/Weather 工具不可用
**解决方案**:
1. 验证 API Key 是否有效
2. 检查 API Key 是否有相应权限
3. 确认网络可以访问对应的 API 服务

---

## 🔧 自定义 MCP 服务器

您也可以添加其他 MCP 服务器，只需按以下格式配置：

```yaml
- name: "custom-server"
  command: "npx"  # 或其他启动命令
  enabled: true
  args:
    - "-y"
    - "@your-package/mcp-server"
    - "--option1"
    - "value1"
```

---

## 📚 更多资源

- [MCP 官方文档](https://modelcontextprotocol.io/)
- [MCP 服务器列表](https://github.com/modelcontextprotocol/servers)
- ThoughtCoding GitHub: [仓库链接]

---

**最后更新**: 2025-11-06

