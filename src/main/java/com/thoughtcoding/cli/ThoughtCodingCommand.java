// src/main/java/com/thoughtcoding/cli/ThoughtCodingCommand.java
package com.thoughtcoding.cli;


import com.thoughtcoding.core.AgentLoop;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.service.SessionService;
import com.thoughtcoding.ui.ThoughtCodingUI;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "thought", mixinStandardHelpOptions = true,
        description = "ThoughtCoding CLI - Interactive Code Assistant")
public class ThoughtCodingCommand implements Callable<Integer> {

    private final ThoughtCodingContext context;

    // 添加会话管理字段
    private AgentLoop currentAgentLoop;
    private String currentSessionId;

    @Option(names = {"-i", "--interactive"}, description = "Run in interactive mode")
    private boolean interactive = true;

    @Option(names = {"-c", "--continue"}, description = "Continue last session")
    private boolean continueSession;

    @Option(names = {"-S", "--session"}, description = "Specify session ID")
    private String sessionId;

    @Option(names = {"-p", "--prompt"}, description = "Single prompt mode")
    private String prompt;

    @Option(names = {"-m", "--model"}, description = "Specify model to use")
    private String model;

    @Option(names = {"--list-sessions"}, description = "List all sessions")
    private boolean listSessions;

    @Option(names = {"--delete-session"}, description = "Delete specified session")
    private String deleteSessionId;

    public ThoughtCodingCommand(ThoughtCodingContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        try {
            ThoughtCodingUI ui = context.getUi();
            SessionService sessionService = context.getSessionService();

            // 显示欢迎信息
            ui.showBanner();

            // 确定使用哪个模型
            String modelToUse = model != null ? model : context.getAppConfig().getDefaultModel();

            // 处理列表会话
            if (listSessions) {
                List<String> sessions = sessionService.listSessions();
                ui.displaySessionList(sessions);
                return 0;
            }

            // 处理删除会话
            if (deleteSessionId != null) {
                boolean deleted = sessionService.deleteSession(deleteSessionId);
                if (deleted) {
                    ui.displayInfo("Session deleted: " + deleteSessionId);
                } else {
                    ui.displayError("Failed to delete session: " + deleteSessionId);
                }
                return 0;
            }

            // 加载会话历史
            List<ChatMessage> history = new ArrayList<>();

            if (continueSession) {
                currentSessionId = sessionService.getLatestSessionId();
                if (currentSessionId != null) {
                    history = sessionService.loadSession(currentSessionId);
                    ui.displayInfo("Continuing from previous session: " + currentSessionId);
                } else {
                    ui.displayInfo("No previous session found. Starting new session.");
                    currentSessionId = UUID.randomUUID().toString();
                }
            } else if (sessionId != null) {
                currentSessionId = sessionId;
                history = sessionService.loadSession(sessionId);
                ui.displayInfo("Loaded session: " + sessionId);
            } else {
                // 创建新会话
                currentSessionId = UUID.randomUUID().toString();
                ui.displayInfo("Created new session: " + currentSessionId);
            }

            // 创建Agent循环
            currentAgentLoop = new AgentLoop(context, currentSessionId, modelToUse);
            currentAgentLoop.loadHistory(history);

            // 单次对话模式
            if (prompt != null) {
                return handleSinglePrompt(currentAgentLoop, prompt);
            }

            // 交互式模式
            if (interactive) {
                return startInteractiveMode(currentAgentLoop, ui);
            }

            return 0;

        } catch (Exception e) {
            context.getUi().displayError("Error: " + e.getMessage());
            return 1;
        }
    }

    private Integer startInteractiveMode(AgentLoop agentLoop, ThoughtCodingUI ui) {
        ui.displayInfo("Entering interactive mode. Type 'exit' to quit, 'help' for commands.");

        while (true) {
            try {
                String input = ui.readInput("thought> ");

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                String trimmedInput = input.trim();

                // 退出命令
                if (trimmedInput.equalsIgnoreCase("exit") || trimmedInput.equalsIgnoreCase("quit")) {
                    ui.displayInfo("Goodbye!");
                    break;
                }

                // 帮助命令
                if (trimmedInput.equalsIgnoreCase("help")) {
                    showHelp();
                    continue;
                }

                // 清屏命令
                if (trimmedInput.equalsIgnoreCase("clear")) {
                    ui.clearScreen();
                    continue;
                }

                // 处理内部命令（以 / 开头）
                if (trimmedInput.startsWith("/")) {
                    handleInternalCommand(trimmedInput, agentLoop);
                    continue;
                }

                // 🚨 关键修复：检查是否是参数格式
                if (isParameterFormat(trimmedInput)) {
                    handleParameterInInteractiveMode(trimmedInput, agentLoop);
                    continue;
                }

                // 处理普通对话
                agentLoop.processInput(trimmedInput);

            } catch (Exception e) {
                ui.displayError("Error: " + e.getMessage());
            }
        }

        return 0;
    }

    /**
     * 检查输入是否是参数格式（以 - 或 -- 开头）
     */
    private boolean isParameterFormat(String input) {
        return input.startsWith("-") && input.length() > 1;
    }

    /**
     * 在交互模式中处理参数命令
     */
    private void handleParameterInInteractiveMode(String parameter, AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        String[] args = parameter.split("\\s+");

        switch (args[0]) {
            case "-i":
            case "--interactive":
                ui.displayInfo("Already in interactive mode");
                break;

            case "-c":
            case "--continue":
                handleContinueInInteractive(currentAgentLoop);
                break;

            case "-S":
            case "--session":
                if (args.length > 1) {
                    handleLoadSession(args[1], currentAgentLoop);
                } else {
                    ui.displayError("Usage: -S <session-id>");
                }
                break;

            case "-p":
            case "--prompt":
                if (args.length > 1) {
                    // 重新组合提示内容（处理带空格的提示）
                    String prompt = parameter.substring(args[0].length()).trim();

                    // 🚨 关键修复：去掉引号
                    if (prompt.startsWith("\"") && prompt.endsWith("\"")) {
                        prompt = prompt.substring(1, prompt.length() - 1);
                    }

                    if (!prompt.isEmpty()) {
                        handleSinglePromptInInteractive(prompt, currentAgentLoop);
                    } else {
                        ui.displayError("Usage: -p \"your prompt\"");
                    }
                } else {
                    ui.displayError("Usage: -p \"your prompt\"");
                }
                break;
        }
    }

    /**
     * 在交互模式中继续上次会话
     */
    private void handleContinueInInteractive(AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            String latestSessionId = context.getSessionService().getLatestSessionId();
            if (latestSessionId != null) {
                List<ChatMessage> history = context.getSessionService().loadSession(latestSessionId);

                // 🚨 关键修复：过滤空消息
                List<ChatMessage> filteredHistory = history.stream()
                        .filter(msg -> msg != null &&
                                msg.getContent() != null &&
                                !msg.getContent().trim().isEmpty())
                        .collect(Collectors.toList());

                currentAgentLoop.loadHistory(filteredHistory);
                currentSessionId = latestSessionId;
                ui.displaySuccess("Continued from session: " + latestSessionId);

                // 显示加载的消息数量
                ui.displayInfo("Loaded " + filteredHistory.size() + " messages from history");
            } else {
                ui.displayInfo("No previous session found.");
            }
        } catch (Exception e) {
            ui.displayError("Failed to continue session: " + e.getMessage());
        }
    }

    /**
     * 在交互模式中加载指定会话
     */
    private void handleLoadSession(String sessionId, AgentLoop currentAgentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            List<ChatMessage> history = context.getSessionService().loadSession(sessionId);
            currentAgentLoop.loadHistory(history);
            currentSessionId = sessionId;
            ui.displaySuccess("Loaded session: " + sessionId);
        } catch (Exception e) {
            ui.displayError("Failed to load session: " + e.getMessage());
        }
    }

    /**
     * 在交互模式中执行单次提示
     */
    private void handleSinglePromptInInteractive(String prompt, AgentLoop agentLoop) {
        ThoughtCodingUI ui = context.getUi();
        try {
            ui.displayUserMessage(new ChatMessage("user", prompt));
            agentLoop.processInput(prompt);
        } catch (Exception e) {
            ui.displayError("Failed to process prompt: " + e.getMessage());
        }
    }

    private void showHelp() {
        context.getUi().displayInfo("""
                        🚀 可用命令：
                                                               \s
                                                                💬 对话命令：
                                                                  <消息>         发送消息给AI助手
                                                                  /new          开始新会话
                                                                  /save <名称>  保存当前会话
                                                                  /list         查看所有会话
                                                                  /clear        清空屏幕
                                                                  /help         显示帮助信息
                                                               \s
                                                                ⚡ 快捷命令：
                                                                  -c, --continue   继续上次会话
                                                                  -S <id>         加载指定会话 \s
                                                                  -p "提示"       单次提问模式
                                                                  --list-sessions 列出所有会话
                                                               \s
                                                                ❌ 退出命令：
                                                                  exit / quit     退出程序
                """);
    }

    private void handleInternalCommand(String command, AgentLoop agentLoop) {
        String[] parts = command.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "new":
                handleNewSession();
                break;
            case "save":
                handleSaveSession(argument);
                break;
            case "list":
                handleListSessions();
                break;
            case "clear":
                handleClearScreen();
                break;
            case "help":
                showHelp();
                break;
            default:
                context.getUi().displayError("Unknown command: " + cmd);
                break;
        }
    }

    private Integer handleSinglePrompt(AgentLoop agentLoop, String prompt) {
        try {
            ThoughtCodingUI ui = context.getUi();

            // 显示用户输入
            ChatMessage userMessage = new ChatMessage("user", prompt);
            ui.displayUserMessage(userMessage);

            // 处理AI响应
            agentLoop.processInput(prompt);

            // 等待AI响应完成（如果是流式输出，需要确保完成）
            // 这里可以添加适当的等待逻辑，或者依赖AgentLoop的内部同步

            return 0;
        } catch (Exception e) {
            context.getUi().displayError("Failed to process prompt: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private void handleNewSession() {
        try {
            // 保存当前会话
            saveCurrentSession();

            // 创建新会话
            currentSessionId = UUID.randomUUID().toString();
            String modelToUse = model != null ? model : context.getAppConfig().getDefaultModel();
            currentAgentLoop = new AgentLoop(context, currentSessionId, modelToUse);

            context.getUi().displaySuccess("Started new session: " + currentSessionId);
        } catch (Exception e) {
            context.getUi().displayError("Failed to create new session: " + e.getMessage());
        }
    }

    private void handleSaveSession(String sessionName) {
        if (sessionName == null || sessionName.trim().isEmpty()) {
            context.getUi().displayError("Usage: /save <session-name>");
            return;
        }

        try {
            List<ChatMessage> history = currentAgentLoop.getHistory();
            context.getSessionService().saveSession(sessionName, history);
            context.getUi().displaySuccess("Session saved as: " + sessionName);
        } catch (Exception e) {
            context.getUi().displayError("Failed to save session: " + e.getMessage());
        }
    }

    private void handleListSessions() {
        try {
            List<String> sessions = context.getSessionService().listSessions();
            if (sessions.isEmpty()) {
                context.getUi().displayInfo("No saved sessions found.");
            } else {
                displaySessionList(sessions);
            }
        } catch (Exception e) {
            context.getUi().displayError("Failed to list sessions: " + e.getMessage());
        }
    }

    private void displaySessionList(List<String> sessions) {
        ThoughtCodingUI ui = context.getUi();
        ui.getTerminal().writer().println("\n📁 Saved Sessions:");
        ui.getTerminal().writer().println("────────────────");
        for (int i = 0; i < sessions.size(); i++) {
            ui.getTerminal().writer().println((i + 1) + ". " + sessions.get(i));
        }
        ui.getTerminal().writer().println();
        ui.getTerminal().writer().flush();
    }

    private void handleClearScreen() {
        context.getUi().clearScreen();
        // 重新显示banner
        //context.getUi().showBanner();
    }

    private void saveCurrentSession() {
        try {
            List<ChatMessage> history = currentAgentLoop.getHistory();
            if (history != null && !history.isEmpty()) {
                context.getSessionService().saveSession("auto_" + currentSessionId, history);
            }
        } catch (Exception e) {
            // 忽略自动保存错误
            System.err.println("Auto-save failed: " + e.getMessage());
        }
    }
}