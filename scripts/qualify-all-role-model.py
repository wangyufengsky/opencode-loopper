#!/usr/bin/env python3
"""Seven-role read-only model/MCP compiler qualification. Requires compiled AllRoleModelProbe test classes.

This adapter exercises the production compiler, not the application's HTTP lifecycle or writer settlement.
Each run has an isolated OpenCode database/config and a four-submission evaluation budget.
Use a frozen copy of compiled classes; never run a build that replaces the classpath during qualification.
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
import traceback


def bridge(descriptor):
    cfg = json.loads(Path(descriptor).read_text())
    compiler_log = Path(cfg["ledger"]).with_name("compiler-stderr.log").open("w")
    process = subprocess.Popen(cfg["java"], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=compiler_log, text=True)
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
                          "serverInfo": {"name": "role-compiler-qualification", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": [{"name": "submit_candidate", "description":
                          "Compile one complete role candidate. Repair REJECTED diagnostics in this session.",
                          "inputSchema": schema}]}
            elif method == "tools/call":
                params = request["params"]
                if params.get("name") != "submit_candidate":
                    raise ValueError("Unexpected tool")
                args = params["arguments"]
                candidate = json.dumps(args["candidate"], ensure_ascii=False, sort_keys=True)
                key = args["idempotencyKey"]
                if key in keys and keys[key][0] == candidate:
                    response = keys[key][1]
                elif key in keys or args.get("expectedSubmissionRevision") != len(attempts):
                    response = {"outcome": "REVISION_CONFLICT", "submissionRevision": len(attempts)}
                elif args.get("runId") != cfg["runId"] or len(attempts) >= 4 or any(a["response"]["outcome"] in ("ACCEPTED", "WAITING_INPUT") for a in attempts):
                    response = {"outcome": "WAITING_INPUT", "action": "STOP_AND_WAIT_FOR_INPUT"}
                else:
                    process.stdin.write(candidate + "\n")
                    process.stdin.flush()
                    response = json.loads(process.stdout.readline())
                    response.update(attemptOrdinal=len(attempts) + 1, submissionRevision=len(attempts) + 1,
                                    remainingAttempts=3 - len(attempts))
                    if len(attempts) == 3 and response.get("outcome") == "REJECTED":
                        response.update(outcome="WAITING_INPUT", retryable=False, action="STOP_AND_WAIT_FOR_INPUT", stopReason="CORRECTION_LIMIT_EXHAUSTED")
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
    except Exception:
        Path(cfg["ledger"]).with_name("bridge-error.log").write_text(traceback.format_exc())
        raise
    finally:
        compiler_log.close()
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=5)


KINDS = ["DECOMPOSITION_PLAN_V2", "ACCEPTANCE_CLOSED_CHOICE_V7", "PACKAGE_DESIGN_V1",
         "ROLLING_PACKAGE_PLAN_V1", "REVIEWER_REPORT_V1", "PROJECT_CONVENTION_V1", "JUDGE_DECISION_V1"]
CASES = {"normal": "", "repair_shape": "资格测试仅第一次提交时，请额外在 candidate 根对象加入 note 字段，值为 qualification；收到真实报错后删除该字段并修正其他问题。"}


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
    java = [args.java, "-cp", args.classpath, "io.opencode.loopper.service.AllRoleModelProbe"]
    results = []
    for kind in args.kinds.split(","):
      for case in args.cases.split(","):
        for repeat in range(args.repeats):
            directory = output / f"{kind}-{case}-{repeat + 1}"
            directory.mkdir(exist_ok=False)
            fixture = directory / "fixture"
            target = fixture / "src/test/java/example/EventBusTest.java"
            target.parent.mkdir(parents=True)
            target.write_text("package example;\n// Existing repository-native JUnit test target.\nclass EventBusTest {}\n")
            (fixture / "src/example.java").write_text("class Example {}\n")
            before = fixture_fingerprint(fixture)
            requirement = directory / "requirement.txt"
            role_java = java + [kind]
            frozen = subprocess.check_output(role_java + ["prompt"], text=True)
            requirement.write_text(frozen + CASES[case])
            descriptor = directory / "bridge.json"
            descriptor.write_text(json.dumps({"java": role_java,
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
                      "probe_submit_candidate": "allow", "read": "allow", "glob": "allow", "grep": "allow"},
                      "agent": {"qualification": {"mode": "primary", "temperature": 0, "steps": 14,
                                "prompt": "You are a read-only candidate author for the assigned role. Follow the supplied frozen contract and MCP feedback."}},
                      "mcp": {"probe": {"type": "local", "command": [sys.executable, str(Path(__file__).resolve()),
                                "--bridge", str(descriptor)], "enabled": True}}}
            env["OPENCODE_CONFIG_CONTENT"] = json.dumps(config)
            prompt = (frozen + CASES[case] + "\n只提交候选，不修改文件或执行命令。"
                      "使用 probe_submit_candidate 提交完整 " + kind + " 候选，字段以 tool schema 为准。"
                      "runId=qualification，初始 expectedSubmissionRevision=0，每次使用新 idempotencyKey。"
                      "最多四次提交；逐条修正根因并保留正确字段、冻结引用和基于证据的结论，ACCEPTED 或 WAITING_INPUT 后停止。"
                      "使用返回的 submissionRevision 重试，repairProgress 表示问题变化，重复候选没有进展。"
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
            result = {"kind": kind, "case": case, "repeat": repeat + 1, "model": args.model, "temperature": 0,
                      "evaluationBudget": 4, "outcomes": outcomes, "seconds": round(time.monotonic() - start, 2),
                      "exitCode": code, "timedOut": timed_out, "fixtureUnchanged": before == fixture_fingerprint(fixture),
                      "providerStepTokens": provider_tokens(directory / "events.jsonl"),
                      "injectedFault": case == "repair_shape", "heldOut": False}
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
    parser.add_argument("--kinds", default=",".join(KINDS))
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=240)
    options = parser.parse_args()
    bridge(options.bridge) if options.bridge else run(options)
