// src/main/java/com/thoughtcoding/tools/exec/CodeExecutorTool.java
package com.thoughtcoding.tools.exec;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.model.ToolResult;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class CodeExecutorTool extends BaseTool {

    public CodeExecutorTool(AppConfig appConfig) {
        super("code_executor", "Execute code snippets in various programming languages");
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
            // 简单的Java代码执行（仅支持表达式）
            if (code.trim().endsWith(";")) {
                code = code.trim();
                code = code.substring(0, code.length() - 1);
            }

            // 使用JavaScript引擎执行简单的Java-like表达式
            return executeJavaScript("Java execution: " + code, startTime);

        } catch (Exception e) {
            return error("Java execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult executeJavaScript(String code, long startTime) {
        try {
            // 使用Java内置的JavaScript引擎
            javax.script.ScriptEngineManager manager = new javax.script.ScriptEngineManager();
            javax.script.ScriptEngine engine = manager.getEngineByName("javascript");

            if (engine == null) {
                return error("JavaScript engine not available", System.currentTimeMillis() - startTime);
            }

            Object result = engine.eval(code);
            String output = result != null ? result.toString() : "null";

            return success(output, System.currentTimeMillis() - startTime);

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
}