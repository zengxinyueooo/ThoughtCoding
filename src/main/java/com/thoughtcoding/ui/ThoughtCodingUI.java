// src/main/java/com/thoughtcoding/ui/ThoughtCodingUI.java
package com.thoughtcoding.ui;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.service.PerformanceMonitor;

import com.thoughtcoding.ui.component.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.StringsCompleter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ThoughtCodingUI {
    private final Terminal terminal;
    private final LineReader lineReader;
    private final ChatRenderer chatRenderer;
    private final ToolDisplay toolDisplay;
    private final StatusBar statusBar;
    private final ProgressIndicator progressIndicator;
    private final InputHandler inputHandler;

    public Terminal getTerminal() {
        return terminal;
    }
    public ThoughtCodingUI() {
        try {
            // 初始化JLine终端
            this.terminal = TerminalBuilder.builder()
                    .name("ThoughtCoding")
                    .system(true)
                    .build();

            // 初始化行阅读器
            this.lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new StringsCompleter("exit", "quit", "clear", "help", "new", "save", "list"))
                    .build();

            // 初始化UI组件
            this.chatRenderer = new ChatRenderer(terminal);
            this.toolDisplay = new ToolDisplay(terminal);
            this.statusBar = new StatusBar(terminal);
            this.progressIndicator = new ProgressIndicator(terminal);
            this.inputHandler = new InputHandler(
                    terminal,
                    new StringsCompleter("exit", "quit", "clear", "help", "new", "save", "list")
            );

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize terminal", e);
        }
    }

    public void showBanner() {
        try {
            // 显示ASCII艺术横幅
            terminal.writer().println(AnsiColors.GREEN + """
                ░██████████░██                                         ░██           ░██      ░██████                    ░██ ░██                     \s
                    ░██    ░██                                         ░██           ░██     ░██   ░██                   ░██                         \s
                    ░██    ░████████   ░███████  ░██    ░██  ░████████ ░████████  ░████████ ░██         ░███████   ░████████ ░██░████████   ░████████\s
                    ░██    ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██    ░██    ░██        ░██    ░██ ░██    ░██ ░██░██    ░██ ░██    ░██\s
                    ░██    ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██    ░██    ░██        ░██    ░██ ░██    ░██ ░██░██    ░██ ░██    ░██\s
                    ░██    ░██    ░██ ░██    ░██ ░██   ░███ ░██   ░███ ░██    ░██    ░██     ░██   ░██ ░██    ░██ ░██   ░███ ░██░██    ░██ ░██   ░███\s
                    ░██    ░██    ░██  ░███████   ░█████░██  ░█████░██ ░██    ░██     ░████   ░██████   ░███████   ░█████░██ ░██░██    ░██  ░█████░██\s
                                                               ░██                                                                            ░██\s
                                                         ░███████                                                                       ░███████ \s
                                                                                                                                                 \s        """ + AnsiColors.RESET);

            // 要居中的三行文字
            String[] titleLines = {
                    "Interactive Code Assistant CLI",
                    "- Java Edition -",
                    "Version 1.0.0"
            };

            // 计算ASCII艺术的宽度（取第一行的长度，因为通常最宽）
            int asciiWidth = 122; // 根据你的ASCII艺术，第一行大约是122个字符宽度

            // 居中显示每行文字
            for (String line : titleLines) {
                int padding = (asciiWidth - line.length()) / 2;
                String spaces = " ".repeat(Math.max(0, padding));
                terminal.writer().println(AnsiColors.CYAN + spaces + line + AnsiColors.RESET);
            }

            terminal.writer().println();

        } catch (Exception e) {
            // 如果彩色输出失败，使用普通输出
            System.out.println("""
                ░██████████░██                                         ░██           ░██      ░██████                    ░██ ░██                     \s
                    ░██    ░██                                         ░██           ░██     ░██   ░██                   ░██                         \s
                    ░██    ░████████   ░███████  ░██    ░██  ░████████ ░████████  ░████████ ░██         ░███████   ░████████ ░██░████████   ░████████\s
                    ░██    ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██    ░██    ░██        ░██    ░██ ░██    ░██ ░██░██    ░██ ░██    ░██\s
                    ░██    ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██ ░██    ░██    ░██    ░██        ░██    ░██ ░██    ░██ ░██░██    ░██ ░██    ░██\s
                    ░██    ░██    ░██ ░██    ░██ ░██   ░███ ░██   ░███ ░██    ░██    ░██     ░██   ░██ ░██    ░██ ░██   ░███ ░██░██    ░██ ░██   ░███\s
                    ░██    ░██    ░██  ░███████   ░█████░██  ░█████░██ ░██    ░██     ░████   ░██████   ░███████   ░█████░██ ░██░██    ░██  ░█████░██\s
                                                               ░██                                                                            ░██\s
                                                         ░███████                                                                       ░███████ \s
                                                                                                                                                 \s                           """);

            // 普通输出的居中显示
            String[] titleLines = {
                    "Interactive Code Assistant CLI",
                    "- Java Edition -",
                    "Version 1.0.0"
            };

            int asciiWidth = 122;
            for (String line : titleLines) {
                int padding = (asciiWidth - line.length()) / 2;
                String spaces = " ".repeat(Math.max(0, padding));
                System.out.println(spaces + line);
            }
            System.out.println();
        }
    }

    public void displayUserMessageWithBox(ChatMessage message) {
        String content = message.getContent();
        String timestamp = formatTimestamp(message.getTimestamp());

        // 用户消息框（右侧，绿色）
        displayMessageBox(content, timestamp, true);
    }

    public void displayAssistantMessageWithBox(ChatMessage message) {
        String content = message.getContent();
        String timestamp = formatTimestamp(message.getTimestamp());

        // AI消息框（左侧，蓝色）
        displayMessageBox(content, timestamp, false);
    }

    private void displayMessageBox(String content, String timestamp, boolean isUser) {
        String color = isUser ? AnsiColors.GREEN : AnsiColors.BLUE;
        String prefix = isUser ? "You: " : "AI: ";

        terminal.writer().println();
        terminal.writer().println(color + prefix + content + AnsiColors.RESET);
        terminal.writer().println();
    }

    private List<String> splitMessage(String message, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = message.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > maxWidth) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }

    private String padLine(String line, int width, String alignment) {
        if (line.length() >= width) {
            return line;
        }

        int padding = width - line.length();
        switch (alignment) {
            case "right":
                return " ".repeat(padding) + line;
            case "center":
                int leftPadding = padding / 2;
                int rightPadding = padding - leftPadding;
                return " ".repeat(leftPadding) + line + " ".repeat(rightPadding);
            case "left":
            default:
                return line + " ".repeat(padding);
        }
    }

    private String formatTimestamp(String timestamp) {
        try {
            // 简化时间戳显示
            if (timestamp == null) {
                return "Just now";
            }

            // 如果是完整的时间戳，提取时间部分
            if (timestamp.contains("T")) {
                return timestamp.substring(11, 16); // 提取 HH:mm
            }

            return timestamp;
        } catch (Exception e) {
            return "Now";
        }
    }

    public void displayUserMessage(ChatMessage message) {
        chatRenderer.renderUserMessage(message);
    }

    public void displayAIMessage(ChatMessage message) {
        if (message.isAssistantMessage()) {
            terminal.writer().print(message.getContent());
            terminal.writer().flush();
        }
    }

    public void displayToolCall(ToolCall toolCall) {
        toolDisplay.displayToolCall(toolCall);
    }

    public void displayInfo(String info) {
        statusBar.showInfo(info);
    }

    public void displayError(String error) {
        statusBar.showError(error);
    }

    public void displaySuccess(String message) {
        statusBar.showSuccess(message);
    }

    public void displayWarning(String warning) {
        statusBar.showWarning(warning);
    }

    public void displaySessionList(java.util.List<String> sessions) {
        chatRenderer.renderSessionList(sessions);
    }

    public void displayPerformanceInfo(PerformanceMonitor.PerformanceData data) {
        statusBar.showPerformanceInfo(data);
    }

    public void showProgress(String message) {
        progressIndicator.show(message);
    }

    public void updateProgress(String message) {
        progressIndicator.update(message);
    }

    public void hideProgress() {
        progressIndicator.hide();
    }

    public String readInput(String prompt) {
        return inputHandler.readInput(prompt);
    }

    public void clearScreen() {
        try {
            // 使用标准的 ANSI 转义序列清屏
            terminal.writer().print("\u001b[H\u001b[2J");
            terminal.writer().flush();
        } catch (Exception e) {
            // 如果清屏失败，输出换行
            for (int i = 0; i < 50; i++) {
                terminal.writer().println();
            }
            terminal.writer().flush();
        }
    }

    public void close() {
        try {
            if (terminal != null) {
                terminal.close();
            }
        } catch (Exception e) {
            // 忽略关闭错误
        }
    }


}