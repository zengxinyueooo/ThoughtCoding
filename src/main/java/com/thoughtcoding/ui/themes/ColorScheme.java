package com.thoughtcoding.ui.themes;

/**
 * 颜色方案类，定义了一组颜色属性，用于终端UI的主题配置
 */
public class ColorScheme {
    // 预定义主题
    public static final ColorScheme DEFAULT = new ColorScheme(
            "#2E3440", "#3B4252", "#434C5E", "#4C566A",  // 背景色
            "#D8DEE9", "#E5E9F0", "#ECEFF4",             // 前景色
            "#88C0D0", "#81A1C1", "#5E81AC",             // 蓝色调
            "#A3BE8C", "#8FBCBB", "#88C0D0",             // 绿色/青色调
            "#EBCB8B", "#D08770", "#BF616A"              // 黄色/红色调
    );

    public static final ColorScheme DARK = new ColorScheme(
            "#1E1E1E", "#252526", "#2D2D30", "#3E3E42",
            "#CCCCCC", "#D4D4D4", "#FFFFFF",
            "#007ACC", "#005A9E", "#004578",
            "#4EC9B0", "#2D7D9A", "#264F78",
            "#DCDCAA", "#CE9178", "#F44747"
    );

    public static final ColorScheme LIGHT = new ColorScheme(
            "#FFFFFF", "#F5F5F5", "#EEEEEE", "#E0E0E0",
            "#333333", "#555555", "#777777",
            "#0078D7", "#106EBE", "#005A9E",
            "#107C10", "#498205", "#D83B01",
            "#B4009E", "#E3008C", "#BF0077"
    );

    private final String background;
    private final String backgroundAlt;
    private final String backgroundHighlight;
    private final String backgroundDim;
    private final String foreground;
    private final String foregroundAlt;
    private final String foregroundBright;
    private final String primary;
    private final String primaryAlt;
    private final String primaryDim;
    private final String success;
    private final String info;
    private final String warning;
    private final String error;
    private final String accent1;
    private final String accent2;

    public ColorScheme(String background, String backgroundAlt, String backgroundHighlight, String backgroundDim,
                       String foreground, String foregroundAlt, String foregroundBright,
                       String primary, String primaryAlt, String primaryDim,
                       String success, String info, String warning,
                       String error, String accent1, String accent2) {
        this.background = background;
        this.backgroundAlt = backgroundAlt;
        this.backgroundHighlight = backgroundHighlight;
        this.backgroundDim = backgroundDim;
        this.foreground = foreground;
        this.foregroundAlt = foregroundAlt;
        this.foregroundBright = foregroundBright;
        this.primary = primary;
        this.primaryAlt = primaryAlt;
        this.primaryDim = primaryDim;
        this.success = success;
        this.info = info;
        this.warning = warning;
        this.error = error;
        this.accent1 = accent1;
        this.accent2 = accent2;
    }

    // Getters
    public String getBackground() { return background; }
    public String getBackgroundAlt() { return backgroundAlt; }
    public String getBackgroundHighlight() { return backgroundHighlight; }
    public String getBackgroundDim() { return backgroundDim; }
    public String getForeground() { return foreground; }
    public String getForegroundAlt() { return foregroundAlt; }
    public String getForegroundBright() { return foregroundBright; }
    public String getPrimary() { return primary; }
    public String getPrimaryAlt() { return primaryAlt; }
    public String getPrimaryDim() { return primaryDim; }
    public String getSuccess() { return success; }
    public String getInfo() { return info; }
    public String getWarning() { return warning; }
    public String getError() { return error; }
    public String getAccent1() { return accent1; }
    public String getAccent2() { return accent2; }
}