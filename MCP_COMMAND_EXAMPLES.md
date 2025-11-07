# MCP 命令使用示例

## 📋 命令概览

| 命令 | 格式 | 说明 |
|------|------|------|
| **连接服务器** | `/mcp connect <name> <command>` | 动态连接一个 MCP 服务器 |
| **使用预定义工具** | `/mcp tools <tool1,tool2>` | 启用预定义的 MCP 工具集 |
| **断开服务器** | `/mcp disconnect <name>` | 断开指定的 MCP 服务器连接 |
| **查看已连接** | `/mcp list` | 列出所有已连接的 MCP 工具 |
| **查看预定义工具** | `/mcp predefined` | 显示所有可用的预定义工具 |

---

## 🚀 命令示例

### 1️⃣ `/mcp connect <name> <command>` - 连接 MCP 服务器

**功能**: 动态连接一个新的 MCP 服务器

#### 示例 1: 连接文件系统服务器
```bash
thought> /mcp connect filesystem npx
```
**说明**: 
- `filesystem` - 服务器名称
- `npx` - 启动命令（会自动添加参数 `-y @modelcontextprotocol/server-filesystem`）

#### 示例 2: 连接 SQLite 数据库服务器
```bash
thought> /mcp connect sqlite npx
```

#### 示例 3: 连接 PostgreSQL 服务器
```bash
thought> /mcp connect postgres npx
```

#### 示例 4: 连接 GitHub 服务器
```bash
thought> /mcp connect github npx
```

**成功响应**:
```
✅ MCP server connected: filesystem
```

**失败响应**:
```
❌ Failed to connect MCP server
```

---

### 2️⃣ `/mcp tools <tool1,tool2>` - 使用预定义工具

**功能**: 快速启用一组预定义的 MCP 工具集合

#### 可用的预定义工具:
- `github-search` - GitHub 仓库搜索
- `sql-query` - PostgreSQL 数据库查询
- `file-system` - 本地文件系统操作
- `web-search` - 网页搜索（使用 Brave）
- `calculator` - 数学计算
- `weather` - 天气信息查询
- `memory` - 内存操作

#### 示例 1: 启用单个工具
```bash
thought> /mcp tools file-system
```

#### 示例 2: 启用多个工具（用逗号分隔）
```bash
thought> /mcp tools file-system,github-search
```

#### 示例 3: 启用开发者工具组合
```bash
thought> /mcp tools file-system,github-search,sql-query
```

#### 示例 4: 启用所有工具
```bash
thought> /mcp tools file-system,github-search,sql-query,web-search,calculator,weather,memory
```

**成功响应**:
```
✅ MCP tools connected: file-system,github-search
```

**失败响应**:
```
❌ Failed to connect MCP tools
```

---

### 3️⃣ `/mcp disconnect <name>` - 断开 MCP 服务器

**功能**: 断开指定的 MCP 服务器连接

#### 示例 1: 断开文件系统服务器
```bash
thought> /mcp disconnect filesystem
```

#### 示例 2: 断开 SQLite 服务器
```bash
thought> /mcp disconnect sqlite
```

#### 示例 3: 断开 GitHub 服务器
```bash
thought> /mcp disconnect github
```

**成功响应**:
```
✅ MCP server disconnected: filesystem
```

---

### 4️⃣ `/mcp list` - 查看已连接的服务器

**功能**: 列出所有当前已连接的 MCP 工具

#### 示例:
```bash
thought> /mcp list
```

**响应示例**:
```
📋 Connected MCP Tools:
──────────────────────
• filesystem (10 tools)
• github (15 tools)
• sqlite (8 tools)
```

---

### 5️⃣ `/mcp predefined` - 查看预定义工具

**功能**: 显示所有可用的预定义工具列表

#### 示例:
```bash
thought> /mcp predefined
```

**响应**:
```
🔧 Predefined MCP Tools:
──────────────────────
• github-search    - GitHub repository search
• sql-query        - PostgreSQL database queries
• file-system      - Local file system operations
• web-search       - Web search using Brave
• calculator       - Mathematical calculations
• weather          - Weather information
• memory           - Memory operations

Usage: --mcp-tools tool1,tool2,tool3
```

---

## 🎯 实战场景示例

### 场景 1: 项目开发环境设置

```bash
# 1. 连接文件系统服务器
thought> /mcp connect filesystem npx

# 2. 连接 GitHub 服务器
thought> /mcp connect github npx

# 3. 查看已连接的服务器
thought> /mcp list

# 4. 现在可以使用文件操作和 GitHub 功能了
thought> 帮我读取项目根目录下的 README.md 文件
thought> 搜索 GitHub 上关于 MCP 的仓库
```

### 场景 2: 数据分析任务

```bash
# 1. 启用数据分析相关工具
thought> /mcp tools file-system,sql-query,calculator

# 2. 开始分析
thought> 读取数据文件 /path/to/data.csv
thought> 连接数据库并查询用户统计数据
thought> 计算平均值和标准差
```

### 场景 3: 快速切换工具

```bash
# 1. 启用基础工具
thought> /mcp tools file-system

# 2. 工作一段时间后，需要更多功能
thought> /mcp tools file-system,github-search,web-search

# 3. 任务完成，断开不需要的服务器
thought> /mcp disconnect github
```

### 场景 4: 测试新的 MCP 服务器

```bash
# 1. 连接测试服务器
thought> /mcp connect mytest npx

# 2. 测试功能...

# 3. 测试完成，断开连接
thought> /mcp disconnect mytest
```

---

## 📝 注意事项

### ✅ 使用建议

1. **优先使用预定义工具**: 预定义工具经过测试，更稳定可靠
   ```bash
   thought> /mcp tools file-system  # 推荐
   ```

2. **按需连接**: 只启用当前任务需要的工具，避免资源浪费
   ```bash
   # ✅ 好的做法
   thought> /mcp tools file-system,github-search
   
   # ❌ 不推荐
   thought> /mcp tools file-system,github-search,sql-query,web-search,calculator,weather,memory
   ```

3. **及时断开**: 任务完成后断开不需要的服务器
   ```bash
   thought> /mcp disconnect sqlite
   ```

4. **查看状态**: 定期检查已连接的服务器
   ```bash
   thought> /mcp list
   ```

### ⚠️ 常见错误

#### 错误 1: 缺少参数
```bash
thought> /mcp connect filesystem
❌ Usage: /mcp connect <server-name> <command>
```
**解决**: 必须同时提供服务器名称和命令
```bash
thought> /mcp connect filesystem npx  ✅
```

#### 错误 2: 工具名称错误
```bash
thought> /mcp tools file_system
❌ Failed to connect MCP tools
```
**解决**: 使用正确的工具名称（使用连字符 `-` 而非下划线 `_`）
```bash
thought> /mcp tools file-system  ✅
```

#### 错误 3: 重复连接
```bash
thought> /mcp connect filesystem npx
thought> /mcp connect filesystem npx
```
**解决**: 先断开再重新连接
```bash
thought> /mcp disconnect filesystem
thought> /mcp connect filesystem npx  ✅
```

---

## 🔧 高级用法

### 组合使用
```bash
# 启动时使用预定义工具
thought> /mcp tools file-system,github-search

# 运行时动态添加新服务器
thought> /mcp connect weather npx

# 查看所有连接
thought> /mcp list

# 移除不需要的
thought> /mcp disconnect weather
```

### 自定义工具链
```bash
# 开发环境
thought> /mcp tools file-system,github-search,sql-query

# 数据分析环境
thought> /mcp tools sql-query,calculator,file-system

# 研究环境
thought> /mcp tools web-search,github-search,memory
```

---

## 📚 相关文档

- [MCP 配置指南](./MCP_CONFIG_GUIDE.md) - 详细的配置说明
- [MCP 架构分析](./MCP_ARCHITECTURE_ANALYSIS.md) - 技术架构
- [完整功能列表](./FEATURES.md) - 所有功能介绍

---

**最后更新**: 2025-11-06
**版本**: 1.0.0

