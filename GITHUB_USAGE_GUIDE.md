# GitHub MCP 工具使用指南

## ✅ 好消息：参数传递已修复！

从最新的测试日志可以看到：
```
📝 参数: {query=user:zengxinyue}  ✅ 正确传递了参数！
```

之前的空参数 `{}` 问题已经完全解决！

## ❌ 当前问题：GitHub API 权限错误

```
The listed users and repositories cannot be searched either because 
the resources do not exist or you do not have permission to view them.
```

### 可能的原因

1. **GitHub Token 过期或无效**
   - 你的token: `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxx` (已隐藏)
   - GitHub Personal Access Token 可能已过期
   
2. **用户名不存在或拼写错误**
   - AI使用了 `user:zengxinyue` 进行搜索
   - 请确认你的GitHub用户名是否正确

3. **Token权限不足**
   - Token需要有 `repo` 权限才能搜索仓库

## 🔧 解决方案

### 方案1：更新GitHub Token（推荐）

1. **访问GitHub生成新Token**
   ```
   https://github.com/settings/tokens/new
   ```

2. **配置权限**（至少需要以下权限）：
   - ✅ `repo` - 完整的仓库访问权限
   - ✅ `read:user` - 读取用户信息
   - ✅ `read:org` - 读取组织信息（可选）

3. **更新config.yaml**
   ```yaml
   - name: "github"
     command: "npx"
     enabled: true
     args:
       - "@modelcontextprotocol/server-github"
       - "--token"
       - "YOUR_NEW_TOKEN_HERE"  # 替换为新token
   ```

4. **重启程序**
   ```bash
   pkill -f thoughtcoding
   ./bin/thought
   ```

### 方案2：使用公开搜索（无需token）

如果你只是想搜索GitHub上的公开仓库，可以使用不同的搜索方式：

**搜索公开项目示例**：
```
# 搜索Java相关项目
帮我搜索GitHub上stars最多的Java项目

# 搜索特定主题
帮我搜索GitHub上关于Spring Boot的项目

# 搜索特定语言
帮我搜索Python机器学习相关的项目
```

AI会使用类似这样的查询：
```
query=language:java sort:stars
query=topic:spring-boot
query=language:python topic:machine-learning
```

### 方案3：验证你的GitHub用户名

在终端中运行：
```bash
curl https://api.github.com/users/zengxinyue
```

如果返回404，说明用户名不存在，需要使用正确的GitHub用户名。

## 📝 正确的使用示例

### ✅ 推荐用法（搜索公开仓库）

```
# 搜索热门Java项目
帮我搜索GitHub上stars超过1000的Java项目

# 搜索特定组织的仓库
帮我搜索spring-projects组织的仓库

# 搜索包含特定关键词的仓库
帮我搜索关于微服务的Java项目
```

### ⚠️ 需要认证的用法（需要有效token）

```
# 查看自己的仓库（需要有效token）
帮我查看我的私有仓库

# 查看特定用户的仓库（用户必须存在）
帮我查看 torvalds 的仓库
```

## 🧪 测试步骤

1. **首先验证token是否有效**：
   ```bash
   curl -H "Authorization: token YOUR_GITHUB_TOKEN" \
        https://api.github.com/user
   ```
   
   如果返回你的用户信息，说明token有效；如果返回401，说明token无效。

2. **测试搜索功能**：
   ```bash
   # 重启程序
   ./bin/thought
   
   # 尝试搜索公开仓库
   帮我搜索GitHub上最受欢迎的Java项目
   ```

## 📊 技术细节

修复后的调用流程：
```
用户: "帮我查看我的github仓库"
  ↓
AI理解并生成工具调用:
  {
    "tool_name": "search_repositories",
    "parameters": {
      "query": "user:zengxinyue"  ✅ 正确传递了参数
    }
  }
  ↓
GitHub API调用:
  GET /search/repositories?q=user:zengxinyue
  Authorization: token ghp_5W3m...
  ↓
GitHub返回:
  ❌ 422 Validation Error (用户不存在或token无权限)
```

## 🎯 下一步行动

1. **验证GitHub用户名是否正确**
2. **更新GitHub Token（如果过期）**
3. **或者使用公开搜索功能**

修复后重新测试即可！🚀

