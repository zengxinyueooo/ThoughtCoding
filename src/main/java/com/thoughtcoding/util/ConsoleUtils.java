package com.thoughtcoding.util;

import com.thoughtcoding.ui.AnsiColors;

import java.io.Console;
import java.util.Scanner;

/**
 * 控制台工具类
 */
public class ConsoleUtils {

    private ConsoleUtils() {
        // 工具类，防止实例化
    }

    /**
     * 读取用户输入
     */
    public static String readLine(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readLine(prompt);
        } else {
            // 在IDE中运行时使用Scanner
            System.out.print(prompt);
            Scanner scanner = new Scanner(System.in);
            return scanner.nextLine();
        }
    }

    /**
     * 读取密码（不显示）
     */
    public static String readPassword(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword(prompt);
            return new String(password);
        } else {
            // 在IDE中无法隐藏密码
            System.out.print(prompt);
            Scanner scanner = new Scanner(System.in);
            return scanner.nextLine();
        }
    }

    /**
     * 打印带颜色的信息
     */
    public static void printInfo(String message) {
        System.out.println(AnsiColors.info(message));
    }

    public static void printSuccess(String message) {
        System.out.println(AnsiColors.success(message));
    }

    public static void printWarning(String message) {
        System.out.println(AnsiColors.warning(message));
    }

    public static void printError(String message) {
        System.out.println(AnsiColors.error(message));
    }

    /**
     * 打印分隔线
     */
    public static void printSeparator() {
        printSeparator(60);
    }

    public static void printSeparator(int length) {
        System.out.println(AnsiColors.BRIGHT_BLACK + "─".repeat(length) + AnsiColors.RESET);
    }

    /**
     * 打印标题
     */
    public static void printTitle(String title) {
        printSeparator(title.length() + 4);
        System.out.println(AnsiColors.BRIGHT_CYAN + "  " + title + "  " + AnsiColors.RESET);
        printSeparator(title.length() + 4);
    }

    /**
     * 清屏
     */
    public static void clearScreen() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            // 如果清屏失败，输出一些空行
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
    }

    /**
     * 确认对话框
     */
    public static boolean confirm(String question) {
        String answer = readLine(question + " (y/N): ");
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }

    /**
     * 选择菜单
     */
    public static int showMenu(String title, String... options) {
        System.out.println();
        printTitle(title);

        for (int i = 0; i < options.length; i++) {
            System.out.println(AnsiColors.BRIGHT_WHITE + (i + 1) + ". " + AnsiColors.RESET + options[i]);
        }

        while (true) {
            String input = readLine("\nEnter your choice (1-" + options.length + "): ");
            try {
                int choice = Integer.parseInt(input);
                if (choice >= 1 && choice <= options.length) {
                    return choice;
                }
            } catch (NumberFormatException e) {
                // 继续循环
            }
            System.out.println(AnsiColors.error("Invalid choice. Please try again."));
        }
    }

    /**
     * 显示进度条
     */
    public static void showProgressBar(int current, int total, int width) {
        double percent = (double) current / total;
        int progress = (int) (percent * width);

        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < progress) {
                bar.append("=");
            } else if (i == progress) {
                bar.append(">");
            } else {
                bar.append(" ");
            }
        }
        bar.append("] ");
        bar.append(String.format("%.1f%%", percent * 100));

        System.out.print("\r" + AnsiColors.BRIGHT_GREEN + bar + AnsiColors.RESET);

        if (current == total) {
            System.out.println(); // 完成后换行
        }
    }

    /**
     * 获取终端宽度
     */
    public static int getTerminalWidth() {
        try {
            String columns = System.getenv("COLUMNS");
            if (columns != null) {
                return Integer.parseInt(columns);
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return 80; // 默认宽度
    }
}