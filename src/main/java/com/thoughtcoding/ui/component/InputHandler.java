package com.thoughtcoding.ui.component;

import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;

/**
 * 输入处理组件，负责读取用户输入和密码，并支持自动补全功能
 */
public class InputHandler {
    private LineReader lineReader;
    private Completer completer;
    private final Terminal terminal;

    public InputHandler(Terminal terminal, Completer initialCompleter) {
        this.terminal = terminal;
        this.completer = initialCompleter;
        this.lineReader = createLineReader();
    }

    private LineReader createLineReader() {
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .build();
    }

    public String readInput(String prompt) {
        try {
            return lineReader.readLine(prompt);
        } catch (UserInterruptException e) {
            throw new RuntimeException("Operation cancelled by user");
        } catch (EndOfFileException e) {
            throw new RuntimeException("End of input");
        }
    }

    public String readInput() {
        return readInput("thought> ");
    }

    public String readPassword(String prompt) {
        try {
            return lineReader.readLine(prompt, '*');
        } catch (UserInterruptException e) {
            throw new RuntimeException("Operation cancelled by user");
        } catch (EndOfFileException e) {
            throw new RuntimeException("End of input");
        }
    }

    public void setCompleter(Completer completer) {
        this.completer = completer;
        this.lineReader = createLineReader(); // Rebuild LineReader with the new Completer
    }

    public void clearCompleter() {
        this.completer = null;
        this.lineReader = createLineReader(); // Rebuild LineReader without a Completer
    }
}