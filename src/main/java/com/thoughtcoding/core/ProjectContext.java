package com.thoughtcoding.core;

import java.io.File;
import java.util.*;

/**
 * 项目上下文检测器
 * 自动识别当前项目类型，提供智能命令建议
 */
public class ProjectContext {

    private final String workingDirectory;
    private ProjectType projectType;
    private Set<String> detectedTools;

    public enum ProjectType {
        MAVEN("Maven 项目"),
        GRADLE("Gradle 项目"),
        NPM("Node.js 项目"),
        PYTHON("Python 项目"),
        GO("Go 项目"),
        RUST("Rust 项目"),
        MIXED("混合项目"),
        UNKNOWN("未知项目");

        private final String displayName;

        ProjectType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public ProjectContext(String workingDirectory) {
        this.workingDirectory = workingDirectory != null ? workingDirectory : System.getProperty("user.dir");
        this.detectedTools = new HashSet<>();
        detectProjectType();
    }

    /**
     * 检测项目类型
     */
    private void detectProjectType() {
        File dir = new File(workingDirectory);

        // 检测各种项目标识文件
        boolean hasPom = new File(dir, "pom.xml").exists();
        boolean hasGradle = new File(dir, "build.gradle").exists() || new File(dir, "build.gradle.kts").exists();
        boolean hasPackageJson = new File(dir, "package.json").exists();
        boolean hasRequirements = new File(dir, "requirements.txt").exists() || new File(dir, "setup.py").exists();
        boolean hasGoMod = new File(dir, "go.mod").exists();
        boolean hasCargo = new File(dir, "Cargo.toml").exists();

        // 统计检测到的工具数量
        int detectedCount = 0;

        if (hasPom) {
            detectedTools.add("maven");
            detectedCount++;
        }
        if (hasGradle) {
            detectedTools.add("gradle");
            detectedCount++;
        }
        if (hasPackageJson) {
            detectedTools.add("npm");
            detectedCount++;
        }
        if (hasRequirements) {
            detectedTools.add("python");
            detectedCount++;
        }
        if (hasGoMod) {
            detectedTools.add("go");
            detectedCount++;
        }
        if (hasCargo) {
            detectedTools.add("rust");
            detectedCount++;
        }

        // 确定主要项目类型
        if (detectedCount > 1) {
            projectType = ProjectType.MIXED;
        } else if (hasPom) {
            projectType = ProjectType.MAVEN;
        } else if (hasGradle) {
            projectType = ProjectType.GRADLE;
        } else if (hasPackageJson) {
            projectType = ProjectType.NPM;
        } else if (hasRequirements) {
            projectType = ProjectType.PYTHON;
        } else if (hasGoMod) {
            projectType = ProjectType.GO;
        } else if (hasCargo) {
            projectType = ProjectType.RUST;
        } else {
            projectType = ProjectType.UNKNOWN;
        }
    }

    /**
     * 获取项目类型
     */
    public ProjectType getProjectType() {
        return projectType;
    }

    /**
     * 获取检测到的所有工具
     */
    public Set<String> getDetectedTools() {
        return new HashSet<>(detectedTools);
    }

    /**
     * 智能转换通用命令到具体项目命令
     * 例如："运行测试" -> "mvn test" (Maven) 或 "npm test" (Node.js)
     */
    public String smartTranslate(String genericCommand) {
        switch (genericCommand.toLowerCase()) {
            case "build":
            case "构建":
                return getBuildCommand();
            case "test":
            case "测试":
                return getTestCommand();
            case "clean":
            case "清理":
                return getCleanCommand();
            case "install":
            case "安装依赖":
                return getInstallCommand();
            case "run":
            case "运行":
                return getRunCommand();
            default:
                return null;
        }
    }

    private String getBuildCommand() {
        switch (projectType) {
            case MAVEN:
                return "mvn package";
            case GRADLE:
                return "gradle build";
            case NPM:
                return "npm run build";
            case GO:
                return "go build";
            case RUST:
                return "cargo build";
            default:
                return null;
        }
    }

    private String getTestCommand() {
        switch (projectType) {
            case MAVEN:
                return "mvn test";
            case GRADLE:
                return "gradle test";
            case NPM:
                return "npm test";
            case PYTHON:
                return "pytest";
            case GO:
                return "go test ./...";
            case RUST:
                return "cargo test";
            default:
                return null;
        }
    }

    private String getCleanCommand() {
        switch (projectType) {
            case MAVEN:
                return "mvn clean";
            case GRADLE:
                return "gradle clean";
            case NPM:
                return "npm run clean";
            case GO:
                return "go clean";
            case RUST:
                return "cargo clean";
            default:
                return null;
        }
    }

    private String getInstallCommand() {
        switch (projectType) {
            case MAVEN:
                return "mvn install";
            case GRADLE:
                return "gradle install";
            case NPM:
                return "npm install";
            case PYTHON:
                return "pip install -r requirements.txt";
            case GO:
                return "go mod download";
            case RUST:
                return "cargo fetch";
            default:
                return null;
        }
    }

    private String getRunCommand() {
        switch (projectType) {
            case MAVEN:
                return "mvn spring-boot:run";
            case GRADLE:
                return "gradle run";
            case NPM:
                return "npm start";
            case PYTHON:
                return "python main.py";
            case GO:
                return "go run main.go";
            case RUST:
                return "cargo run";
            default:
                return null;
        }
    }

    /**
     * 获取项目信息摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("📁 项目类型: ").append(projectType.getDisplayName()).append("\n");
        sb.append("📂 工作目录: ").append(workingDirectory).append("\n");

        if (!detectedTools.isEmpty()) {
            sb.append("🔧 检测到的工具: ").append(String.join(", ", detectedTools)).append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取推荐命令
     */
    public List<String> getRecommendedCommands() {
        List<String> commands = new ArrayList<>();

        switch (projectType) {
            case MAVEN:
                commands.add("mvn clean package - 清理并打包");
                commands.add("mvn test - 运行测试");
                commands.add("mvn dependency:tree - 查看依赖树");
                break;
            case GRADLE:
                commands.add("gradle build - 构建项目");
                commands.add("gradle test - 运行测试");
                commands.add("gradle tasks - 查看所有任务");
                break;
            case NPM:
                commands.add("npm install - 安装依赖");
                commands.add("npm start - 启动项目");
                commands.add("npm test - 运行测试");
                break;
            case PYTHON:
                commands.add("pip install -r requirements.txt - 安装依赖");
                commands.add("pytest - 运行测试");
                commands.add("python main.py - 运行主程序");
                break;
            case GO:
                commands.add("go build - 构建项目");
                commands.add("go test ./... - 运行所有测试");
                commands.add("go run main.go - 运行程序");
                break;
            case RUST:
                commands.add("cargo build - 构建项目");
                commands.add("cargo test - 运行测试");
                commands.add("cargo run - 运行程序");
                break;
        }

        return commands;
    }
}

