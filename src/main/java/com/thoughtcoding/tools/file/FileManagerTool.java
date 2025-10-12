package com.thoughtcoding.tools.file;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.model.ToolResult;


import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件管理工具，支持基本的文件读写和目录操作
 */
public class FileManagerTool extends BaseTool {
    private final long maxFileSize;
    private final AppConfig appConfig;



    public FileManagerTool(AppConfig appConfig) {
        super("file_manager", "File management tool for reading, writing, and managing files");
        this.maxFileSize = appConfig.getTools().getFileManager().getMaxFileSize();
        this.appConfig = appConfig;
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();

        try {
            // 解析输入参数（简单解析，实际应该用JSON）
            String[] parts = input.split(" ", 2);
            String action = parts[0].toLowerCase();
            String path = parts.length > 1 ? parts[1] : "";

            switch (action) {
                case "read":
                    return readFile(path, startTime);
                case "write":
                    return writeFile(path, startTime);
                case "list":
                    return listFiles(path, startTime);
                case "create":
                    return createDirectory(path, startTime);
                case "delete":
                    return deleteFile(path, startTime);
                case "info":
                    return fileInfo(path, startTime);
                default:
                    return error("Unknown file action: " + action + ". Supported: read, write, list, create, delete, info",
                            System.currentTimeMillis() - startTime);
            }
        } catch (Exception e) {
            return error("File operation failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult readFile(String filePath, long startTime) {
        try {
            Path path = Paths.get(filePath).toAbsolutePath();

            if (!Files.exists(path)) {
                return error("File not found: " + filePath, System.currentTimeMillis() - startTime);
            }

            if (Files.isDirectory(path)) {
                return error("Path is a directory, not a file: " + filePath, System.currentTimeMillis() - startTime);
            }

            if (Files.size(path) > maxFileSize) {
                return error("File too large: " + Files.size(path) + " bytes (max: " + maxFileSize + ")",
                        System.currentTimeMillis() - startTime);
            }

            String content = Files.readString(path);
            return success(content, System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("Failed to read file: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult writeFile(String input, long startTime) {
        try {
            // 输入格式: "path content" 或 JSON格式
            String[] parts = input.split(" ", 2);
            if (parts.length < 2) {
                return error("Invalid write format. Use: write <path> <content>", System.currentTimeMillis() - startTime);
            }

            String filePath = parts[0];
            String content = parts[1];

            Path path = Paths.get(filePath).toAbsolutePath();

            // 确保父目录存在
            Files.createDirectories(path.getParent());

            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return success("File written successfully: " + path, System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("Failed to write file: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult listFiles(String directoryPath, long startTime) {
        try {
            Path path = Paths.get(directoryPath.isEmpty() ? "." : directoryPath).toAbsolutePath();

            if (!Files.exists(path)) {
                return error("Directory not found: " + path, System.currentTimeMillis() - startTime);
            }

            if (!Files.isDirectory(path)) {
                return error("Path is not a directory: " + path, System.currentTimeMillis() - startTime);
            }

            List<String> fileList = new ArrayList<>();
            Files.list(path)
                    .forEach(filePath -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                            String type = attrs.isDirectory() ? "DIR" : "FILE";
                            String size = attrs.isDirectory() ? "" : " (" + Files.size(filePath) + " bytes)";
                            fileList.add(String.format("%s %s%s", type, filePath.getFileName(), size));
                        } catch (IOException e) {
                            fileList.add("ERROR " + filePath.getFileName());
                        }
                    });

            String result = String.join("\n", fileList);
            return success(result.isEmpty() ? "Directory is empty" : result, System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("Failed to list directory: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult createDirectory(String dirPath, long startTime) {
        try {
            Path path = Paths.get(dirPath).toAbsolutePath();
            Files.createDirectories(path);
            return success("Directory created: " + path, System.currentTimeMillis() - startTime);
        } catch (IOException e) {
            return error("Failed to create directory: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult deleteFile(String filePath, long startTime) {
        try {
            Path path = Paths.get(filePath).toAbsolutePath();

            if (!Files.exists(path)) {
                return error("File or directory not found: " + filePath, System.currentTimeMillis() - startTime);
            }

            Files.delete(path);
            return success("Deleted: " + filePath, System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("Failed to delete: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult fileInfo(String filePath, long startTime) {
        try {
            Path path = Paths.get(filePath).toAbsolutePath();

            if (!Files.exists(path)) {
                return error("File not found: " + filePath, System.currentTimeMillis() - startTime);
            }

            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            StringBuilder info = new StringBuilder();
            info.append("Path: ").append(path).append("\n");
            info.append("Type: ").append(attrs.isDirectory() ? "Directory" : "File").append("\n");
            info.append("Size: ").append(attrs.size()).append(" bytes\n");
            info.append("Created: ").append(attrs.creationTime()).append("\n");
            info.append("Modified: ").append(attrs.lastModifiedTime()).append("\n");
            info.append("Accessed: ").append(attrs.lastAccessTime());

            return success(info.toString(), System.currentTimeMillis() - startTime);

        } catch (IOException e) {
            return error("Failed to get file info: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public String getCategory() {
        return "file";
    }

    @Override
    public boolean isEnabled() {
        return appConfig.getTools().getFileManager().isEnabled();
    }
}