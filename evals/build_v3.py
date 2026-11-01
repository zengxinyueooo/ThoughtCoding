"""Build only new v3 adapters against existing production classes, then test offline.

Does not mutate old adapters, production classes, or historical results. Source
transformations are count-checked and recorded so the policy/prompt stay intact.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Frozen adapter shape changed; review v3 generation")
    return source.replace(old, new)


def generate(source):
    source = replace_once(source, "import com.thoughtcoding.observability.EvalTelemetry;",
                          "import com.thoughtcoding.observability.EvalTelemetryV3;")
    source = replace_once(source, "public class EvalMain {", "public class EvalMainV3 {")
    source = replace_once(source, "    public static void main(String[] args) throws Exception {", """    public static void main(String[] args) throws Exception {
        boolean agentCompleted = false;
        Throwable mainFailure = null;
        try {
            run(args);
            agentCompleted = true;
        } catch (Exception | Error error) {
            mainFailure = error;
            throw error;
        } finally {
            try { EvalTelemetryV3.finish(agentCompleted); }
            catch (Exception finishError) {
                if (mainFailure != null) mainFailure.addSuppressed(finishError);
                else throw finishError;
            }
        }
    }

    private static void run(String[] args) throws Exception {""")
    source = replace_once(source, """        if (Boolean.TRUE.equals(contract.get("local_span_backup")))
            EvalTelemetry.initialize(trace.resolveSibling("spans.jsonl"));
        else Telemetry.initialize();""", """        EvalTelemetryV3.initialize(trace.resolveSibling("spans.jsonl"));""")
    return source


def main():
    dependencies = ROOT / "target/eval-classpath.txt"
    if not dependencies.is_file() or not (ROOT / "target/classes").is_dir():
        raise SystemExit("Compile production runtime first; v3 build never changes it implicitly")
    output = ROOT / "evals/.build-v3"
    generated = output / "generated"
    generated.mkdir(parents=True, exist_ok=True)
    original = ROOT / "evals/java/EvalMain.java"
    entrypoint = generated / "EvalMainV3.java"
    entrypoint.write_text(generate(original.read_text(encoding="utf-8")), encoding="utf-8")
    sources = [entrypoint, ROOT / "evals/java/EvalTelemetryV3.java", ROOT / "evals/java/EvalTelemetryV3SelfTest.java",
               ROOT / "evals/java/EvalTelemetryV3Canary.java"]
    classpath = os.pathsep.join([str(ROOT / "target/classes"), dependencies.read_text().strip()])
    subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(output), *map(str, sources)], check=True, timeout=120)
    full_cp = str(output) + os.pathsep + classpath
    subprocess.run(["java", "-cp", full_cp, "EvalMainV3", "--self-test"], check=True, timeout=60)
    evidence = ROOT / "evals/results" / ("v3-durable-self-test-" + uuid.uuid4().hex[:10])
    subprocess.run(["java", "-cp", full_cp, "com.thoughtcoding.observability.EvalTelemetryV3SelfTest", str(evidence)],
                   check=True, timeout=60)
    # Deliberately fail after tracing initializes, before any Agent/model work.
    failed = evidence / "adapter-exception"
    failed.mkdir()
    (failed / "runtime.yaml").write_text("{}\n", encoding="utf-8")
    (failed / "input.json").write_text(json.dumps({"prompt": "synthetic failure check", "allowed_paths": [],
        "require_tracing": True, "verification_gate_enabled": True, "public_verification": False}), encoding="utf-8")
    with (failed / "agent.log").open("wb") as log:
        result = subprocess.run(["java", "-cp", full_cp, "EvalMainV3", str(failed / "runtime.yaml"),
            str(failed / "input.json"), str(failed / "tools.jsonl"), str(failed / "history.json")],
            cwd=failed, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    receipt = json.loads((failed / "otlp-spool-completion.json").read_text(encoding="utf-8"))
    if result.returncode == 0 or receipt["agent_completed"] or not receipt["complete"]:
        raise AssertionError("Adapter exception must remain a failure with a separate transport receipt")
    if "Verification feedback requires the shared public fixture" not in (failed / "agent.log").read_text(encoding="utf-8"):
        raise AssertionError("Adapter failed at an unexpected location")
    manifest = {"schema_version": 3, "entrypoint": "EvalMainV3", "adapter": str(output),
                "production_classes": str(ROOT / "target/classes"), "source_sha256": {
                    str(path.relative_to(ROOT)).replace("\\", "/"): sha(path)
                    for path in [original, Path(__file__), *sources]},
                "class_sha256": {str(path.relative_to(output)).replace("\\", "/"): sha(path)
                                 for path in sorted(output.rglob("*.class"))},
                "dependencies_sha256": sha(dependencies), "self_test_evidence": str(evidence),
                "new_model_calls": 0, "new_network_calls": 0}
    (output / "build-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("V3 build/self-test complete; evidence: " + str(evidence))


if __name__ == "__main__":
    main()
