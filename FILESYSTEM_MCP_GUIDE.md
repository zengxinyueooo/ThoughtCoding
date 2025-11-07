# Filesystem MCP 服务器功能详解

## 📌 概述
`@modelcontextprotocol/server-filesystem` 是 MCP 官方提供的文件系统操作服务器，提供了强大的文件和目录操作能力。

## 🛠️ 主要功能工具

### 1. **read_file** - 读取文件内容
**功能**: 读取指定路径的文件内容

**参数**:
- `path` (必需): 文件的完整路径

**用途**:
- 读取文本文件内容
- 查看配置文件
- 读取代码文件
- 读取日志文件

**示例**:
```json
{
  "path": "/Users/zengxinyue/Desktop/仓库/ThoughtCoding/README.md"
}
```

---

### 2. **read_multiple_files** - 批量读取多个文件
**功能**: 一次性读取多个文件的内容

**参数**:
- `paths` (必需): 文件路径数组

**用途**:
- 批量读取配置文件
- 对比多个文件内容
- 一次性加载相关文件

**示例**:
```json
{
  "paths": [
    "/path/to/file1.txt",
    "/path/to/file2.txt",
    "/path/to/file3.txt"
  ]
}
```

---

### 3. **write_file** - 写入文件
**功能**: 创建新文件或覆盖现有文件内容

**参数**:
- `path` (必需): 文件路径
- `content` (必需): 要写入的内容

**用途**:
- 创建新文件
- 修改现有文件
- 生成配置文件
- 保存处理结果

**示例**:
```json
{
  "path": "/path/to/newfile.txt",
  "content": "Hello, World!"
}
```

---

### 4. **edit_file** - 编辑文件（搜索替换）
**功能**: 在文件中搜索并替换指定内容

**参数**:
- `path` (必需): 文件路径
- `edits` (必需): 编辑操作数组
  - `oldText`: 要查找的原文本
  - `newText`: 替换后的新文本

**用途**:
- 精确修改文件内容
- 批量替换
- 代码重构
- 配置更新

**示例**:
```json
{
  "path": "/path/to/file.txt",
  "edits": [
    {
      "oldText": "old content",
      "newText": "new content"
    }
  ]
}
```

---

### 5. **create_directory** - 创建目录
**功能**: 创建新目录（支持递归创建）

**参数**:
- `path` (必需): 目录路径

**用途**:
- 创建项目结构
- 组织文件
- 创建临时目录

**示例**:
```json
{
  "path": "/path/to/new/directory"
}
```

---

### 6. **list_directory** - 列出目录内容
**功能**: 列出指定目录中的所有文件和子目录

**参数**:
- `path` (必需): 目录路径

**返回信息**:
- 文件名
- 文件类型（文件/目录）
- 文件大小
- 修改时间

**用途**:
- 浏览目录结构
- 查找文件
- 分析项目结构

**示例**:
```json
{
  "path": "/Users/zengxinyue/Desktop/仓库/ThoughtCoding"
}
```

---

### 7. **directory_tree** - 获取目录树
**功能**: 递归获取目录的完整树状结构

**参数**:
- `path` (必需): 根目录路径

**用途**:
- 查看完整项目结构
- 生成目录文档
- 分析代码组织

**示例**:
```json
{
  "path": "/Users/zengxinyue/Desktop/仓库/ThoughtCoding/src"
}
```

---

### 8. **move_file** - 移动/重命名文件
**功能**: 移动文件到新位置或重命名文件

**参数**:
- `source` (必需): 源文件路径
- `destination` (必需): 目标路径

**用途**:
- 重命名文件
- 移动文件到其他目录
- 重组项目结构

**示例**:
```json
{
  "source": "/path/to/old.txt",
  "destination": "/path/to/new.txt"
}
```

---

### 9. **search_files** - 搜索文件内容
**功能**: 在指定目录中搜索包含特定文本的文件

**参数**:
- `path` (必需): 搜索路径
- `pattern` (必需): 搜索模式（支持正则表达式）
- `excludePatterns` (可选): 排除的文件模式

**用途**:
- 全文搜索
- 查找代码引用
- 定位配置项
- 代码审查

**示例**:
```json
{
  "path": "/Users/zengxinyue/Desktop/仓库/ThoughtCoding",
  "pattern": "MCP",
  "excludePatterns": ["node_modules", "target", ".git"]
}
```

---

### 10. **get_file_info** - 获取文件信息
**功能**: 获取文件的详细元数据

**参数**:
- `path` (必需): 文件路径

**返回信息**:
- 文件大小
- 创建时间
- 修改时间
- 访问权限
- 文件类型

**用途**:
- 检查文件是否存在
- 获取文件属性
- 验证文件状态

**示例**:
```json
{
  "path": "/path/to/file.txt"
}
```

---

## 🔒 安全特性

### 权限控制
- **只能访问配置的工作目录**: 防止访问系统敏感目录
- **路径验证**: 防止路径遍历攻击
- **读写分离**: 可以单独控制读写权限

### 配置示例
```yaml
- name: "filesystem"
  command: "npx"
  enabled: true
  args:
    - "-y"
    - "@modelcontextprotocol/server-filesystem"
    - "/Users/zengxinyue/Desktop/仓库/ThoughtCoding"  # 仅允许访问这个目录
```

---

## 💡 实用场景

### 1. **代码开发辅助**
```
用途: 读取和修改代码文件
工具: read_file, edit_file, search_files
示例: "帮我找到所有使用了 MCP 的 Java 文件"
```

### 2. **项目结构分析**
```
用途: 了解项目组织结构
工具: directory_tree, list_directory
示例: "显示 src/main/java 目录的完整结构"
```

### 3. **批量文件操作**
```
用途: 同时处理多个文件
工具: read_multiple_files, write_file
示例: "读取所有配置文件并生成汇总报告"
```

### 4. **代码重构**
```
用途: 批量修改代码
工具: edit_file, search_files
示例: "将所有文件中的 'oldClassName' 替换为 'newClassName'"
```

### 5. **文档生成**
```
用途: 自动生成项目文档
工具: directory_tree, read_file, write_file
示例: "生成项目的 README.md 文件"
```

---

## 🎯 与 ThoughtCoding 集成

在 ThoughtCoding 中，您可以通过 AI 对话来使用这些工具：

### 示例对话

**用户**: "读取 README.md 文件"
**AI**: 使用 `read_file` 工具读取文件并返回内容

**用户**: "在 src 目录中搜索所有包含 'MCP' 的文件"
**AI**: 使用 `search_files` 工具搜索并列出匹配的文件

**用户**: "创建一个新的配置文件 config/app.yaml"
**AI**: 使用 `write_file` 工具创建文件

**用户**: "显示项目的目录结构"
**AI**: 使用 `directory_tree` 工具展示完整结构

---

## 📊 性能特点

- ✅ **高效**: 直接访问本地文件系统，速度快
- ✅ **可靠**: 官方维护，稳定性高
- ✅ **安全**: 严格的权限控制
- ✅ **易用**: 简单的 JSON 参数

---

## 🔧 配置建议

### 开发环境
```yaml
# 允许访问整个项目目录
- "/Users/zengxinyue/Desktop/仓库/ThoughtCoding"
```

### 生产环境
```yaml
# 只允许访问数据目录
- "/app/data"
```

### 多目录访问
```yaml
# 注意: filesystem 服务器通常只支持单个根目录
# 如需访问多个目录，可以配置多个 filesystem 实例
- name: "filesystem-project"
  command: "npx"
  enabled: true
  args:
    - "-y"
    - "@modelcontextprotocol/server-filesystem"
    - "/Users/zengxinyue/Desktop/仓库/ThoughtCoding"

- name: "filesystem-data"
  command: "npx"
  enabled: true
  args:
    - "-y"
    - "@modelcontextprotocol/server-filesystem"
    - "/Users/zengxinyue/Desktop/data"
```

---

## 📚 相关资源

- [MCP 官方文档](https://modelcontextprotocol.io/)
- [Filesystem 服务器 GitHub](https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem)
- [MCP 协议规范](https://spec.modelcontextprotocol.io/)

---

**最后更新**: 2025-11-06
**服务器版本**: @modelcontextprotocol/server-filesystem (最新)

