package com.thoughtcoding.ui.component;

import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.ui.AnsiColors;
import org.jline.terminal.Terminal;

/**
 * 工具调用显示组件，负责在终端中美观地展示工具调用的状态和结果
 */
public class ToolDisplay {
    private final Terminal terminal;

    public ToolDisplay(Terminal terminal) {
        this.terminal = terminal;
    }

    public void displayToolCall(ToolCall toolCall) {
        // 🔥 不再显示调试信息，保持界面简洁
        // 工具调用的详细信息已经在确认界面显示过了
    }

    public void displayToolStart(String toolName) {
        String message = String.format("%s🛠️  Starting tool: %s%s%s",
                AnsiColors.BRIGHT_MAGENTA, AnsiColors.BRIGHT_WHITE, toolName, AnsiColors.RESET);

        terminal.writer().println(message);
        terminal.writer().flush();
    }

    public void displayToolResult(String toolName, String result, boolean success, long executionTime) {
        String statusIcon = success ? "✅" : "❌";
        String statusColor = success ? AnsiColors.BRIGHT_GREEN : AnsiColors.BRIGHT_RED;

        String message = String.format("%s%s %s: %s (%dms)%s",
                statusColor, statusIcon, toolName, result, executionTime, AnsiColors.RESET);

        terminal.writer().println(message);
        terminal.writer().flush();
    }
}