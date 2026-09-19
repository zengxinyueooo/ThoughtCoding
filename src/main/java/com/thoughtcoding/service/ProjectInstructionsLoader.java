package com.thoughtcoding.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目指令文件（CLAUDE.md / AGENTS.md）加载器 —— 对齐 Claude Code 的回退语义。
 *
 * <p>AGENTS.md 是 OpenAI/Cursor/Devin 等共同推出的开放标准（agents.md），
 * Claude Code 自 v2.1.277 起采用「CLAUDE.md 优先、无则回退 AGENTS.md」的兼容策略；
 * 本类对齐同一语义，让为 Codex / Claude Code 编写的项目指令在本 Agent 中直接生效。
 *
 * <p>扫描层级（由远及近，越近越具体，拼接顺序同此）：
 * <ol>
 *   <li>全局层：{@code ~/.thoughtcoding/} 下的 CLAUDE.md（优先）或 AGENTS.md（回退）——用户跨项目偏好</li>
 *   <li>项目根层：工作区根目录下的 CLAUDE.md（优先）或 AGENTS.md（回退）</li>
 * </ol>
 * 每层内部先找 CLAUDE.md，不存在才找 AGENTS.md；两层独立决策（可以全局用 AGENTS.md、项目用 CLAUDE.md）。
 *
 * <p>失败即降级：任一文件读取失败跳过该层，绝不阻塞启动。单文件超过
 * {@link #MAX_FILE_BYTES} 截断——指令文件是仓库内任意人可写的，不能让它撑爆 system prompt。
 */
public final class ProjectInstructionsLoader {

    /** 单个指令文件的读取上限（字节）。超出截断并标注。 */
    static final int MAX_FILE_BYTES = 64 * 1024;

    /** 加载结果：拼接后的正文 + 实际生效的文件来源（供启动展示与测试断言）。 */
    public record Loaded(String content, List<String> sources) {
        public boolean isEmpty() {
            return content == null || content.isBlank();
        }
    }

    private ProjectInstructionsLoader() {}

    /**
     * 扫描项目指令。projectRoot 为工作区根目录；全局层取 {@code user.home}。
     * 返回的 content 可为空串（无任何指令文件），调用方按空处理。
     */
    public static Loaded load(Path projectRoot) {
        StringBuilder combined = new StringBuilder();
        List<String> sources = new ArrayList<>();

        appendLayer(combined, sources, "global",
                Paths.get(System.getProperty("user.home"), ".thoughtcoding"));
        if (projectRoot != null) {
            appendLayer(combined, sources, "project",
                    projectRoot.toAbsolutePath());
        }

        return new Loaded(combined.toString().strip(), sources);
    }

    /** 扫描一层目录：CLAUDE.md 优先、AGENTS.md 回退——包括「存在但读取失败」的场景也继续回退。 */
    private static void appendLayer(StringBuilder combined, List<String> sources,
                                    String layerLabel, Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        for (Path candidate : new Path[]{dir.resolve("CLAUDE.md"), dir.resolve("AGENTS.md")}) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                String raw = Files.readString(candidate, StandardCharsets.UTF_8);
                String body = truncate(raw.strip(), MAX_FILE_BYTES,
                        candidate.getFileName().toString());
                if (body.isBlank()) {
                    return; // 空文件视为该层无指令，不再回退
                }
                if (combined.length() > 0) {
                    combined.append("\n\n");
                }
                combined.append("### 来源: ").append(layerLabel).append(" / ")
                        .append(candidate.getFileName()).append("\n\n").append(body);
                sources.add(layerLabel + ":" + candidate.getFileName());
                return; // 命中即消费该层
            } catch (Exception ignored) {
                // 读取失败（编码非法等）→ 尝试层内下一个候选，最终都不行则该层为空
            }
        }
    }

    private static String truncate(String body, int maxBytes, String filename) {
        if (body.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return body;
        }
        // 按字符裁剪到字节预算内（UTF-8 每字符最多 4 字节，保守折半迭代）
        int limit = maxBytes / 4;
        String cut = body.length() <= limit ? body : body.substring(0, limit);
        while (cut.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            cut = cut.substring(0, cut.length() / 2);
        }
        return cut + "\n\n[" + filename + " 已截断：仅保留前 " + maxBytes + " 字节]";
    }
}
