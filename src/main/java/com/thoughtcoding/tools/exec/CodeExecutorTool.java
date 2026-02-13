package com.thoughtcoding.tools.exec;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;

/**
 * 代码执行工具，支持多种编程语言的代码片段执行
 */
public class CodeExecutorTool extends BaseTool {

    private final AppConfig appConfig;
    public CodeExecutorTool(AppConfig appConfig) {
        super("code_executor", "Execute code snippets in various programming languages");
        this.appConfig = appConfig;
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();

        try {
            // 解析语言和代码
            String[] parts = input.split(" ", 2);
            if (parts.length < 2) {
                return error("Invalid format. Use: <language> <code>", System.currentTimeMillis() - startTime);
            }

            String language = parts[0].toLowerCase();
            String code = parts[1];

            switch (language) {
                case "java":
                    return executeJavaCode(code, startTime);
                case "javascript":
                case "js":
                    return executeJavaScript(code, startTime);
                case "python":
                case "py":
                    return executePython(code, startTime);
                default:
                    return error("Unsupported language: " + language + ". Supported: java, javascript, python",
                            System.currentTimeMillis() - startTime);
            }

        } catch (Exception e) {
            return error("Code execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult executeJavaCode(String code, long startTime) {
        try {
            // 通过编译和执行临时 Java 文件的方式执行 Java 代码
            // 创建临时文件
            File tempDir = Files.createTempDirectory("thoughtcoding_java_").toFile();

            // 提取类名（如果代码中包含类定义）
            String className = extractClassName(code);
            if (className == null) {
                // 如果没有类定义，包装成一个临时类
                className = "TempJavaCode";
                code = "public class " + className + " {\n" +
                       "    public static void main(String[] args) {\n" +
                       "        " + code + "\n" +
                       "    }\n" +
                       "}";
            }

            File javaFile = new File(tempDir, className + ".java");
            Files.writeString(javaFile.toPath(), code);

            try {
                // 编译 Java 代码
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    return error("Java compiler not available. Please ensure JDK (not JRE) is installed.",
                                System.currentTimeMillis() - startTime);
                }

                int compilationResult = compiler.run(null, null, null, javaFile.getAbsolutePath());
                if (compilationResult != 0) {
                    return error("Java compilation failed", System.currentTimeMillis() - startTime);
                }

                // 执行编译后的类
                ProcessBuilder processBuilder = new ProcessBuilder(
                    "java", "-cp", tempDir.getAbsolutePath(), className
                );
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                int exitCode = process.waitFor();
                String result = output.toString().trim();

                if (exitCode != 0) {
                    return error("Java execution failed:\n" + result, System.currentTimeMillis() - startTime);
                }

                return success(result.isEmpty() ? "Java code executed successfully (no output)" : result,
                        System.currentTimeMillis() - startTime);

            } finally {
                // 清理临时文件
                deleteDirectory(tempDir);
            }

        } catch (Exception e) {
            return error("Java execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 从 Java 代码中提取类名
     */
    private String extractClassName(String code) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "public\\s+class\\s+(\\w+)"
        );
        java.util.regex.Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    private ToolResult executeJavaScript(String code, long startTime) {
        try {
            // 通过 Node.js 执行 JavaScript 代码
            // 注意: Nashorn JavaScript 引擎在 JDK 15+ 已被移除
            File tempFile = File.createTempFile("thoughtcoding_js_", ".js");
            try {
                Files.writeString(tempFile.toPath(), code);

                // 尝试使用 node 命令
                ProcessBuilder processBuilder = new ProcessBuilder("node", tempFile.getAbsolutePath());
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                int exitCode = process.waitFor();
                String result = output.toString().trim();

                if (exitCode != 0) {
                    // 如果失败，可能是因为没有安装 Node.js
                    if (result.isEmpty() || result.contains("command not found") || result.contains("'node' is not recognized")) {
                        return error("Node.js is not installed or not in PATH. Please install Node.js to execute JavaScript code.",
                                System.currentTimeMillis() - startTime);
                    }
                    return error("JavaScript execution failed:\n" + result, System.currentTimeMillis() - startTime);
                }

                return success(result.isEmpty() ? "JavaScript code executed successfully (no output)" : result,
                        System.currentTimeMillis() - startTime);

            } finally {
                tempFile.delete();
            }

        } catch (IOException e) {
            if (e.getMessage().contains("Cannot run program \"node\"")) {
                return error("Node.js is not installed or not in PATH. Please install Node.js to execute JavaScript code.",
                        System.currentTimeMillis() - startTime);
            }
            return error("JavaScript execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("JavaScript execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult executePython(String code, long startTime) {
        try {
            // 通过系统命令执行Python代码
            File tempFile = File.createTempFile("thoughtcoding_python_", ".py");
            try {
                Files.writeString(tempFile.toPath(), code);

                ProcessBuilder processBuilder = new ProcessBuilder("python", tempFile.getAbsolutePath());
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                int exitCode = process.waitFor();
                String result = output.toString().trim();

                if (exitCode != 0) {
                    return error("Python execution failed:\n" + result, System.currentTimeMillis() - startTime);
                }

                return success(result.isEmpty() ? "Python code executed successfully (no output)" : result,
                        System.currentTimeMillis() - startTime);

            } finally {
                tempFile.delete();
            }

        } catch (Exception e) {
            return error("Python execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public String getCategory() {
        return "exec";
    }

    @Override
    public boolean isEnabled() {
        return appConfig != null && appConfig.getTools().getCommandExec().isEnabled();
    }
}