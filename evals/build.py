"""Compile the production classes and the evaluation-only Java adapter."""
import os
from pathlib import Path
import shutil
import subprocess
import uuid
from run import ROOT, command


def main():
    logs = ROOT / "evals/results"
    logs.mkdir(exist_ok=True)
    maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    if not maven:
        raise SystemExit("Maven is not on PATH")
    result = command([maven, "-B", "-ntp", "compile", "dependency:build-classpath",
        "-Dmdep.outputFile=target/eval-classpath.txt"], ROOT, logs / "build.log", 600)
    if result["exit_code"] != 0:
        raise SystemExit("Build failed; see evals/results/build.log")
    classes = ROOT / "evals/.build"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join([str(ROOT / "target/classes"),
        (ROOT / "target/eval-classpath.txt").read_text().strip()])
    subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
        "-d", str(classes), str(ROOT / "evals/java/EvalMain.java"),
        str(ROOT / "evals/java/EvalTelemetry.java")], check=True)
    subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath, "EvalMain", "--self-test"], check=True)
    subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath,
        "com.thoughtcoding.observability.EvalTelemetry",
        str(logs / ("span-backup-self-test-"+uuid.uuid4().hex[:10]+".jsonl"))], check=True)


if __name__ == "__main__":
    main()
