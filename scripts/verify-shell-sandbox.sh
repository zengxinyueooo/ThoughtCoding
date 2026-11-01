#!/usr/bin/env bash
# Run inside Linux/WSL2 after installing the dependencies in docs/shell-sandbox.md.
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
  echo 'FAIL: run the whole acceptance suite inside Linux/WSL2.' >&2
  exit 1
fi
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
for tool in java javac mvn node; do
  command -v "$tool" >/dev/null || { echo "FAIL: missing $tool" >&2; exit 1; }
done
for tool in /usr/bin/bwrap /usr/bin/socat /usr/bin/rg /usr/bin/curl; do
  [[ -x "$tool" ]] || { echo "FAIL: missing $tool" >&2; exit 1; }
done
export THOUGHTCODING_NODE_PATH="${THOUGHTCODING_NODE_PATH:-$(command -v node)}"
export THOUGHTCODING_SRT_PATH="${THOUGHTCODING_SRT_PATH:-$HOME/.local/share/thoughtcoding/srt/node_modules/@anthropic-ai/sandbox-runtime/dist/cli.js}"
[[ -f "$THOUGHTCODING_SRT_PATH" ]] || { echo 'FAIL: install sandbox-runtime@0.0.81 first.' >&2; exit 1; }
export THOUGHTCODING_SANDBOX_INTEGRATION=true
# A fresh report directory prevents stale results being mistaken for this run.
mvn -B clean test -Dtest=SrtSandboxIntegrationTest -DfailIfNoTests=true
echo 'PASS: real SRT acceptance suite completed; reports: target/surefire-reports/'
