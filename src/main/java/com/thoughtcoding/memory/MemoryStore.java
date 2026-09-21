package com.thoughtcoding.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆(Memory)存储层 —— 仿 {@code SkillRegistry} 的 frontmatter 解析，但<b>可变</b>（运行时写入/整理）。
 *
 * <p>目录结构（仿 Claude Code 的 memory）：
 * <pre>
 *   .memory/
 *     MEMORY.md            ← 索引（每行一条：{@code - [name](file) — description}），常驻注入 system prompt
 *     *.md                 ← 单条记忆文件（Markdown + YAML frontmatter：name/description/type）
 * </pre>
 *
 * <p>{@code type} ∈ user（用户偏好）/ feedback（反馈约定）/ project（项目事实）/ reference（外部指引）。
 * 本类<b>不做任何 LLM 调用</b>——召回/抽取/整理的大脑在 {@link MemoryService}。任何文件操作失败都降级、不抛。
 */
public final class MemoryStore {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** frontmatter 块：开头 ---，正文前再一个 ---（\R 兼容 \n 与 \r\n）。 */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*\\R?(.*)\\z", Pattern.DOTALL);

    /** 索引文件恒定的名字（不在记忆文件集合里）。 */
    public static final String INDEX_FILE = "MEMORY.md";

    /** 合法记忆类型（frontmatter 里的 type 非法时回退为 user）。 */
    public static final List<String> MEMORY_TYPES = List.of("user", "feedback", "project", "reference");

    /** 单条记忆：filename 用于索引/读取，name/description 用于目录展示，type 用于分类，body 为正文。 */
    public record Memory(String filename, String name, String description, String type, String body) {
    }

    private final Path dir;
    private final int maxIndexEntries;
    private final Map<String, Memory> memories; // filename → Memory，写入序即展示序

    private MemoryStore(Path dir, int maxIndexEntries, Map<String, Memory> memories) {
        this.dir = dir;
        this.maxIndexEntries = maxIndexEntries;
        this.memories = memories;
    }

    /**
     * 加载（或创建）记忆目录。目录不存在则 {@code createDirectories} 后视为空库。
     * 解析/列举失败不阻塞——降级为空库。返回的实例可直接用 {@link #write}/{@link #replaceAll} 修改。
     */
    public static MemoryStore load(Path dir, int maxIndexEntries) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 目录创建失败 → 降级为空库（后续写操作也会各自失败降级）
        }
        Map<String, Memory> map = new LinkedHashMap<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
                for (Path file : files) {
                    String filename = file.getFileName().toString();
                    if (INDEX_FILE.equals(filename) || !filename.toLowerCase(Locale.ROOT).endsWith(".md")) {
                        continue;
                    }
                    try {
                        String raw = Files.readString(file);
                        Memory m = parse(filename, raw);
                        map.put(filename, m);
                    } catch (Exception ignored) {
                        // 单条记忆解析失败不阻塞其余记忆加载
                    }
                }
            } catch (IOException ignored) {
                // 目录列举失败 → 降级为空库
            }
        }
        return new MemoryStore(dir, maxIndexEntries, map);
    }

    @SuppressWarnings("unchecked")
    private static Memory parse(String filename, String raw) {
        String name = filename.replaceFirst("\\.md$", "");
        String description = null;
        String type = "user";
        String body = raw;

        Matcher m = FRONTMATTER.matcher(raw);
        if (m.matches()) {
            String fm = m.group(1);
            body = m.group(2);
            try {
                Map<String, Object> meta = YAML_MAPPER.readValue(fm, Map.class);
                Object n = meta.get("name");
                if (n != null && !n.toString().isBlank()) {
                    name = n.toString().trim();
                }
                Object d = meta.get("description");
                if (d != null && !d.toString().isBlank()) {
                    description = d.toString().trim();
                }
                Object t = meta.get("type");
                if (t != null && MEMORY_TYPES.contains(t.toString().trim())) {
                    type = t.toString().trim();
                }
            } catch (Exception ignored) {
                // frontmatter 非法 YAML → 忽略元数据，整份原文当正文
                body = raw;
            }
        }

        if (description == null || description.isBlank()) {
            description = firstNonBlankLine(body);
        }

        return new Memory(filename, name, description, type, body.strip());
    }

    private static String firstNonBlankLine(String body) {
        for (String line : body.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return t.replaceFirst("^#+\\s*", "");
            }
        }
        return "";
    }

    /**
     * 写入一条记忆。slugify name 得文件名（{@code <slug>.md}），frontmatter 与正文一起落盘，
     * 更新内存 map 并重建索引。失败返回 null（调用方静默忽略），不抛。
     */
    public synchronized Path write(String name, String type, String description, String body) {
        try {
            String slug = slugify(name);
            String filename = slug + ".md";
            int suffix = 2;
            while (memories.containsKey(filename) && !name.equals(memories.get(filename).name())) {
                filename = slug + "-" + suffix++ + ".md";
            }
            String typeVal = (type != null && MEMORY_TYPES.contains(type)) ? type : "user";
            String content = serialize(name, typeVal, description, body);
            Path target = dir.resolve(filename);
            writeAtomically(target, content);
            memories.put(filename, new Memory(filename, name, description == null ? "" : description, typeVal, body == null ? "" : body.strip()));
            rebuildIndexFile();
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 整理(Dream)用：整体替换全部记忆（去重/合并/清理后的结果），并重建索引。
     *
     * <p>顺序不可换：<b>先写全部新文件（覆盖同名、创建新名）→ 全部成功后才删旧文件</b>——
     * 删除循环只删「不在新集合 {@code next.keySet()} 里的」旧文件，<b>刚写入的新文件绝不在删除范围</b>，
     * 修复了旧实现「写完再全删」会连带删掉新文件（尤其是 dream 新增了旧记忆没有的名字时）的 bug。
     * 任一写失败则返回、不进入删除，保留既有记忆不丢。
     */
    public synchronized boolean replaceAll(List<Memory> newMemories) {
        if (newMemories == null) {
            return false;
        }
        // 1. 先在内存里算好全部目标文件（filename → 内容）与目标集合，避免边写边决定
        Map<String, String> contents = new LinkedHashMap<>();
        Map<String, Memory> next = new LinkedHashMap<>();
        for (Memory m : newMemories) {
            if (m == null || m.name() == null || m.name().isBlank()) {
                continue;
            }
            String slug = slugify(m.name());
            String filename = slug + ".md";
            int suffix = 2;
            while (next.containsKey(filename)) {
                filename = slug + "-" + suffix++ + ".md";
            }
            String typeVal = (m.type() != null && MEMORY_TYPES.contains(m.type())) ? m.type() : "user";
            try {
                contents.put(filename, serialize(m.name(), typeVal, m.description(), m.body()));
            } catch (IOException e) {
                return false;
            }
            next.put(filename, new Memory(filename, m.name(), m.description() == null ? "" : m.description(),
                    typeVal, m.body() == null ? "" : m.body().strip()));
        }

        // 2. 先写全部新文件（覆盖同名、创建新名）；任一失败 → 不进入删除，保留旧记忆
        for (Map.Entry<String, String> e : contents.entrySet()) {
            try {
                writeAtomically(dir.resolve(e.getKey()), e.getValue());
            } catch (Exception ex) {
                return false;
            }
        }

        // 3. 全部写成功后，只删「不在新集合里」的旧文件（新文件/保留的同名文件都不在删除范围）
        try {
            try (var stream = Files.list(dir)) {
                for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile).toList()) {
                    String filename = file.getFileName().toString();
                    if (!INDEX_FILE.equals(filename)
                            && filename.toLowerCase(Locale.ROOT).endsWith(".md")
                            && !next.containsKey(filename)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        } catch (Exception ignored) {
            // 清理失败不影响新集合生效
        }

        // 4. 清 map、替换、重建索引
        memories.clear();
        memories.putAll(next);
        rebuildIndexFile();
        return true;
    }

    private static String serialize(String name, String type, String description, String body) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("description", description == null ? "" : description);
        metadata.put("type", type);
        String yaml = YAML_MAPPER.writeValueAsString(metadata).stripTrailing();
        if (yaml.startsWith("---")) {
            yaml = yaml.substring(3).stripLeading();
        }
        return "---\n" + yaml + "\n---\n\n" + (body == null ? "" : body.strip()) + "\n";
    }

    /** 单文件先写同目录临时文件再替换，进程中断时不会留下半截 Markdown。 */
    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 重建 MEMORY.md 索引（受 maxIndexEntries 上限，超限裁掉最早写入的）。 */
    public synchronized void rebuildIndexFile() {
        try {
            List<String> lines = new ArrayList<>();
            for (Memory m : memories.values()) {
                if (lines.size() >= maxIndexEntries) {
                    break;
                }
                lines.add("- [" + oneLine(m.name()) + "](" + m.filename() + ") — " + oneLine(m.description()));
            }
            writeAtomically(dir.resolve(INDEX_FILE), String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
        } catch (Exception ignored) {
            // 索引重建失败不抛
        }
    }

    /** 索引文本（供 system prompt 注入），受 maxIndexEntries 上限。 */
    public synchronized String index() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Memory m : memories.values()) {
            if (count >= maxIndexEntries) {
                break;
            }
            sb.append("- [").append(oneLine(m.name())).append("](").append(m.filename())
                    .append(") — ").append(oneLine(m.description())).append('\n');
            count++;
        }
        return sb.toString().stripTrailing();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
    }

    private static String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "memory-" + Integer.toUnsignedString(name.hashCode(), 36) : slug;
    }

    public synchronized List<Memory> list() {
        return List.copyOf(memories.values());
    }

    public synchronized Memory get(String filename) {
        return memories.get(filename);
    }

    public synchronized int size() {
        return memories.size();
    }

    public synchronized boolean isEmpty() {
        return memories.isEmpty();
    }
}
