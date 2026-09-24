package com.thoughtcoding.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 声明式权限规则（config.yaml 的 permissions 段）——在内置权限矩阵之外，
 * 让用户按工具/命令/路径自定义三档决策。
 *
 * <p>规则语法（对齐 Claude Code 的 permissions 风格）：
 * <pre>
 *   Bash(mvn *)        工具 + 说明符：* 作通配符，整串匹配命令/路径
 *   Bash(git status*)  前缀式常用写法（尾部 * 补齐任意后缀）
 *   Write(.env*)       文件工具匹配 path 参数
 *   Read               裸工具名：该工具全部放行/确认/拒绝
 * </pre>
 *
 * <p>层级与优先级（高 → 低，上层永远压住下层，用户规则<b>不能</b>解锁安全底线）：
 * <ol>
 *   <li>bash 硬拒绝列表（rm -rf / 等）——allow 规则也无法放行</li>
 *   <li>计划模式矩阵（PlanMode 激活时的读写分离）</li>
 *   <li>用户 deny 规则 → ask 规则 → allow 规则</li>
 *   <li>内置默认矩阵（write/edit/bash 确认，read/glob 越界确认，纯内存工具放行）</li>
 * </ol>
 *
 * <p>说明符匹配的目标参数：bash 匹配 {@code command}；read/glob/write/edit 匹配 {@code path}；
 * 其他工具无说明符目标，只有裸工具名规则能命中。匹配大小写不敏感。
 * 无法解析的规则条目跳过并告警（fail-open 回落到默认矩阵），不阻塞启动。
 */
public final class PermissionRules {

    private static final Logger log = LoggerFactory.getLogger(PermissionRules.class);

    public enum Decision { ALLOW, ASK, DENY }

    /** 命中结果：决策 + 命中的规则原文（供拒绝/确认消息展示）。 */
    public record Match(Decision decision, String rule) {}

    private record Compiled(String tool, Pattern spec, String original) {}

    private final List<Compiled> denyRules;
    private final List<Compiled> askRules;
    private final List<Compiled> allowRules;

    private PermissionRules(List<Compiled> denyRules, List<Compiled> askRules, List<Compiled> allowRules) {
        this.denyRules = denyRules;
        this.askRules = askRules;
        this.allowRules = allowRules;
    }

    /**
     * 解析三组规则文本。任一条目格式非法只跳过该条并告警，不抛出——
     * 权限规则是用户配置的便利层，语法错误不应让整个应用起不来。
     */
    public static PermissionRules parse(List<String> denySpecs, List<String> askSpecs, List<String> allowSpecs) {
        return new PermissionRules(compile(denySpecs), compile(askSpecs), compile(allowSpecs));
    }

    /** 无任何规则的空集（等价于关闭声明式规则，全部回落默认矩阵）。 */
    public static PermissionRules empty() {
        return parse(null, null, null);
    }

    private static List<Compiled> compile(List<String> specs) {
        List<Compiled> out = new ArrayList<>();
        if (specs == null) {
            return out;
        }
        for (String raw : specs) {
            Compiled c = compileOne(raw);
            if (c != null) {
                out.add(c);
            } else {
                log.warn("忽略无法解析的权限规则: {}", raw);
            }
        }
        return out;
    }

    /** 单条规则编译：{@code Tool(spec)} 或裸 {@code Tool}；非法返回 null。 */
    private static Compiled compileOne(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        int lp = s.indexOf('(');
        if (lp < 0) {
            String tool = s.toLowerCase();
            return tool.isEmpty() ? null : new Compiled(tool, null, s);
        }
        if (!s.endsWith(")") || s.length() - 1 <= lp) {
            return null;
        }
        String tool = s.substring(0, lp).strip().toLowerCase();
        String spec = s.substring(lp + 1, s.length() - 1).strip();
        if (tool.isEmpty() || spec.isEmpty()) {
            return null;
        }
        return new Compiled(tool, wildcard(spec), s);
    }

    /** 说明符转正则：除 {@code *} 外全部字面量，整串匹配，大小写不敏感。 */
    private static Pattern wildcard(String spec) {
        StringBuilder re = new StringBuilder();
        for (char c : spec.toCharArray()) {
            if (c == '*') {
                re.append(".*");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * 按工具名与参数匹配规则：deny → ask → allow 依次找第一个命中。
     *
     * @return 命中的决策与规则原文；无命中返回 null（回落默认矩阵）
     */
    public Match match(String toolName, Map<String, Object> params) {
        String tool = toolName == null ? "" : toolName.toLowerCase();
        String subject = subjectOf(tool, params);
        Match m = firstMatch(denyRules, Decision.DENY, tool, subject);
        if (m == null) {
            m = firstMatch(askRules, Decision.ASK, tool, subject);
        }
        if (m == null) {
            m = firstMatch(allowRules, Decision.ALLOW, tool, subject);
        }
        return m;
    }

    private static Match firstMatch(List<Compiled> rules, Decision decision, String tool, String subject) {
        for (Compiled c : rules) {
            if (!c.tool().equals(tool)) {
                continue;
            }
            if (c.spec() == null) {
                return new Match(decision, c.original());
            }
            if (subject != null && c.spec().matcher(subject).matches()) {
                return new Match(decision, c.original());
            }
        }
        return null;
    }

    /** 规则说明符的匹配目标：bash 看 command，文件工具看 path，其余无目标。 */
    private static String subjectOf(String tool, Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        String key = switch (tool) {
            case "bash" -> "command";
            case "read", "glob", "write", "edit" -> "path";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        Object v = params.get(key);
        return v == null ? null : v.toString().strip();
    }

    public boolean isEmpty() {
        return denyRules.isEmpty() && askRules.isEmpty() && allowRules.isEmpty();
    }
}
