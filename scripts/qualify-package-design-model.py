#!/usr/bin/env python3
"""Read-only model/MCP compiler qualification. Requires compiled PackageDesignModelProbe test classes.

This adapter exercises the production compiler, not the application's HTTP lifecycle or writer settlement.
Each run has an isolated OpenCode database/config and a four-submission evaluation budget.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time


def bridge(descriptor):
    cfg = json.loads(Path(descriptor).read_text())
    process = subprocess.Popen(cfg["java"] + [cfg["requirement"]], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, text=True)
    schema = json.loads(subprocess.check_output(cfg["java"] + ["schema"], text=True))
    attempts, keys = [], {}
    try:
        for line in sys.stdin:
            request = json.loads(line)
            if "id" not in request:
                continue
            method = request["method"]
            if method == "initialize":
                result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                          "serverInfo": {"name": "package-compiler-qualification", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": [{"name": "submit_package_design", "description":
                          "Compile one complete package candidate. Repair REJECTED diagnostics in this session.",
                          "inputSchema": schema}]}
            elif method == "tools/call":
                params = request["params"]
                if params.get("name") != "submit_package_design":
                    raise ValueError("Unexpected tool")
                args = params["arguments"]
                candidate = json.dumps(args["candidate"], ensure_ascii=False, sort_keys=True)
                key = args["idempotencyKey"]
                if key in keys and keys[key][0] == candidate:
                    response = keys[key][1]
                elif key in keys or args.get("expectedSubmissionRevision") != len(attempts):
                    response = {"outcome": "REVISION_CONFLICT", "submissionRevision": len(attempts)}
                elif args.get("runId") != cfg["runId"] or len(attempts) >= 4:
                    response = {"outcome": "WAITING_INPUT", "action": "STOP_AND_WAIT_FOR_INPUT"}
                else:
                    process.stdin.write(candidate + "\n")
                    process.stdin.flush()
                    response = json.loads(process.stdout.readline())
                    response.update(attemptOrdinal=len(attempts) + 1, submissionRevision=len(attempts) + 1,
                                    remainingAttempts=3 - len(attempts))
                    attempts.append({"candidateSha256": hashlib.sha256(candidate.encode()).hexdigest(),
                                     "response": response})
                    keys[key] = (candidate, response)
                    Path(cfg["ledger"]).write_text(json.dumps(attempts, ensure_ascii=False, indent=2))
                result = {"content": [{"type": "text", "text": json.dumps(response, ensure_ascii=False)}],
                          "isError": response.get("outcome") != "ACCEPTED"}
            elif method == "ping":
                result = {}
            else:
                print(json.dumps({"jsonrpc": "2.0", "id": request["id"],
                                  "error": {"code": -32601, "message": "Method not found"}}), flush=True)
                continue
            print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}, ensure_ascii=False), flush=True)
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=5)


CASES = {
    "normal": "为 EventBus 的未注册事件补充单元测试：publish 正常返回，不调用任何处理器，保持已注册事件行为。",
    "duplicate_titles": "为 EventBus 补充两个不同场景的测试：从未注册事件，以及注册后移除全部处理器。两种 publish 都正常返回且无处理器调用。两条场景标题都必须是‘未注册事件被安全忽略’，用不同 key 和前置条件区分，保持已注册事件行为。",
    "heldout_empty": "为 EventBus 空处理器列表补充测试：连续发布两次仍正常返回，调用计数保持零，不改变其他事件注册关系。场景标题必须包含反引号和分号，例如 `空列表`；重复发布。",
    "repair_reference": "为 EventBus 的未注册事件补充单元测试：publish 正常返回且不调用处理器，既有分发行为不变。资格测试要求仅第一次提交时，在 stage.includes 额外加入不存在的 SC-MISSING；收到真实报错后自行修正。",
}


def fixture_fingerprint(directory):
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in directory.rglob("*") if path.is_file()}


def provider_tokens(events):
    totals = {"input": 0, "output": 0, "reasoning": 0, "cacheRead": 0, "cacheWrite": 0}
    available = False
    for line in events.read_text().splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        tokens = event.get("part", {}).get("tokens")
        if event.get("type") != "step_finish" or not isinstance(tokens, dict):
            continue
        available = True
        for key in ["input", "output", "reasoning"]:
            totals[key] += tokens.get(key, 0)
        totals["cacheRead"] += tokens.get("cache", {}).get("read", 0)
        totals["cacheWrite"] += tokens.get("cache", {}).get("write", 0)
    return totals if available else None


def run(args):
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    java = [args.java, "-cp", args.classpath, "io.opencode.loopper.service.PackageDesignModelProbe"]
    results = []
    for case in args.cases.split(","):
        for repeat in range(args.repeats):
            directory = output / f"{case}-{repeat + 1}"
            directory.mkdir(exist_ok=False)
            fixture = directory / "fixture"
            target = fixture / "src/test/java/example/EventBusTest.java"
            target.parent.mkdir(parents=True)
            target.write_text("package example;\n// Existing repository-native JUnit test target.\nclass EventBusTest {}\n")
            before = fixture_fingerprint(fixture)
            requirement = directory / "requirement.txt"
            requirement.write_text(CASES[case])
            descriptor = directory / "bridge.json"
            descriptor.write_text(json.dumps({"java": java, "requirement": str(requirement),
                                             "runId": "qualification", "ledger": str(directory / "attempts.json")}))
            env = dict(os.environ)
            for name, child in [("XDG_CONFIG_HOME", "config"), ("XDG_DATA_HOME", "data"),
                                ("XDG_STATE_HOME", "state"), ("XDG_CACHE_HOME", "cache")]:
                env[name] = str(directory / child)
                Path(env[name]).mkdir()
            # Reuse only the user's existing authentication; never log or include it in evidence.
            auth = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share"))) / "opencode/auth.json"
            copied_auth = Path(env["XDG_DATA_HOME"]) / "opencode/auth.json"
            if auth.exists():
                copied_auth.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(auth, copied_auth)
                copied_auth.chmod(0o600)
            config = {"model": args.model, "share": "disabled", "permission": {"*": "deny",
                      "probe_submit_package_design": "allow", "read": "allow", "glob": "allow", "grep": "allow"},
                      "agent": {"qualification": {"mode": "primary", "temperature": 0, "steps": 14,
                                "prompt": "You are a read-only package designer. Follow the supplied frozen contract and MCP feedback."}},
                      "mcp": {"probe": {"type": "local", "command": [sys.executable, str(Path(__file__).resolve()),
                                "--bridge", str(descriptor)], "enabled": True}}}
            env["OPENCODE_CONFIG_CONTENT"] = json.dumps(config)
            prompt = (CASES[case] + "\n只设计，不修改文件或执行命令。Java REQUIRED；唯一允许的交付路径是 "
                      "src/test/java/example/EventBusTest.java，需声明新增 EventBusTest 聚焦测试及具体业务结果。"
                      "使用一个阶段，并保持所有需求覆盖。使用 probe_submit_package_design 提交完整 PACKAGE_DESIGN_V1。"
                      "runId=qualification，初始 expectedSubmissionRevision=0，每次使用新 idempotencyKey。"
                      "最多四次提交；按报错修正，ACCEPTED 或 NEEDS_INPUT 后停止。reviews 无主观项时为空。"
                      "模型只提供语义字段，不能提供命令、权限、测试命令或稳定服务端 ID。")
            start = time.monotonic()
            timed_out = False
            try:
                with (directory / "events.jsonl").open("w") as stdout, (directory / "stderr.log").open("w") as stderr:
                    child = subprocess.Popen([args.opencode, "run", "--pure", "--format", "json", "--agent", "qualification",
                                              "--model", args.model, "--dir", str(fixture), prompt],
                                             env=env, stdout=stdout, stderr=stderr, start_new_session=True)
                    try:
                        code = child.wait(timeout=args.timeout)
                    except subprocess.TimeoutExpired:
                        timed_out = True
                        os.killpg(child.pid, 15)
                        try:
                            code = child.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            os.killpg(child.pid, 9)
                            code = child.wait(timeout=5)
            finally:
                copied_auth.unlink(missing_ok=True)
            ledger = directory / "attempts.json"
            attempts = json.loads(ledger.read_text()) if ledger.exists() else []
            outcomes = [item["response"]["outcome"] for item in attempts]
            result = {"case": case, "repeat": repeat + 1, "model": args.model, "temperature": 0,
                      "evaluationBudget": 4, "outcomes": outcomes, "seconds": round(time.monotonic() - start, 2),
                      "exitCode": code, "timedOut": timed_out, "fixtureUnchanged": before == fixture_fingerprint(fixture),
                      "providerStepTokens": provider_tokens(directory / "events.jsonl"),
                      "injectedFault": case == "repair_reference", "heldOut": case == "heldout_empty"}
            results.append(result)
            (output / "summary.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
            print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge")
    parser.add_argument("--classpath")
    parser.add_argument("--output")
    parser.add_argument("--java", default="java")
    parser.add_argument("--opencode", default="opencode")
    parser.add_argument("--model", default="opencode/gpt-5.4")
    parser.add_argument("--cases", default=",".join(CASES))
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=240)
    options = parser.parse_args()
    bridge(options.bridge) if options.bridge else run(options)
