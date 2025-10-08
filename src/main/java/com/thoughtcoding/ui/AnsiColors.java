// src/main/java/com/thoughtcoding/ui/AnsiColors.java
package com.thoughtcoding.ui;

public class AnsiColors {
    // 重置所有属性
    public static final String RESET = "\u001B[0m";

    // 常规颜色
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String PURPLE = "\u001B[35m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    // 亮色
    public static final String BRIGHT_BLACK = "\u001B[90m";
    public static final String BRIGHT_RED = "\u001B[91m";
    public static final String BRIGHT_GREEN = "\u001B[92m";
    public static final String BRIGHT_YELLOW = "\u001B[93m";
    public static final String BRIGHT_BLUE = "\u001B[94m";
    public static final String BRIGHT_MAGENTA = "\u001B[95m";
    public static final String BRIGHT_CYAN = "\u001B[96m";
    public static final String BRIGHT_WHITE = "\u001B[97m";

    // 背景颜色
    public static final String BG_BLACK = "\u001B[40m";
    public static final String BG_RED = "\u001B[41m";
    public static final String BG_GREEN = "\u001B[42m";
    public static final String BG_YELLOW = "\u001B[43m";
    public static final String BG_BLUE = "\u001B[44m";
    public static final String BG_MAGENTA = "\u001B[45m";
    public static final String BG_CYAN = "\u001B[46m";
    public static final String BG_WHITE = "\u001B[47m";

    // 文本样式
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String BLINK = "\u001B[5m";
    public static final String REVERSE = "\u001B[7m";
    public static final String HIDDEN = "\u001B[8m";


    // 便捷方法
    public static String colorize(String text, String color) {
        return color + text + RESET;
    }

    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    public static String error(String text) {
        return BRIGHT_RED + text + RESET;
    }

    public static String success(String text) {
        return BRIGHT_GREEN + text + RESET;
    }

    public static String warning(String text) {
        return BRIGHT_YELLOW + text + RESET;
    }

    public static String info(String text) {
        return BRIGHT_CYAN + text + RESET;
    }

    public static String highlight(String text) {
        return BRIGHT_MAGENTA + text + RESET;
    }

    // 检测终端是否支持颜色
    public static boolean isColorSupported() {
        String term = System.getenv("TERM");
        return term != null && !term.equals("dumb") && System.console() != null;
    }

    // 如果终端不支持颜色，返回空字符串
    public static String safeColor(String colorCode) {
        return isColorSupported() ? colorCode : "";
    }

    public static String safeReset() {
        return isColorSupported() ? RESET : "";
    }
}