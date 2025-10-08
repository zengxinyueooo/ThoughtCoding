// src/main/java/com/thoughtcoding/ui/component/ProgressIndicator.java
package com.thoughtcoding.ui.component;

import com.thoughtcoding.ui.AnsiColors;
import org.jline.terminal.Terminal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProgressIndicator {
    private final Terminal terminal;
    private final ScheduledExecutorService scheduler;
    private final String[] spinnerFrames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private final String[] dotFrames = {".  ", ".. ", "...", "   "};

    private ScheduledFuture<?> progressTask;
    private final AtomicBoolean isRunning;
    private int frameIndex;

    public ProgressIndicator(Terminal terminal) {
        this.terminal = terminal;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.isRunning = new AtomicBoolean(false);
        this.frameIndex = 0;
    }

    public void show(String message) {
        if (isRunning.get()) {
            hide();
        }

        isRunning.set(true);
        frameIndex = 0;

        progressTask = scheduler.scheduleAtFixedRate(() -> {
            if (!isRunning.get()) {return;}

            String frame = spinnerFrames[frameIndex % spinnerFrames.length];
            String formattedMessage = String.format("%s%s%s %s%s",
                    AnsiColors.BRIGHT_YELLOW, frame, AnsiColors.RESET,
                    AnsiColors.BRIGHT_CYAN, message);

            terminal.writer().print("\r" + formattedMessage);
            terminal.writer().flush();

            frameIndex++;
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    public void update(String message) {
        if (!isRunning.get()) {return;}

        // 停止当前动画
        if (progressTask != null) {
            progressTask.cancel(false);
        }

        // 重新开始动画
        show(message);
    }

    public void hide() {
        isRunning.set(false);

        if (progressTask != null) {
            progressTask.cancel(false);
            progressTask = null;
        }

        // 清除进度行
        terminal.writer().print("\r" + " ".repeat(terminal.getWidth()) + "\r");
        terminal.writer().flush();
    }

    public void showDots(String message) {
        if (isRunning.get()) {
            hide();
        }

        isRunning.set(true);
        frameIndex = 0;

        progressTask = scheduler.scheduleAtFixedRate(() -> {
            if (!isRunning.get()) {return;}

            String dots = dotFrames[frameIndex % dotFrames.length];
            String formattedMessage = String.format("%s%s%s",
                    AnsiColors.BRIGHT_CYAN, message + dots, AnsiColors.RESET);

            terminal.writer().print("\r" + formattedMessage);
            terminal.writer().flush();

            frameIndex++;
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        hide();
        scheduler.shutdown();
    }
}