# MCP 数据库连接配置指南

## 问题原因

PostgreSQL MCP 服务器的参数格式错误。之前的配置使用了 `--connectionString` 参数，但实际上这些 MCP 服务器直接接收数据库 URL 作为参数。

## 正确的配置格式

### PostgreSQL

```yaml
- name: "postgres"
  command: "npx"
  enabled: false
  args:
    - "@modelcontextprotocol/server-postgres"
    - "postgresql://username:password@localhost:5432/database_name"
```

**示例：**
```yaml
# 本地默认 PostgreSQL（无密码）
- "postgresql://localhost/postgres"

# 带用户名和密码
- "postgresql://myuser:mypass@localhost:5432/mydb"

# 远程数据库
- "postgresql://user:pass@192.168.1.100:5432/production"
```

### MySQL

```yaml
- name: "mysql"
  command: "npx"
  enabled: false
  args:
    - "@modelcontextprotocol/server-mysql"
    - "mysql://username:password@localhost:3306/database_name"
```

**示例：**
```yaml
# 本地默认 MySQL
- "mysql://root@localhost:3306/mysql"

# 带密码
- "mysql://root:password@localhost:3306/mydb"
```

### SQLite

```yaml
- name: "sqlite"
  command: "npx"
  enabled: false
  args:
    - "@modelcontextprotocol/server-sqlite"
    - "./path/to/database.db"
```

**示例：**
```yaml
# 相对路径
- "./data.db"

# 绝对路径
- "/Users/username/databases/myapp.db"
```

## 使用步骤

### 1. 配置数据库连接

编辑 `src/main/resources/config.yaml`，修改对应数据库的连接字符串：

```yaml
mcp:
  servers:
    - name: "postgres"
      command: "npx"
      enabled: true  # 改为 true 启用
      args:
        - "@modelcontextprotocol/server-postgres"
        - "postgresql://localhost/postgres"  # 修改为你的数据库连接
```

### 2. 启用数据库服务器

将 `enabled: false` 改为 `enabled: true`

### 3. 重新编译和运行

```bash
cd /Users/zengxinyue/Desktop/仓库/ThoughtCoding
mvn clean package
./bin/thought
```

### 4. 连接 MCP 服务器

在交互模式中：

```bash
# 如果配置文件中已启用，程序启动时会自动连接
# 或者手动连接：
/mcp connect postgres
```

### 5. 查看可用工具

```bash
/mcp list
```

## 常见问题

### Q: 如何测试 PostgreSQL 连接是否正确？

在终端中运行：
```bash
psql postgresql://localhost/postgres
```

如果能连接成功，说明连接字符串正确。

### Q: 如何创建本地 PostgreSQL 数据库？

```bash
# macOS (使用 Homebrew)
brew install postgresql@14
brew services start postgresql@14
createdb mydb

# 连接字符串
postgresql://localhost/mydb
```

### Q: 如何创建本地 MySQL 数据库？

```bash
# macOS (使用 Homebrew)
brew install mysql
brew services start mysql
mysql -u root

# 在 MySQL 中
CREATE DATABASE mydb;

# 连接字符串
mysql://root@localhost:3306/mydb
```

### Q: SQLite 数据库文件不存在怎么办？

SQLite 会自动创建文件，只需确保路径正确：

```yaml
args:
  - "@modelcontextprotocol/server-sqlite"
  - "./mydata.db"  # 相对于项目根目录
```

## 安全建议

⚠️ **不要在配置文件中直接写入数据库密码！**

### 方案1：使用环境变量

```yaml
args:
  - "@modelcontextprotocol/server-postgres"
  - "${DATABASE_URL}"  # 从环境变量读取
```

然后在 shell 中设置：
```bash
export DATABASE_URL="postgresql://user:pass@localhost/db"
./bin/thought
```

### 方案2：使用本地配置文件

创建 `config.local.yaml`（添加到 .gitignore）：
```yaml
mcp:
  servers:
    - name: "postgres"
      args:
        - "@modelcontextprotocol/server-postgres"
        - "postgresql://myuser:secretpass@localhost/mydb"
```

### 方案3：无密码本地连接

配置 PostgreSQL 允许本地无密码连接：

编辑 `pg_hba.conf`：
```
# TYPE  DATABASE        USER            ADDRESS                 METHOD
local   all             all                                     trust
```

然后使用：
```yaml
- "postgresql://localhost/postgres"  # 无需用户名和密码
```

## 测试示例

连接成功后，可以尝试：

```
帮我查询数据库中的所有表

列出 users 表的结构

执行 SQL：SELECT * FROM users LIMIT 10
```

AI 会通过 MCP 工具执行数据库查询并返回结果。

