package com.thoughtcoding.core;

import com.thoughtcoding.model.ToolExecution;
import com.thoughtcoding.ui.ThoughtCodingUI;
import org.jline.reader.LineReader;

/**
 * 工具执行确认组件
 * 实现类似 Claude Code 的交互式确认功能
 */
public class ToolExecutionConfirmation {
    private final ThoughtCodingUI ui;
    private final LineReader lineReader;
    private boolean autoApproveMode = false;

    public ToolExecutionConfirmation(ThoughtCodingUI ui, LineReader lineReader) {
        this.ui = ui;
        this.lineReader = lineReader;
    }

    /**
     * 询问用户是否执行工具调用
     */
    public boolean askConfirmation(ToolExecution execution) {
        if (autoApproveMode) {
            ui.displayInfo("🤖 [自动批准模式] 执行: " + execution.toolName());
            return true;
        }

        displayToolCallDetails(execution);

        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                String prompt = "\n执行此操作？ [yes/no/auto/skip]: ";
                String response = lineReader.readLine(prompt);

                if (response == null) {
                    retryCount++;
                    if (retryCount < maxRetries) {
                        ui.displayWarning("⚠️  输入读取失败，正在重试... (" + retryCount + "/" + maxRetries + ")");
                        Thread.sleep(100); // 短暂延迟后重试
                        continue;
                    } else {
                        ui.displayError("❌ 输入读取失败次数过多，操作已取消");
                        return false;
                    }
                }

                String trimmedResponse = response.toLowerCase().trim();

                switch (trimmedResponse) {
                    case "y":
                    case "yes":
                        return true;
                    case "n":
                    case "no":
                        ui.displayWarning("⏭️  用户拒绝执行");
                        return false;
                    case "auto":
                        ui.displayInfo("🤖 已启用自动批准模式");
                        autoApproveMode = true;
                        return true;
                    case "skip":
                    case "s":
                        ui.displayWarning("⏭️  已跳过此操作");
                        return false;
                    default:
                        ui.displayError("❌ 无效输入，请输入: yes/no/auto/skip");
                        // 不增加重试计数，让用户重新输入
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ui.displayError("❌ 操作被中断");
                return false;
            } catch (Exception e) {
                retryCount++;
                if (retryCount < maxRetries) {
                    ui.displayWarning("⚠️  读取输入异常，正在重试... (" + retryCount + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    ui.displayError("❌ 读取输入失败: " + e.getMessage());
                    return false;
                }
            }
        }

        return false;
    }

    private void displayToolCallDetails(ToolExecution execution) {
        ui.getTerminal().writer().println();
        ui.getTerminal().writer().println("═".repeat(70));

        // 根据工具类型显示不同的标题
        String action = getActionDescription(execution.toolName());
        ui.getTerminal().writer().println("📝 " + action);
        ui.getTerminal().writer().println("═".repeat(70));

        // 🔥 简化显示：只显示文件路径，代码内容不显示（太长）
        if (execution.parameters() != null && !execution.parameters().isEmpty()) {
            execution.parameters().forEach((key, value) -> {
                String displayKey = translateParameterKey(key);

                // 如果是代码内容，只显示摘要
                if ("content".equals(key) && value instanceof String) {
                    String content = (String) value;
                    int lines = content.split("\n").length;
                    ui.getTerminal().writer().println(displayKey + ": " + lines + " 行代码");
                } else {
                    ui.getTerminal().writer().println(displayKey + ": " + value);
                }
            });
        }

        ui.getTerminal().writer().println("═".repeat(70));
        ui.getTerminal().writer().flush();
    }

    /**
     * 根据工具名称返回友好的操作描述
     */
    private String getActionDescription(String toolName) {
        return switch (toolName) {
            case "write_file" -> "创建文件";
            case "read_file" -> "读取文件";
            case "list_directory" -> "列出目录";
            case "edit_file" -> "编辑文件";
            default -> "执行操作: " + toolName;
        };
    }

    /**
     * 将参数键名翻译为中文
     */
    private String translateParameterKey(String key) {
        return switch (key) {
            case "path" -> "📂 文件路径";
            case "content" -> "📄 文件内容";
            case "directory" -> "📁 目录";
            default -> key;
        };
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "(null)";
        }

        if (value instanceof String) {
            String str = (String) value;

            if (str.contains("\n")) {
                // 🔥 显示完整内容，不再省略
                String[] lines = str.split("\n");
                StringBuilder full = new StringBuilder("\n");
                for (String line : lines) {
                    full.append("      ").append(line).append("\n");
                }
                return full.toString();
            }

            // 单行文本也不截断，显示完整内容
            return str;
        }

        return value.toString();
    }

    public void setAutoApproveMode(boolean enabled) {
        this.autoApproveMode = enabled;
        if (enabled) {
            ui.displayInfo("🤖 自动批准模式已启用");
        } else {
            ui.displayInfo("👤 交互式确认模式已启用");
        }
    }

    public boolean isAutoApproveMode() {
        return autoApproveMode;
    }

    /**
     * 🔥 简化的确认方法，用于流式触发的工具调用
     * 这种情况下，确认框已经在流式输出中显示了，只需要询问用户是否执行
     */
    public boolean askSimpleConfirmation() {
        if (autoApproveMode) {
            ui.displayInfo("🤖 [自动批准模式] 执行");
            return true;
        }

        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                String prompt = "\n执行此操作？ [yes/no/auto/skip]: ";
                String response = lineReader.readLine(prompt);

                if (response == null) {
                    retryCount++;
                    if (retryCount < maxRetries) {
                        ui.displayWarning("⚠️  输入读取失败，正在重试... (" + retryCount + "/" + maxRetries + ")");
                        Thread.sleep(100);
                        continue;
                    } else {
                        ui.displayError("❌ 输入读取失败次数过多，操作已取消");
                        return false;
                    }
                }

                String trimmedResponse = response.toLowerCase().trim();

                switch (trimmedResponse) {
                    case "y":
                    case "yes":
                        return true;
                    case "n":
                    case "no":
                        ui.displayWarning("⏭️  用户拒绝执行");
                        return false;
                    case "auto":
                        ui.displayInfo("🤖 已启用自动批准模式");
                        autoApproveMode = true;
                        return true;
                    case "skip":
                    case "s":
                        ui.displayWarning("⏭️  已跳过此操作");
                        return false;
                    default:
                        ui.displayError("❌ 无效输入，请输入: yes/no/auto/skip");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ui.displayError("❌ 操作被中断");
                return false;
            } catch (Exception e) {
                retryCount++;
                if (retryCount < maxRetries) {
                    ui.displayWarning("⚠️  读取输入异常，正在重试... (" + retryCount + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    ui.displayError("❌ 读取输入失败: " + e.getMessage());
                    return false;
                }
            }
        }

        return false;
    }
}

