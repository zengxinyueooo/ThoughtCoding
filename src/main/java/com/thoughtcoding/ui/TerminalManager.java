package com.thoughtcoding.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;

import java.io.IOException;

/**
 * 终端管理类，负责初始化和恢复终端状态，处理终端大小调整等
 */
public class TerminalManager {
    private final Terminal terminal;
    private final Attributes originalAttributes;
    private final Size originalSize;

    public TerminalManager(Terminal terminal) {
        this.terminal = terminal;
        this.originalAttributes = terminal.getAttributes();
        this.originalSize = terminal.getSize();

        setupTerminal();
    }

    private void setupTerminal() {
        // 设置终端属性
        Attributes attributes = new Attributes(originalAttributes);

        // 启用原始模式（用于更好的输入处理）
        // attributes.setLocalFlags(EnumSet.of(Attributes.LocalFlag.ICANON, Attributes.LocalFlag.ECHO), false);

        terminal.setAttributes(attributes);

    }

    public void restoreTerminal() {
        terminal.setAttributes(originalAttributes);
    }

    public int getWidth() {
        return terminal.getWidth();
    }

    public int getHeight() {
        return terminal.getHeight();
    }

    public void resize(int width, int height) {
        terminal.setSize(new Size(width, height));
    }

    public void flush() {
        terminal.writer().flush();
    }

    public Terminal getTerminal() {
        return terminal;
    }
}