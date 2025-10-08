// src/main/java/com/thoughtcoding/util/FileUtils.java
package com.thoughtcoding.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class FileUtils {

    private FileUtils() {
        // 工具类，防止实例化
    }

    /**
     * 读取文件内容为字符串
     */
    public static String readFile(String filePath) throws IOException {
        return readFile(Paths.get(filePath));
    }

    public static String readFile(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /**
     * 写入字符串到文件
     */
    public static void writeFile(String filePath, String content) throws IOException {
        writeFile(Paths.get(filePath), content);
    }

    public static void writeFile(Path filePath, String content) throws IOException {
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * 追加内容到文件
     */
    public static void appendToFile(String filePath, String content) throws IOException {
        appendToFile(Paths.get(filePath), content);
    }

    public static void appendToFile(Path filePath, String content) throws IOException {
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * 检查文件是否存在
     */
    public static boolean exists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * 创建目录（包括父目录）
     */
    public static void createDirectories(String dirPath) throws IOException {
        Files.createDirectories(Paths.get(dirPath));
    }

    /**
     * 列出目录下的所有文件
     */
    public static List<Path> listFiles(String dirPath) throws IOException {
        return listFiles(Paths.get(dirPath));
    }

    public static List<Path> listFiles(Path dirPath) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
            try (var stream = Files.list(dirPath)) {
                stream.forEach(files::add);
            }
        }
        return files;
    }

    /**
     * 递归列出目录下的所有文件
     */
    public static List<Path> listFilesRecursive(String dirPath) throws IOException {
        return listFilesRecursive(Paths.get(dirPath));
    }

    public static List<Path> listFilesRecursive(Path dirPath) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
            Files.walkFileTree(dirPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return files;
    }

    /**
     * 删除文件或目录（递归）
     */
    public static void deleteRecursive(String path) throws IOException {
        deleteRecursive(Paths.get(path));
    }

    public static void deleteRecursive(Path path) throws IOException {
        if (Files.exists(path)) {
            if (Files.isDirectory(path)) {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.delete(path);
            }
        }
    }

    /**
     * 复制文件或目录
     */
    public static void copy(String source, String target) throws IOException {
        copy(Paths.get(source), Paths.get(target));
    }

    public static void copy(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path targetDir = target.resolve(source.relativize(dir));
                    Files.createDirectories(targetDir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 获取文件大小
     */
    public static long getFileSize(String filePath) throws IOException {
        return Files.size(Paths.get(filePath));
    }

    /**
     * 获取文件扩展名
     */
    public static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }

    /**
     * 获取文件名（不含扩展名）
     */
    public static String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    /**
     * 压缩文件为GZIP格式
     */
    public static void compressToGzip(String sourceFile, String targetFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(targetFile);
             GZIPOutputStream gzipOS = new GZIPOutputStream(fos)) {

            StreamUtils.copyStream(fis, gzipOS);
        }
    }

    /**
     * 解压GZIP文件
     */
    public static void decompressFromGzip(String sourceFile, String targetFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(sourceFile);
             GZIPInputStream gzipIS = new GZIPInputStream(fis);
             FileOutputStream fos = new FileOutputStream(targetFile)) {

            StreamUtils.copyStream(gzipIS, fos);
        }
    }

    /**
     * 创建临时文件
     */
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(prefix, suffix);
    }

    /**
     * 创建临时目录
     */
    public static Path createTempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }
}