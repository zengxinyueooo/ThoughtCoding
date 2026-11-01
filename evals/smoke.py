"""Check native tool-call round trips without logging credentials."""
import json
from pathlib import Path
import urllib.request
import yaml

ROOT = Path(__file__).resolve().parents[1]


def model_config():
    config = yaml.safe_load((ROOT / "config.yaml").read_text(encoding="utf-8-sig"))
    model = config["models"][config["defaultModel"]]
    if not model.get("apiKey") or "REPLACE_" in model["apiKey"]:
        raise ValueError("Fill in config.yaml locally first")
    return config, model


def request(model, payload):
    req = urllib.request.Request(model["baseURL"].rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode(), headers={"Authorization": "Bearer " + model["apiKey"],
        "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.load(response)


def main():
    _, model = model_config()
    messages = [{"role": "user", "content": "Call eval_echo with value 42, then report its returned value."}]
    tool = {"type": "function", "function": {"name": "eval_echo", "description": "Return the given integer.",
        "parameters": {"type": "object", "properties": {"value": {"type": "integer"}}, "required": ["value"]}}}
    first = request(model, {"model": model["name"], "messages": messages, "tools": [tool],
        "tool_choice": {"type": "function", "function": {"name": "eval_echo"}}, "max_tokens": 128, "temperature": 0})
    assistant = first["choices"][0]["message"]
    calls = assistant.get("tool_calls", [])
    assert len(calls) == 1 and calls[0]["function"]["name"] == "eval_echo", "No valid tool call returned"
    assert json.loads(calls[0]["function"]["arguments"]) == {"value": 42}, "Unexpected tool arguments"
    messages.extend([assistant, {"role": "tool", "tool_call_id": calls[0]["id"], "content": "42"}])
    second = request(model, {"model": model["name"], "messages": messages, "max_tokens": 128, "temperature": 0})
    assert "42" in (second["choices"][0]["message"].get("content") or ""), "Tool result was not consumed"
    result = {"model": model["name"], "native_tool_round_trip": True,
              "usage": [first.get("usage"), second.get("usage")], "agent_task_runs": 0}
    out = ROOT / "evals/results/model-smoke.json"
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
