package com.thoughtcoding.core;

import com.thoughtcoding.observability.Telemetry;

import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.hook.HookType;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.ui.ThoughtCodingUI;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.PermissionHook;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 直接命令执行器
 * 负责识别和直接执行系统命令，绕过AI交互
 */
public class DirectCommandExecutor {

    private final ThoughtCodingUI ui;
    private final ToolExecutionPipeline toolPipeline;
    private final ProjectContext projectContext;

    // 直接执行的模式匹配
    private static final Map<Pattern, String> DIRECT_COMMANDS = new HashMap<>();

    // 自然语言命令映射
    private static final Map<Pattern, String> NATURAL_LANGUAGE_COMMANDS = new HashMap<>();

    static {
        // Java相关命令
        DIRECT_COMMANDS.put(Pattern.compile("^java\\s+-?version$", Pattern.CASE_INSENSITIVE), "java -version");
        DIRECT_COMMANDS.put(Pattern.compile("^javac\\s+-?version$", Pattern.CASE_INSENSITIVE), "javac -version");

        // Git命令
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+status$", Pattern.CASE_INSENSITIVE), "git status");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+log(?:\\s+-\\d+)?$", Pattern.CASE_INSENSITIVE), null);
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+push$", Pattern.CASE_INSENSITIVE), "git push");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+pull$", Pattern.CASE_INSENSITIVE), "git pull");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+branch$", Pattern.CASE_INSENSITIVE), "git branch");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+diff$", Pattern.CASE_INSENSITIVE), "git diff");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+stash$", Pattern.CASE_INSENSITIVE), "git stash");

        // Maven 构建命令
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+clean$", Pattern.CASE_INSENSITIVE), "mvn clean");
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+compile$", Pattern.CASE_INSENSITIVE), "mvn compile");
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+test$", Pattern.CASE_INSENSITIVE), "mvn test");
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+package$", Pattern.CASE_INSENSITIVE), "mvn package");
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+install$", Pattern.CASE_INSENSITIVE), "mvn install");
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+clean\\s+install$", Pattern.CASE_INSENSITIVE), "mvn clean install");

        // 系统命令
        DIRECT_COMMANDS.put(Pattern.compile("^pwd$", Pattern.CASE_INSENSITIVE), "pwd");
        DIRECT_COMMANDS.put(Pattern.compile("^whoami$", Pattern.CASE_INSENSITIVE), "whoami");
        DIRECT_COMMANDS.put(Pattern.compile("^ls(?:\\s+.*)?$", Pattern.CASE_INSENSITIVE), null);  // ls 可以带参数
        DIRECT_COMMANDS.put(Pattern.compile("^cd\\s+.+$", Pattern.CASE_INSENSITIVE), null);  // cd 命令，保留原始输入
        DIRECT_COMMANDS.put(Pattern.compile("^cd$", Pattern.CASE_INSENSITIVE), "cd");  // cd 不带参数（回到用户主目录）
        DIRECT_COMMANDS.put(Pattern.compile("^cd\\s+.+$", Pattern.CASE_INSENSITIVE), null);  // cd 命令，保留原始输入
        DIRECT_COMMANDS.put(Pattern.compile("^cd$", Pattern.CASE_INSENSITIVE), "cd");  // cd 不带参数（回到用户主目录）

        // npm 包管理
        DIRECT_COMMANDS.put(Pattern.compile("^npm\\s+install$", Pattern.CASE_INSENSITIVE), "npm install");
        DIRECT_COMMANDS.put(Pattern.compile("^npm\\s+test$", Pattern.CASE_INSENSITIVE), "npm test");
        DIRECT_COMMANDS.put(Pattern.compile("^npm\\s+start$", Pattern.CASE_INSENSITIVE), "npm start");

        // 代码格式化
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+spotless:apply$", Pattern.CASE_INSENSITIVE), "mvn spotless:apply");
        DIRECT_COMMANDS.put(Pattern.compile("^mvn\\s+spotless:check$", Pattern.CASE_INSENSITIVE), "mvn spotless:check");

        // 自然语言命令映射
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*暂存.*修改.*", Pattern.CASE_INSENSITIVE), "git stash");

        // Maven相关
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*maven.*编译.*", Pattern.CASE_INSENSITIVE), "mvn compile");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*mvn.*编译.*", Pattern.CASE_INSENSITIVE), "mvn compile");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*maven.*打包.*", Pattern.CASE_INSENSITIVE), "mvn package");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*mvn.*打包.*", Pattern.CASE_INSENSITIVE), "mvn package");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*maven.*测试.*", Pattern.CASE_INSENSITIVE), "mvn test");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*运行.*测试.*", Pattern.CASE_INSENSITIVE), "mvn test");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*maven.*清理.*", Pattern.CASE_INSENSITIVE), "mvn clean");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*清理.*项目.*", Pattern.CASE_INSENSITIVE), "mvn clean");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*依赖.*树.*", Pattern.CASE_INSENSITIVE), "mvn dependency:tree");

        // Gradle相关
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*gradle.*构建.*", Pattern.CASE_INSENSITIVE), "gradle build");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*gradle.*打包.*", Pattern.CASE_INSENSITIVE), "gradle build");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*gradle.*测试.*", Pattern.CASE_INSENSITIVE), "gradle test");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*gradle.*任务.*", Pattern.CASE_INSENSITIVE), "gradle tasks");

        // npm相关
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*安装.*依赖.*", Pattern.CASE_INSENSITIVE), "npm install");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*npm.*安装.*", Pattern.CASE_INSENSITIVE), "npm install");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*启动.*项目.*", Pattern.CASE_INSENSITIVE), "npm start");

        // 代码格式化
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*格式化.*代码.*", Pattern.CASE_INSENSITIVE), "mvn spotless:apply");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*整理.*代码.*", Pattern.CASE_INSENSITIVE), "mvn spotless:apply");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*检查.*代码.*格式.*", Pattern.CASE_INSENSITIVE), "mvn spotless:check");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*检查.*代码.*风格.*", Pattern.CASE_INSENSITIVE), "mvn checkstyle:check");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*代码.*检查.*", Pattern.CASE_INSENSITIVE), "mvn checkstyle:check");

        // 测试覆盖率
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*测试.*覆盖率.*", Pattern.CASE_INSENSITIVE), "mvn jacoco:report");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*生成.*覆盖率.*报告.*", Pattern.CASE_INSENSITIVE), "mvn test jacoco:report");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*覆盖率.*", Pattern.CASE_INSENSITIVE), "mvn jacoco:report");

        // 项目初始化
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*创建.*maven.*项目.*", Pattern.CASE_INSENSITIVE), "mvn archetype:generate");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*初始化.*npm.*项目.*", Pattern.CASE_INSENSITIVE), "npm init -y");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*创建.*package\\.json.*", Pattern.CASE_INSENSITIVE), "npm init -y");

        // Git批量操作
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*提交.*并.*推送.*", Pattern.CASE_INSENSITIVE), "BATCH:git_commit_push");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*全部.*提交.*推送.*", Pattern.CASE_INSENSITIVE), "BATCH:git_add_commit_push");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*拉取.*并.*合并.*", Pattern.CASE_INSENSITIVE), "git pull");

        // Git 基础命令
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*git.*状态.*", Pattern.CASE_INSENSITIVE), "git status");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*git.*状态.*", Pattern.CASE_INSENSITIVE), "git status");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*状态.*", Pattern.CASE_INSENSITIVE), "git status");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^状态$", Pattern.CASE_INSENSITIVE), "git status");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*git.*日志.*", Pattern.CASE_INSENSITIVE), "git log");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*git.*日志.*", Pattern.CASE_INSENSITIVE), "git log");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*提交.*历史.*", Pattern.CASE_INSENSITIVE), "git log");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*分支.*", Pattern.CASE_INSENSITIVE), "git branch");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*git.*分支.*", Pattern.CASE_INSENSITIVE), "git branch");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*差异.*", Pattern.CASE_INSENSITIVE), "git diff");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*git.*差异.*", Pattern.CASE_INSENSITIVE), "git diff");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*推送.*代码.*", Pattern.CASE_INSENSITIVE), "git push");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*拉取.*代码.*", Pattern.CASE_INSENSITIVE), "git pull");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*暂存.*所有.*", Pattern.CASE_INSENSITIVE), "git add .");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*添加.*所有.*文件.*", Pattern.CASE_INSENSITIVE), "git add .");

        // Maven/构建相关
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*清理.*并.*构建.*", Pattern.CASE_INSENSITIVE), "mvn clean install");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*完整.*构建.*", Pattern.CASE_INSENSITIVE), "mvn clean install");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*npm.*启动.*", Pattern.CASE_INSENSITIVE), "npm start");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*包.*列表.*", Pattern.CASE_INSENSITIVE), "npm list");

        // 版本查看
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*java.*版本.*", Pattern.CASE_INSENSITIVE), "java -version");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*java.*版本.*", Pattern.CASE_INSENSITIVE), "java -version");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*node.*版本.*", Pattern.CASE_INSENSITIVE), "node -v");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*node.*版本.*", Pattern.CASE_INSENSITIVE), "node -v");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*python.*版本.*", Pattern.CASE_INSENSITIVE), "python --version");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*python.*版本.*", Pattern.CASE_INSENSITIVE), "python --version");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*maven.*版本.*", Pattern.CASE_INSENSITIVE), "mvn -version");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*gradle.*版本.*", Pattern.CASE_INSENSITIVE), "gradle -version");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*npm.*版本.*", Pattern.CASE_INSENSITIVE), "npm -v");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*git.*版本.*", Pattern.CASE_INSENSITIVE), "git --version");

        // 系统信息
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*当前.*目录.*", Pattern.CASE_INSENSITIVE), "pwd");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*我在.*哪.*", Pattern.CASE_INSENSITIVE), "pwd");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*文件.*", Pattern.CASE_INSENSITIVE), "ls -la");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*列出.*文件.*", Pattern.CASE_INSENSITIVE), "ls -la");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*当前.*用户.*", Pattern.CASE_INSENSITIVE), "whoami");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*我是.*谁.*", Pattern.CASE_INSENSITIVE), "whoami");

        // Docker相关
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*容器.*", Pattern.CASE_INSENSITIVE), "docker ps");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*docker.*容器.*", Pattern.CASE_INSENSITIVE), "docker ps");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*镜像.*", Pattern.CASE_INSENSITIVE), "docker images");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*docker.*镜像.*", Pattern.CASE_INSENSITIVE), "docker images");

        // 进程和端口
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*进程.*", Pattern.CASE_INSENSITIVE), "ps aux");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*端口.*", Pattern.CASE_INSENSITIVE), "netstat -an");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*环境.*变量.*", Pattern.CASE_INSENSITIVE), "env");

        // 快速构建
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*快速.*打包.*", Pattern.CASE_INSENSITIVE), "mvn clean package -DskipTests");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*跳过.*测试.*打包.*", Pattern.CASE_INSENSITIVE), "mvn clean package -DskipTests");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*编译.*项目.*", Pattern.CASE_INSENSITIVE), "mvn compile");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*构建.*项目.*", Pattern.CASE_INSENSITIVE), "mvn clean install");

        // 简单单词命令（最常用）
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^构建$", Pattern.CASE_INSENSITIVE), "mvn clean install");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^编译$", Pattern.CASE_INSENSITIVE), "mvn compile");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^测试$", Pattern.CASE_INSENSITIVE), "mvn test");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^打包$", Pattern.CASE_INSENSITIVE), "mvn package");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^清理$", Pattern.CASE_INSENSITIVE), "mvn clean");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^安装$", Pattern.CASE_INSENSITIVE), "mvn install");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^运行$", Pattern.CASE_INSENSITIVE), "npm start");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^启动$", Pattern.CASE_INSENSITIVE), "npm start");

        // 智能上下文命令
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^项目信息$", Pattern.CASE_INSENSITIVE), "SMART:info");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile("^推荐命令$", Pattern.CASE_INSENSITIVE), "SMART:recommend");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*项目.*信息.*", Pattern.CASE_INSENSITIVE), "SMART:info");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*推荐.*命令.*", Pattern.CASE_INSENSITIVE), "SMART:recommend");
        NATURAL_LANGUAGE_COMMANDS.put(Pattern.compile(".*查看.*项目.*", Pattern.CASE_INSENSITIVE), "SMART:info");
    }

    /**
     * 构造函数
     */
    public DirectCommandExecutor(ThoughtCodingContext context) {
        this(context, new ToolExecutionConfirmation(
                context.getUi(),
                context.getUi().getLineReader(),
                "[直接命令]",
                context::getConsoleInputRouter));
    }

    /** 测试/嵌入场景可注入确认组件，生产入口使用上面的统一确认栈。 */
    DirectCommandExecutor(ThoughtCodingContext context, ToolExecutionConfirmation confirmation) {
        this.ui = context.getUi();
        HookRegistry hookRegistry = context.getHookRegistry().copy()
                .registerFirst(HookType.PRE_TOOL_USE, new PermissionHook(confirmation));
        this.toolPipeline = new ToolExecutionPipeline(
                context, hookRegistry, context.getToolRegistry());
        this.projectContext = new ProjectContext(System.getProperty("user.dir"));
    }

    /**
     * 判断输入是否应该直接执行
     */
    public boolean shouldExecuteDirectly(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }

        String trimmedInput = input.trim();

        // 🔥 优先检查：排除应该由 MCP/AI 处理的请求
        if (shouldUseMCP(trimmedInput)) {
            return false;  // 不应该直接执行，应该交给 AI/MCP
        }

        // 检查直接命令模式
        for (Pattern pattern : DIRECT_COMMANDS.keySet()) {
            if (pattern.matcher(trimmedInput).matches()) {
                return true;
            }
        }

        // 检查自然语言命令
        for (Pattern pattern : NATURAL_LANGUAGE_COMMANDS.keySet()) {
            if (pattern.matcher(trimmedInput).matches()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 🔥 从自然语言中提取路径参数
     * 例如："查看sessions下的文件" -> "sessions"
     *       "列出src目录的内容" -> "src"
     */
    private String extractPathFromNaturalLanguage(String input) {
        // 匹配 "XXX下" 或 "XXX目录" 或 "XXX文件夹" 的模式
        java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile(
            "(?:查看|列出|显示|列表|ls)\\s*([\\w/.\\-]+)(?:下|目录|文件夹|里|中|内)?(?:的|之)?(?:文件|内容|列表)?",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher1 = pattern1.matcher(input);
        if (matcher1.find()) {
            return matcher1.group(1);
        }

        // 匹配另一种模式: "查看XXX"
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile(
            "(?:查看|列出|显示).*?([\\w/.\\-]+).*?(?:文件|目录|内容)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher2 = pattern2.matcher(input);
        if (matcher2.find()) {
            String path = matcher2.group(1);
            // 过滤掉一些常见的干扰词
            if (!path.matches("(?i)(的|所有|全部|当前)")) {
                return path;
            }
        }

        return null;
    }

    /**
     * 判断输入是否应该使用 MCP 工具处理
     */
    private boolean shouldUseMCP(String input) {
        String lowerInput = input.toLowerCase();

        // cd 命令应该提示用户或使用文件系统 MCP
        if (lowerInput.startsWith("cd ") || lowerInput.equals("cd")) {
            return true;  // 交给 AI 处理，可以使用 filesystem MCP 或给出提示
        }

        // GitHub 相关的关键词
        if (lowerInput.contains("github") ||
            lowerInput.contains("仓库") ||
            lowerInput.contains("项目") && (lowerInput.contains("搜索") ||
                                          lowerInput.contains("查找") ||
                                          lowerInput.contains("查看") ||
                                          lowerInput.contains("最火") ||
                                          lowerInput.contains("流行") ||
                                          lowerInput.contains("热门"))) {
            return true;
        }

        // 文件系统操作（复杂的）
        if ((lowerInput.contains("读取") || lowerInput.contains("写入") || lowerInput.contains("创建")) &&
            lowerInput.contains("文件")) {
            return true;
        }

        // 数据库查询
        if (lowerInput.contains("sql") || lowerInput.contains("数据库") || lowerInput.contains("查询")) {
            return true;
        }

        // 网络搜索
        if ((lowerInput.contains("搜索") || lowerInput.contains("查找")) &&
            (lowerInput.contains("网络") || lowerInput.contains("网页") || lowerInput.contains("互联网"))) {
            return true;
        }

        return false;
    }

    /**
     * 执行直接命令
     */
    public boolean executeDirectCommand(String input) {
        try (var task = Telemetry.task(null, input)) {
            try { return executeDirectCommandInternal(input); }
            catch (RuntimeException error) { task.error(error); throw error; }
        }
    }

    private boolean executeDirectCommandInternal(String input) {
        String trimmedInput = input.trim();
        String command = null;

        // 先尝试自然语言匹配
        for (Map.Entry<Pattern, String> entry : NATURAL_LANGUAGE_COMMANDS.entrySet()) {
            if (entry.getKey().matcher(trimmedInput).matches()) {
                command = entry.getValue();

                // 🔥 智能提取路径参数（针对文件/目录查看命令）
                if (command != null && command.equals("ls -la")) {
                    String extractedPath = extractPathFromNaturalLanguage(trimmedInput);
                    if (extractedPath != null && !extractedPath.isEmpty()) {
                        command = "ls -la " + extractedPath;
                    }
                }

                break;
            }
        }

        // 如果没有匹配到自然语言，尝试直接命令
        if (command == null) {
            for (Map.Entry<Pattern, String> entry : DIRECT_COMMANDS.entrySet()) {
                if (entry.getKey().matcher(trimmedInput).matches()) {
                    command = entry.getValue() != null ? entry.getValue() : trimmedInput;
                    break;
                }
            }
        }

        if (command == null) {
            return false;
        }

        // 处理批量操作
        if (command.startsWith("BATCH:")) {
            return executeBatchOperation(command.substring(6));
        }

        // 处理智能上下文命令
        if (command.startsWith("SMART:")) {
            return executeSmartCommand(command.substring(6));
        }

        // 处理需要用户输入的命令
        if (command.equals("git commit") && !command.contains("-m")) {
            String message = ui.readInput("📝 请输入 commit message: ");
            if (message == null || message.trim().isEmpty()) {
                ui.displayWarning("⚠️  未提供 commit message，操作已取消");
                return false;
            }
            command = "git commit -m \"" + message + "\"";
        }

        // 执行命令
        executeCommand(command);
        return true;
    }

    /**
     * 执行批量操作
     */
    private boolean executeBatchOperation(String batchName) {
        ui.displayInfo("🚀 执行批量操作: " + batchName);

        List<String> commands = new ArrayList<>();
        switch (batchName) {
            case "git_commit_push":
                String message1 = ui.readInput("📝 请输入 commit message: ");
                if (message1 == null || message1.trim().isEmpty()) {
                    ui.displayWarning("⚠️  未提供 commit message，操作已取消");
                    return false;
                }
                commands.add("git commit -m \"" + message1 + "\"");
                commands.add("git push");
                break;

            case "git_add_commit_push":
                String message2 = ui.readInput("📝 请输入 commit message: ");
                if (message2 == null || message2.trim().isEmpty()) {
                    ui.displayWarning("⚠️  未提供 commit message，操作已取消");
                    return false;
                }
                commands.add("git add .");
                commands.add("git commit -m \"" + message2 + "\"");
                commands.add("git push");
                break;

            default:
                ui.displayError("❌ 未知的批量操作: " + batchName);
                return false;
        }

        // 显示将要执行的命令
        ui.displayWarning("⚠️  即将执行以下命令:");
        for (int i = 0; i < commands.size(); i++) {
            ui.displayInfo("  " + (i + 1) + ". " + commands.get(i));
        }
        ui.displayInfo("每一步执行前都会经过统一权限检查与独立确认。");

        // 执行所有命令
        for (int i = 0; i < commands.size(); i++) {
            String cmd = commands.get(i);
            ui.displayInfo("📍 执行步骤 " + (i + 1) + "/" + commands.size() + ": " + cmd);

            ToolResult result = runBash(cmd);

            if (result.isSuccess()) {
                ui.displaySuccess("✅ 步骤 " + (i + 1) + " 成功");
                if (result.getOutput() != null && !result.getOutput().isEmpty()) {
                    ui.displayInfo("输出:\n" + result.getOutput());
                }
            } else {
                ui.displayError("❌ 步骤 " + (i + 1) + " 失败: " + result.getError());
                ui.displayWarning("⚠️  批量操作在步骤 " + (i + 1) + " 中断");
                return false;
            }
        }

        ui.displaySuccess("🎉 批量操作全部完成！");
        return true;
    }

    /**
     * 用注册表中的 bash 工具执行命令，完整经过 PreToolUse 权限 Hook、统一 Dispatcher
     * 和 PostToolUse Hook。无论拒绝或执行失败都返回非空结果。
     */
    ToolResult runBash(String command) {
        Map<String, Object> parameters = Collections.singletonMap("command", command);
        ToolCall call = new ToolCall("bash", parameters, null, false, 0, false, null);

        return toolPipeline.execute(call, null, Collections.emptyList()).result();
    }

    /**
     * 执行单个命令
     */
    private void executeCommand(String command) {
        try {
            ToolResult result = runBash(command);

            if (result.isSuccess()) {
                // 只显示命令输出结果
                if (result.getOutput() != null && !result.getOutput().isEmpty()) {
                    ui.displayInfo(result.getOutput());
                }

                // 显示执行时间
                if (result.getExecutionTime() > 0) {
                    ui.displayInfo("⏱️  执行时间: " + result.getExecutionTime() + "ms");
                }
            } else {
                String errorMsg = result.getError() != null ? result.getError() : result.getOutput();
                ui.displayError("❌ 命令执行失败: " + errorMsg);
            }

        } catch (Exception e) {
            ui.displayError("❌ 命令执行异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 执行智能上下文命令
     */
    private boolean executeSmartCommand(String smartCommand) {
        // 移除"智能上下文"提示，直接执行

        switch (smartCommand.toLowerCase()) {
            case "info":
                displayProjectInfo();
                return true;

            case "recommend":
                displayRecommendedCommands();
                return true;

            case "build":
                String buildCmd = projectContext.getBuildCommand();
                if (buildCmd != null) {
                    // 移除"智能识别"提示
                    executeCommand(buildCmd);
                    return true;
                }
                break;

            case "test":
                String testCmd = projectContext.getTestCommand();
                if (testCmd != null) {
                    // 移除"智能识别"提示
                    executeCommand(testCmd);
                    return true;
                }
                break;

            case "clean":
                String cleanCmd = projectContext.getCleanCommand();
                if (cleanCmd != null) {
                    // 移除"智能识别"提示
                    executeCommand(cleanCmd);
                    return true;
                }
                break;

            default:
                ui.displayError("❌ 未知的智能命令: " + smartCommand);
                return false;
        }

        ui.displayError("❌ 无法识别项目类型，无法执行智能命令");
        return false;
    }

    /**
     * 显示项目信息
     */
    private void displayProjectInfo() {
        ui.displayInfo("🔍 项目信息:");
        ui.displayInfo("📁 项目类型: " + projectContext.getProjectType());
        ui.displayInfo("📂 工作目录: " + projectContext.getProjectRoot());

        String buildTool = projectContext.getBuildTool();
        if (buildTool != null) {
            ui.displayInfo("🔧 构建工具: " + buildTool);
        }

        ui.displayInfo("\n" + projectContext.getSummary());
    }

    /**
     * 显示推荐命令
     */
    private void displayRecommendedCommands() {
        ui.displayInfo("💡 推荐命令:");

        String[] recommended = projectContext.getRecommendedCommands();
        if (recommended != null && recommended.length > 0) {
            for (int i = 0; i < recommended.length; i++) {
                ui.displayInfo("  " + (i + 1) + ". " + recommended[i]);
            }
        } else {
            ui.displayInfo("  暂无推荐命令");
        }
    }

    /**
     * 列出支持的命令
     */
    public void listSupportedCommands() {
        ui.printAbove("\n🔧 支持直接执行的命令:");
        ui.printAbove("──────────────────────────────────");

        Map<String, List<String>> categorizedCommands = new LinkedHashMap<>();
        categorizedCommands.put("Maven", Arrays.asList("mvn clean", "mvn compile", "mvn test", "mvn package"));
        categorizedCommands.put("Git", Arrays.asList("git status", "git push", "git pull", "git branch"));
        categorizedCommands.put("npm", Arrays.asList("npm install", "npm test", "npm start"));
        categorizedCommands.put("系统", Arrays.asList("pwd", "whoami", "ls"));

        for (Map.Entry<String, List<String>> entry : categorizedCommands.entrySet()) {
            ui.printAbove("\n" + entry.getKey() + ":");
            entry.getValue().forEach(cmd -> ui.printAbove("  • " + cmd));
        }

        ui.printAbove("\n💡 这些命令会绕过AI直接执行，提供更快的响应");
    }
}

