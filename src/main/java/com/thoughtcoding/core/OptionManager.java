package com.thoughtcoding.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 选项管理器
 * 负责识别 AI 提供的选项，并处理用户的选择
 */
public class OptionManager {

    /**
     * 选项定义
     */
    public static class Option {
        private final int number;
        private final String description;
        private final String action;

        public Option(int number, String description, String action) {
            this.number = number;
            this.description = description;
            this.action = action;
        }

        public int getNumber() {
            return number;
        }

        public String getDescription() {
            return description;
        }

        public String getAction() {
            return action;
        }
    }

    /**
     * 当前提供的选项列表
     */
    private List<Option> currentOptions = new ArrayList<>();

    /**
     * 是否处于选项等待状态
     */
    private boolean waitingForOption = false;

    /**
     * 最后一次提供选项时的上下文信息（文件名等）
     */
    private String contextInfo = "";

    /**
     * 从 AI 响应中提取选项
     *
     * 识别格式：
     * 1. xxx
     * 2. xxx
     * 3. xxx
     * 4. xxx
     */
    public boolean extractOptionsFromResponse(String response) {
        currentOptions.clear();

        // 正则匹配选项格式
        Pattern pattern = Pattern.compile("^\\s*(\\d+)\\.\\s*(.+?)(?:\\((.+?)\\))?\\s*$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(response);

        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            String description = matcher.group(2).trim();
            String action = matcher.group(3) != null ? matcher.group(3).trim() : inferAction(description);

            currentOptions.add(new Option(number, description, action));
        }

        // 如果找到了选项，进入等待状态
        if (!currentOptions.isEmpty()) {
            waitingForOption = true;

            // 尝试提取上下文信息（文件名）
            extractContextInfo(response);

            return true;
        }

        return false;
    }

    /**
     * 从描述中推断操作类型
     */
    private String inferAction(String description) {
        String lowerDesc = description.toLowerCase();

        if (lowerDesc.contains("覆盖") || lowerDesc.contains("重新创建")) {
            return "overwrite";
        } else if (lowerDesc.contains("修改") || lowerDesc.contains("编辑")) {
            return "edit";
        } else if (lowerDesc.contains("不同名字") || lowerDesc.contains("新建")) {
            return "create_new";
        } else if (lowerDesc.contains("运行") || lowerDesc.contains("执行")) {
            return "run";
        } else if (lowerDesc.contains("查看") || lowerDesc.contains("显示")) {
            return "view";
        }

        return "unknown";
    }

    /**
     * 提取上下文信息（文件名等）
     */
    private void extractContextInfo(String response) {
        // 尝试匹配文件名
        Pattern filePattern = Pattern.compile("(\\w+\\.\\w+)");
        Matcher matcher = filePattern.matcher(response);

        if (matcher.find()) {
            contextInfo = matcher.group(1);
        }
    }

    /**
     * 检查用户输入是否是选项编号
     */
    public boolean isOptionInput(String input) {
        if (!waitingForOption) {
            return false;
        }

        String trimmed = input.trim();

        // 检查是否是纯数字
        if (trimmed.matches("^\\d+$")) {
            int choice = Integer.parseInt(trimmed);
            return choice >= 1 && choice <= currentOptions.size();
        }

        // 检查是否是"选择 X"格式
        if (trimmed.matches("^选择\\s*\\d+$")) {
            return true;
        }

        return false;
    }

    /**
     * 处理用户的选项选择，返回自动生成的命令
     */
    public String processOptionSelection(String input) {
        String trimmed = input.trim();
        int choice;

        // 提取选项编号
        if (trimmed.matches("^\\d+$")) {
            choice = Integer.parseInt(trimmed);
        } else if (trimmed.matches("^选择\\s*\\d+$")) {
            choice = Integer.parseInt(trimmed.replaceAll("选择\\s*", ""));
        } else {
            return null;
        }

        // 验证选项编号
        if (choice < 1 || choice > currentOptions.size()) {
            return null;
        }

        // 获取选中的选项
        Option selectedOption = currentOptions.get(choice - 1);

        // 清除等待状态
        waitingForOption = false;

        // 根据操作类型生成命令
        return generateCommandForOption(selectedOption);
    }

    /**
     * 根据选项生成对应的命令
     */
    private String generateCommandForOption(Option option) {
        String action = option.getAction();

        return switch (action) {
            case "overwrite" -> "覆盖 " + contextInfo + "，创建新的内容";
            case "edit" -> "修改现有的 " + contextInfo;
            case "create_new" -> "创建一个新的文件，名字不同于 " + contextInfo;
            case "run" -> "运行 " + contextInfo;
            case "view" -> "查看 " + contextInfo + " 的内容";
            default -> "执行选项 " + option.getNumber() + ": " + option.getDescription();
        };
    }

    /**
     * 获取当前选项列表
     */
    public List<Option> getCurrentOptions() {
        return new ArrayList<>(currentOptions);
    }

    /**
     * 是否正在等待用户选择
     */
    public boolean isWaitingForOption() {
        return waitingForOption;
    }

    /**
     * 重置状态
     */
    public void reset() {
        currentOptions.clear();
        waitingForOption = false;
        contextInfo = "";
    }

    /**
     * 获取上下文信息
     */
    public String getContextInfo() {
        return contextInfo;
    }
}

