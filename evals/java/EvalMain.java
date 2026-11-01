import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.*;
import com.thoughtcoding.hook.*;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.service.*;
import com.thoughtcoding.skill.SkillRegistry;
import com.thoughtcoding.tool.ToolRegistry;
import com.thoughtcoding.tool.tools.*;
import com.thoughtcoding.ui.ThoughtCodingUI;
import com.thoughtcoding.observability.Telemetry;
import com.thoughtcoding.observability.EvalTelemetry;
import java.nio.file.*;
import java.util.*;

/** Evaluation adapter; runs the production AgentLoop, not a replacement agent. */
public class EvalMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PUBLIC_COMMAND = "mvn -q -Dtest=PublicWriteCompatibilityTest test";
    private static final String PUBLIC_SOURCE = "src/test/java/com/thoughtcoding/publicchecks/PublicWriteCompatibilityTest.java";
    private static final Set<String> COMMANDS = Set.of("mvn test", "mvn -q test", "mvn -B -ntp test");

    static String restriction(Path root, Set<String> allowed, String name, Map<String,Object> args) throws Exception {
        if ("todo_write".equals(name)) return null;
        if ("bash".equals(name)) {
            String command = String.valueOf(args.get("command")).trim();
            return (COMMANDS.contains(command) || (PUBLIC_COMMAND.equals(command) && Files.isRegularFile(root.resolve(PUBLIC_SOURCE)))) ? null
                    : "评测只允许 bash 执行 mvn test、mvn -q test 或 mvn -B -ntp test；查找文件请使用 glob/read。";
        }
        if (!Set.of("read", "glob", "edit", "write").contains(name)) return "本轮没有启用此工具。";
        Path target = root.resolve(String.valueOf(args.getOrDefault("path", "."))).normalize().toAbsolutePath();
        if (!target.startsWith(root)) return "评测禁止访问工作区之外的路径。";
        Path ancestor = target;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(root.toRealPath())) return "评测禁止通过链接访问工作区之外。";
        String relative = root.relativize(target).toString().replace('\\', '/');
        if (relative.equals(".git") || relative.startsWith(".git/") || relative.equals("sessions")
                || relative.startsWith("sessions/") || relative.equals("transcripts") || relative.startsWith("transcripts/"))
            return "评测禁止读取运行工件。";
        if (Set.of("edit", "write").contains(name) && !allowed.contains(relative)) return "修改路径不在任务允许范围内。";
        return null;
    }

    static VerificationFeedbackHook.Check publicVerification(HookContext hook, HookRegistry policies,
            ToolRegistry tools, Path root, Path journal, int attempt) throws Exception {
        // Both arms can call this exact command. No private acceptance test is copied here.
        Path report = root.resolve("target/surefire-reports/TEST-com.thoughtcoding.publicchecks.PublicWriteCompatibilityTest.xml");
        Files.deleteIfExists(report);
        VerificationFeedbackHook.Check check;
        try (var observation = Telemetry.observation("verification.public", "span", Map.of("command", PUBLIC_COMMAND));
             var scope = observation.activate()) {
            var call = new com.thoughtcoding.model.ToolCall("bash", Map.of("command", PUBLIC_COMMAND),
                    null, false, 0, false, "public-gate-" + attempt);
            var outcome = new ToolExecutionPipeline(hook.getContext(), policies, tools)
                    .execute(call, new CancelToken(), hook.getHistory());
            String details;
            VerificationFeedbackHook.Status status;
            if (Files.isRegularFile(report)) {
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
                int tests = Integer.parseInt(suite.getAttribute("tests"));
                int failures = Integer.parseInt(suite.getAttribute("failures"));
                int errors = Integer.parseInt(suite.getAttribute("errors"));
                int skipped = Integer.parseInt(suite.getAttribute("skipped"));
                details = "公开兼容性验证：tests=" + tests + ", failures=" + failures + ", errors=" + errors + ", skipped=" + skipped;
                for (String kind : List.of("failure", "error")) {
                    var nodes = suite.getElementsByTagName(kind);
                    for (int i = 0; i < nodes.getLength(); i++) {
                        var node = (org.w3c.dom.Element) nodes.item(i);
                        var testcase = (org.w3c.dom.Element) node.getParentNode();
                        details += "\n" + testcase.getAttribute("name") + ": " + node.getAttribute("message");
                    }
                }
                status = tests == 2 && skipped == 0 && failures == 0 && errors == 0 && outcome.result().isSuccess()
                        ? VerificationFeedbackHook.Status.PASSED
                        : (tests == 2 && skipped == 0 ? VerificationFeedbackHook.Status.FAILED : VerificationFeedbackHook.Status.UNAVAILABLE);
            } else {
                status = VerificationFeedbackHook.Status.UNAVAILABLE;
                details = "没有生成新的公开测试报告。" + (outcome.result().isSuccess()
                        ? outcome.result().getOutput() : outcome.result().getError());
            }
            check = new VerificationFeedbackHook.Check(status, details);
            observation.metadata("attempt", attempt);
            observation.metadata("status", status.name());
            observation.metadata("evaluation_role", "public diagnostic feedback; not final acceptance");
            observation.output(details);
        }
        Files.writeString(journal, JSON.writeValueAsString(Map.of("attempt", attempt, "status", check.status(),
                "evidence", check.evidence())) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return check;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        if (args.length == 2 && args[0].equals("--public-probe-smoke")) {
            Sandbox.init(root.toString());
            AppConfig smokeConfig = new AppConfig();
            ToolRegistry smokeTools = new ToolRegistry(smokeConfig);
            smokeTools.register(new BashTool(smokeConfig));
            HookRegistry smokePolicies = new HookRegistry();
            smokePolicies.register(HookType.PRE_TOOL_USE, c -> {
                String blocked = restriction(root, Set.of(), c.getToolCall().getToolName(), c.getToolCall().getParameters());
                return blocked == null ? HookResult.proceed() : HookResult.block(blocked);
            });
            var result = publicVerification(HookContext.forStop(null, List.of()), smokePolicies,
                    smokeTools, root, Path.of(args[1]), 1);
            if (result.status() != VerificationFeedbackHook.Status.FAILED
                    || !result.evidence().contains("legalNestedWriteMustStillCreateItsParentDirectories"))
                throw new AssertionError("Known-bad public probe did not return actionable test evidence");
            System.out.println("Public probe smoke passed: known-bad patch produced FAILED with real test evidence; zero model calls");
            return;
        }
        if (args.length == 1 && args[0].equals("--self-test")) {
            Set<String> allowed = Set.of("allowed.java");
            if (restriction(root, allowed, "read", Map.of("path", "../config.yaml")) == null
                    || restriction(root, allowed, "write", Map.of("path", "pom.xml")) == null
                    || restriction(root, allowed, "bash", Map.of("command", "mvn test; echo unsafe")) == null
                    || restriction(root, allowed, "edit", Map.of("path", "allowed.java")) != null
                    || restriction(root, allowed, "bash", Map.of("command", "mvn test")) != null)
                throw new AssertionError("Evaluation restriction checks failed");
            System.out.println("Evaluation restriction checks passed");
            return;
        }
        // All configuration and trace files remain outside the task workspace.
        AppConfig config = new ObjectMapper(new YAMLFactory()).readValue(Path.of(args[0]).toFile(), AppConfig.class);
        Map<String,Object> contract = JSON.readValue(Path.of(args[1]).toFile(), Map.class);
        if (contract.containsKey("session_id") &&
                !String.valueOf(contract.get("session_id")).matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Evaluation session_id must be filename-safe");
        Set<String> allowed = new HashSet<>((List<String>) contract.get("allowed_paths"));
        Path trace = Path.of(args[2]);
        if (Boolean.TRUE.equals(contract.get("local_span_backup")))
            EvalTelemetry.initialize(trace.resolveSibling("spans.jsonl"));
        else Telemetry.initialize();
        if (Boolean.TRUE.equals(contract.get("require_tracing")) && !Telemetry.enabled())
            throw new IllegalStateException("Evaluation requires tracing environment configuration");
        Sandbox.init(root.toString());
        ToolRegistry tools = new ToolRegistry(config);
        tools.register(new ReadTool(config));
        tools.register(new GlobTool(config));
        tools.register(new WriteTool(config));
        tools.register(new EditTool(config));
        tools.register(new BashTool(config));
        tools.register(new TodoWriteTool());
        HookRegistry hooks = new HookRegistry();
        hooks.register(HookType.PRE_TOOL_USE, new Hook() {
            public HookResult execute(HookContext c) throws Exception {
                String blocked = restriction(root, allowed, c.getToolCall().getToolName(), c.getToolCall().getParameters());
                Files.writeString(trace, JSON.writeValueAsString(Map.of("event", "tool_attempt", "call", c.getToolCall(),
                    "blocked", blocked != null, "reason", blocked == null ? "" : blocked)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return blocked == null ? HookResult.proceed() : HookResult.block(blocked);
            }
            public HookFailurePolicy failurePolicy() { return HookFailurePolicy.FAIL_CLOSED; }
        });
        hooks.register(HookType.POST_TOOL_USE, c -> {
            Files.writeString(trace, JSON.writeValueAsString(Map.of("event", "tool_result", "call", c.getToolCall(),
                "result", c.getToolResult())) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return HookResult.proceed();
        });
        if (Boolean.TRUE.equals(contract.get("verification_gate_enabled"))) {
            if (!Boolean.TRUE.equals(contract.get("public_verification")) || !Files.isRegularFile(root.resolve(PUBLIC_SOURCE)))
                throw new IllegalArgumentException("Verification feedback requires the shared public fixture");
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var gate = new VerificationFeedbackHook(c -> publicVerification(c, hooks, tools,
                    root, trace.resolveSibling("verification-gate.jsonl"), attempts.incrementAndGet()), 2);
            hooks.register(HookType.USER_PROMPT_SUBMIT, gate);
            hooks.register(HookType.STOP, gate);
        }
        ContextManager memory = new ContextManager(config, SkillRegistry.scan(root.resolve("__eval_no_skills")), null);
        ThoughtCodingUI ui = new ThoughtCodingUI();
        try (ThoughtCodingContext context = new ThoughtCodingContext.Builder().appConfig(config)
                .toolRegistry(tools).hookRegistry(hooks).contextManager(memory)
                .aiService(new LangChainService(config, tools, memory)).ui(ui)
                .sessionService(new SessionService()).performanceMonitor(new PerformanceMonitor())
                .subAgentExecutor(new SubAgentExecutor(1, ui)).build()) {
            AgentLoop loop = new AgentLoop(context,
                String.valueOf(contract.getOrDefault("session_id", UUID.randomUUID().toString())),
                config.getDefaultModel());
            loop.setAutoApprove(true);
            String prompt = contract.get("prompt") + "\n\n运行约定：这是独立的 Java 修复工作区。"
                + "只修改任务允许的源码，不修改测试或构建配置。不使用子代理或外部服务。"
                + "使用 glob/read 定位和读取文件，使用 edit/write 修改。bash 只允许 mvn test、mvn -q test 或 mvn -B -ntp test。"
                + "可以运行已有测试，独立验收在交付后进行。完成后简述修改和验证。";
            if (Boolean.TRUE.equals(contract.get("public_verification"))) {
                prompt += "\n本次提供公开兼容性检查 " + PUBLIC_SOURCE + "，可读取但不得修改。"
                        + "除前述命令外，还允许运行 " + PUBLIC_COMMAND + "。公开检查只覆盖已有正常行为，不替代独立验收。";
            }
            loop.processInput(prompt);
            JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[3]).toFile(), loop.getHistory());
        }
    }
}
