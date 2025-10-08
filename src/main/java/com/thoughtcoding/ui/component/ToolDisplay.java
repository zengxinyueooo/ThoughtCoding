// src/main/java/com/thoughtcoding/ui/component/ToolDisplay.java
package com.thoughtcoding.ui.component;

import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.ui.AnsiColors;
import org.jline.terminal.Terminal;

public class ToolDisplay {
    private final Terminal terminal;

    public ToolDisplay(Terminal terminal) {
        this.terminal = terminal;
    }

    public void displayToolCall(ToolCall toolCall) {
        String statusIcon = toolCall.isSuccess() ? "✅" : "❌";
        String statusColor = toolCall.isSuccess() ? AnsiColors.BRIGHT_GREEN : AnsiColors.BRIGHT_RED;
        String statusText = toolCall.isSuccess() ? "SUCCESS" : "FAILED";

        String header = String.format("%s%s Tool Call: %s%s %s(%s - %dms)%s",
                AnsiColors.BRIGHT_MAGENTA, "🛠️",
                AnsiColors.BRIGHT_WHITE, toolCall.getToolName(),
                statusColor, statusText, toolCall.getExecutionTime(), AnsiColors.RESET);

        terminal.writer().println(header);

        // 显示参数
        if (toolCall.getParameters() != null && !toolCall.getParameters().isEmpty()) {
            terminal.writer().println(AnsiColors.BRIGHT_BLACK + "  Parameters: " + toolCall.getParameters() + AnsiColors.RESET);
        }

        // 显示结果
        if (toolCall.getResult() != null) {
            String resultColor = toolCall.isSuccess() ? AnsiColors.BRIGHT_GREEN : AnsiColors.BRIGHT_RED;
            terminal.writer().println(resultColor + "  Result: " + toolCall.getResult() + AnsiColors.RESET);
        }

        terminal.writer().println();
        terminal.writer().flush();
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