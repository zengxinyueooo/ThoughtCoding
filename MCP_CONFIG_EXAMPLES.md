# ThoughtCoding MCP 配置示例

## 示例1: 本地开发环境（推荐新手）
```yaml
mcp:
  enabled: true
  autoDiscover: true
  connectionTimeout: 30
  servers:
    # 只启用文件系统
    - name: "filesystem"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-filesystem"
        - "/Users/zengxinyue/Desktop/仓库/ThoughtCoding"
```

## 示例2: 数据库开发环境
```yaml
mcp:
  enabled: true
  autoDiscover: true
  connectionTimeout: 30
  servers:
    # 文件系统
    - name: "filesystem"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-filesystem"
        - "/Users/zengxinyue/Desktop/仓库/ThoughtCoding"
    
    # SQLite（本地数据库）
    - name: "sqlite"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-sqlite"
        - "--database"
        - "/Users/zengxinyue/Desktop/仓库/ThoughtCoding/data/app.db"
```

## 示例3: 完整功能环境
```yaml
mcp:
  enabled: true
  autoDiscover: true
  connectionTimeout: 30
  servers:
    # 文件系统
    - name: "filesystem"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-filesystem"
        - "/Users/zengxinyue/Desktop/仓库/ThoughtCoding"
    
    # GitHub（需要替换 token）
    - name: "github"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-github"
        - "--token"
        - "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    
    # SQLite
    - name: "sqlite"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-sqlite"
        - "--database"
        - "./data/app.db"
```

## 快速测试命令

```bash
# 1. 重新编译项目
mvn clean package -DskipTests

# 2. 查看已连接的 MCP 服务器
java -jar target/thoughtcoding.jar mcp list

# 3. 查看 filesystem 服务器的工具
java -jar target/thoughtcoding.jar mcp tools filesystem

# 4. 测试文件读取功能
java -jar target/thoughtcoding.jar "请使用MCP工具读取README.md文件"
```

