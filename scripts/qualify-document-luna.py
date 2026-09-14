#!/usr/bin/env python3
"""One synthetic DOCX, Luna medium subscription, independent extraction/review via a bounded MCP bridge.
Production Java parser/prompt/schema/validator, consolidated source scope; not HTTP/DB lifecycle acceptance.
Requires a compiled DocumentRequirementLunaProbe on --classpath. Never installs or purchases anything.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace


def sha(data):
    return hashlib.sha256(data).hexdigest()


def object_schema(properties):
    return dict(type="object", properties=properties, required=list(properties), additionalProperties=False)


def source_tools():
    text = {"type": "string", "maxLength": 1024}
    integer = {"type": "integer", "minimum": -1, "maximum": 2048}
    fields = {"list_requirement_documents": {"runId": text},
              "list_document_sections": {"runId": text, "fileId": text, "after": integer,
                                          "limit": {"type": "integer", "minimum": 1, "maximum": 100}},
              "read_document_section": {"runId": text, "fileId": text, "section": integer, "expectedSha256": text}}
    return [{"name": name, "description": "Read only the frozen synthetic DOCX; never external resources.",
             "annotations": {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": False},
             "inputSchema": object_schema(props)} for name, props in fields.items()]


def validate_shape(value, schema):
    """Closed subset used by production candidate schemas; semantic validation remains Java-owned."""
    types = schema.get("type", [])
    types = [types] if isinstance(types, str) else types
    actual = "null" if value is None else "boolean" if isinstance(value, bool) else "integer" if isinstance(value, int) else "string" if isinstance(value, str) else "array" if isinstance(value, list) else "object" if isinstance(value, dict) else "unsupported"
    if actual not in types or ("enum" in schema and value not in schema["enum"]):
        raise ValueError("JSON 类型或枚举不符合工具合同")
    if actual == "object":
        if set(value) != set(schema["required"]):
            raise ValueError("JSON 必填字段缺失或含额外字段")
        for k, v in value.items():
            validate_shape(v, schema["properties"][k])
    elif actual == "array":
        if len(value) > schema.get("maxItems", 2048):
            raise ValueError("JSON 清单超限")
        for v in value:
            validate_shape(v, schema["items"])
    elif actual == "string":
        if not schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", 2_000_000):
            raise ValueError("JSON 文本长度无效")
    elif actual == "integer" and not schema.get("minimum", -1) <= value <= schema.get("maximum", 2**63):
        raise ValueError("JSON 数值超限")


def bridge(path):
    cfg = json.loads(Path(path).read_text())
    data = json.loads(Path(cfg["input"]).read_text())
    sources = {s["section"]: s for s in data["sources"]}
    tool = {"name": data["toolName"], "description": "Submit complete candidate to the production source/coverage validator. ACCEPTED is structural validation only.", "inputSchema": data["schema"],
            "annotations": {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": False}}
    tools = {t["name"]: t for t in source_tools() + [tool]}
    ledger, reads, keys, revision, terminal = [], set(), {}, 0, False
    child = subprocess.Popen(cfg["validate"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        for line in sys.stdin:
            request = json.loads(line)
            if "id" not in request:
                continue
            method = request["method"]
            if method == "initialize":
                result = {"protocolVersion": request.get("params", {}).get("protocolVersion", "2024-11-05"), "capabilities": {"tools": {}}, "serverInfo": {"name": "document-luna-qualification", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": list(tools.values())}
            elif method == "tools/call":
                args, name = request["params"].get("arguments", {}), request["params"]["name"]
                error = False
                try:
                    if len(ledger) >= 200 or name not in tools:
                        raise ValueError("工具未授权或本轮工具预算耗尽")
                    validate_shape(args, tools[name]["inputSchema"])
                    if args["runId"] != data["runId"] or args.get("fileId", "azx0-document") != "azx0-document":
                        raise ValueError("冻结来源身份不匹配")
                    if name == "list_requirement_documents":
                        value = {"documents": [{"fileId": "azx0-document", "name": "AZX0模拟需规.docx", "sections": len(sources), "limitations": data["limitations"]}]}
                    elif name == "list_document_sections":
                        page = [s for n, s in sources.items() if n > args["after"]][:args["limit"]]
                        value = {"items": [{k: v for k, v in s.items() if k != "text"} for s in page], "hasMore": bool(page and page[-1]["section"] < max(sources))}
                    elif name == "read_document_section":
                        value = sources.get(args["section"])
                        if value is None or value["sha256"] != args["expectedSha256"]:
                            raise ValueError("冻结分段或哈希不匹配")
                        reads.add(args["section"])
                    else:
                        key = args["idempotencyKey"]
                        digest = sha(json.dumps(args, ensure_ascii=False, sort_keys=True).encode())
                        if key in keys:
                            if keys[key][0] != digest:
                                raise ValueError("幂等键内容冲突")
                            value = keys[key][1]
                        else:
                            if terminal or revision >= 4 or args["expectedSubmissionRevision"] != revision:
                                raise ValueError("提交已结束、超出四次候选预算或版本冲突")
                            if reads != set(sources):
                                raise ValueError("请逐一读取全部冻结分段后再提交")
                            child.stdin.write(json.dumps(args["candidate"], ensure_ascii=False) + "\n"); child.stdin.flush()
                            value = json.loads(child.stdout.readline()); revision += 1
                            value["submissionRevision"] = revision
                            keys[key] = (digest, value)
                            if value["outcome"] == "ACCEPTED":
                                Path(cfg["accepted"]).write_text(json.dumps(args["candidate"], ensure_ascii=False, indent=2))
                                terminal = True
                    ledger.append({"tool": name, "arguments": args, "result": value})
                except (ValueError, KeyError) as exc:
                    error = True; value = {"error": str(exc)}
                    ledger.append({"tool": name, "error": value})
                Path(cfg["ledger"]).write_text(json.dumps(ledger, ensure_ascii=False, indent=2))
                result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}], "isError": error}
            else:
                print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32601, "message": "Unsupported method"}}), flush=True)
                continue
            print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)
    finally:
        child.stdin.close()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill(); child.wait(timeout=5)


def run(args):
    output = Path(args.output).resolve(); output.mkdir(parents=True, exist_ok=False)
    sample = output / "sample.docx"; sample.write_bytes(Path(args.document).read_bytes())
    spec = importlib.util.spec_from_file_location("luna_runner", Path(__file__).with_name("qualify-package-design-luna.py"))
    runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
    original_config = runner.isolated_config
    java = [args.java, "-cp", args.classpath, "DocumentRequirementLunaProbe"]
    manifest = {"model": "gpt-5.6-luna", "effort": "medium", "sampleSha256": sha(sample.read_bytes()),
                "codexVersion": subprocess.check_output([args.codex, "--version"], text=True).strip(),
                "scope": "consolidated source effect probe, not production batching/HTTP/DB lifecycle",
                "budget": {"sessions": 2, "secondsPerSession": 600, "candidatesPerSession": 4, "toolCallsPerSession": 200}, "runs": []}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2))
    for role in ["extraction", "review"]:
        directory = output / role; directory.mkdir(); (directory / "workspace").mkdir()
        candidate = output / "extraction/accepted.json"
        command = java + ["prepare", str(sample), role, str(candidate)]
        data = json.loads(subprocess.check_output(command, text=True))
        input_file = directory / "input.json"; input_file.write_text(json.dumps(data, ensure_ascii=False, indent=2))
        descriptor = directory / "bridge.json"
        descriptor.write_text(json.dumps({"input": str(input_file), "validate": java + ["validate", str(sample), role, str(candidate)],
                                          "accepted": str(directory / "accepted.json"), "ledger": str(directory / "tools.json")}))
        def config_override(run_dir, unused=None):
            env, auth, config = original_config(run_dir)
            config += '\n[mcp_servers.qualification]\ncommand = ' + json.dumps(sys.executable) + '\nargs = ' + json.dumps([str(Path(__file__).resolve()), "--bridge", str(descriptor)]) + '\nstartup_timeout_sec = 60\ntool_timeout_sec = 120\n'
            (Path(env["CODEX_HOME"]) / "config.toml").write_text(config)
            return env, auth, config
        runner.isolated_config = config_override
        prompt = data["prompt"]
        (directory / "prompt.txt").write_text(prompt)
        print("START " + role, flush=True)
        result = runner.execute(SimpleNamespace(codex=args.codex, timeout=600), directory, prompt)
        result["accepted"] = (directory / "accepted.json").exists()
        result["workspaceUnchanged"] = not any((directory / "workspace").iterdir())
        manifest["runs"].append({"role": role, **result})
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2))
        print(json.dumps({"role": role, **result}), flush=True)
        if not result["accepted"] or not result["workspaceUnchanged"]:
            raise RuntimeError("No validated candidate or workspace changed; no automatic retry")
    if sha(sample.read_bytes()) != manifest["sampleSha256"]:
        raise RuntimeError("Frozen sample changed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge")
    parser.add_argument("--document")
    parser.add_argument("--output")
    parser.add_argument("--classpath")
    parser.add_argument("--java", default="java")
    parser.add_argument("--codex", default="codex")
    args = parser.parse_args()
    if args.bridge:
        bridge(args.bridge)
    else:
        if not all([args.document, args.output, args.classpath]):
            parser.error("--document, --output and --classpath required")
        run(args)
