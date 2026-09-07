#!/usr/bin/env python3
"""Subscription-only Codex qualification. Synthetic fixture inputs; production Java prompt/schema/compiler.

Never treat turn.completed token totals or sessions as actual provider request counts.
GEPA deliberately fails closed until a before-request budget hook is available.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import time

DISABLED_FEATURES = ("apps", "plugins", "remote_plugin", "memories", "chronicle", "multi_agent",
                     "multi_agent_v2", "shell_tool", "unified_exec", "browser_use",
                     "browser_use_external", "computer_use", "image_generation", "in_app_browser",
                     "hooks", "view_image", "workspace_dependencies", "skill_search", "sleep_tool")


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def bridge(descriptor):
    cfg = json.loads(Path(descriptor).read_text())
    child = subprocess.Popen(cfg["java"] + ["compile", cfg["fixture"]], stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, text=True)
    v2 = cfg.get("contract") == "PACKAGE_DESIGN_V2"
    tool_name = "submit_package_design_v2" if v2 else "submit_package_design"
    schema = json.loads(subprocess.check_output(cfg["java"] + ["schema-v2" if v2 else "schema"], text=True))
    attempts, keys, terminal = [], {}, False
    try:
        for line in sys.stdin:
            request = json.loads(line)
            if "id" not in request:
                continue
            method = request.get("method")
            if method == "initialize":
                result = {"protocolVersion": request.get("params", {}).get("protocolVersion", "2024-11-05"),
                          "capabilities": {"tools": {}}, "serverInfo": {"name": "luna-qualification", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": [{"name": tool_name, "description":
                          "Submit a complete candidate to the production compiler. Follow the returned action.",
                          "annotations": {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": False},
                          "inputSchema": schema}]}
            elif method == "tools/call":
                params = request.get("params", {})
                args = params.get("arguments", {})
                candidate = json.dumps(args.get("candidate"), ensure_ascii=False, sort_keys=True)
                key = args.get("idempotencyKey")
                if params.get("name") != tool_name or not isinstance(key, str) or not key:
                    response = {"outcome": "INVALID_ARGUMENTS", "action": "FIX_AND_RESUBMIT"}
                elif key in keys and keys[key][0] == candidate:
                    response = keys[key][1]
                elif key in keys or args.get("expectedSubmissionRevision") != len(attempts):
                    response = {"outcome": "REVISION_CONFLICT", "submissionRevision": len(attempts)}
                elif args.get("runId") != "qualification":
                    response = {"outcome": "OWNER_MISMATCH", "action": "STOP_AND_WAIT_FOR_INPUT"}
                elif terminal or len(attempts) >= 4:
                    response = {"outcome": "WAITING_INPUT", "action": "STOP_AND_WAIT_FOR_INPUT"}
                elif len(candidate.encode()) > 128 * 1024:
                    response = {"outcome": "INVALID_ARGUMENTS", "action": "FIX_AND_RESUBMIT", "detail": "128 KiB maximum"}
                else:
                    child.stdin.write(candidate + "\n")
                    child.stdin.flush()
                    raw = child.stdout.readline()
                    if not raw:
                        raise RuntimeError("Compiler exited without a response")
                    response = json.loads(raw)
                    compiled = response.pop("compiledResultJson", None)
                    canonical = response.pop("canonicalCandidateJson", None)
                    response.update(attemptOrdinal=len(attempts) + 1, submissionRevision=len(attempts) + 1,
                                    remainingAttempts=3 - len(attempts))
                    terminal = response["action"] != "FIX_AND_RESUBMIT"
                    attempts.append({"candidateSha256": digest(candidate), "candidate": json.loads(candidate),
                                     "canonicalCandidateJson": canonical, "compiledResultJson": compiled,
                                     "response": response})
                    keys[key] = (candidate, response)
                    Path(cfg["ledger"]).write_text(json.dumps(attempts, ensure_ascii=False, indent=2))
                result = {"content": [{"type": "text", "text": json.dumps(response, ensure_ascii=False)}],
                          "isError": response.get("outcome") not in ("ACCEPTED", "REJECTED", "WAITING_INPUT")}
            elif method == "ping":
                result = {}
            else:
                print(json.dumps({"jsonrpc": "2.0", "id": request["id"],
                                  "error": {"code": -32601, "message": "Method not found"}}), flush=True)
                continue
            print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}, ensure_ascii=False), flush=True)
    finally:
        child.stdin.close()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.terminate()
            child.wait(timeout=5)


def isolated_config(directory, descriptor=None):
    home = directory / "codex-config"
    home.mkdir(mode=0o700)
    source = Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))) / "auth.json"
    if not source.is_file():
        raise RuntimeError("ChatGPT file login unavailable; no API/keychain/model fallback is permitted")
    auth = home / "auth.json"
    shutil.copyfile(source, auth)
    auth.chmod(0o600)
    config = ('model = "gpt-5.6-luna"\nmodel_reasoning_effort = "medium"\nforced_login_method = "chatgpt"\n'
              'sandbox_mode = "read-only"\napproval_policy = "never"\nweb_search = "disabled"\n'
              'project_doc_max_bytes = 0\ncli_auth_credentials_store = "file"\n'
              '[features]\nskip_host_skill_discovery = true\n')
    config += "".join(f"{feature} = false\n" for feature in DISABLED_FEATURES)
    if descriptor:
        descriptor_data = json.loads(Path(descriptor).read_text())
        enabled_tool = "submit_package_design_v2" if descriptor_data.get("contract") == "PACKAGE_DESIGN_V2" else "submit_package_design"
        config += ('\n[mcp_servers.qualification]\ncommand = ' + json.dumps(sys.executable) + '\nargs = '
                   + json.dumps([str(Path(__file__).resolve()), "--bridge", str(descriptor)])
                   + '\nenabled_tools = ' + json.dumps([enabled_tool]) + '\nstartup_timeout_sec = 60\ntool_timeout_sec = 120\n')
    (home / "config.toml").write_text(config)
    # No inherited API keys, app thread identity, proxy/provider overrides or personal plugin settings.
    env = {key: value for key, value in os.environ.items() if key in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL")}
    env["CODEX_HOME"] = str(home)
    return env, auth, config


def execute(args, directory, prompt, descriptor=None):
    env, auth, config = isolated_config(directory, descriptor)
    start = time.monotonic()
    child = None
    try:
        login = subprocess.run([args.codex, "login", "status"], env=env, capture_output=True, text=True, timeout=30)
        if login.returncode or "Logged in using ChatGPT" not in login.stdout + login.stderr:
            raise RuntimeError("ChatGPT subscription login not confirmed; no fallback attempted")
        (directory / "effective-config.toml").write_text(config)
        command = [args.codex, "exec", "--strict-config", "--ephemeral", "--ignore-rules", "--skip-git-repo-check",
                   "-C", str(directory / "workspace"), "--json", "-"]
        with (directory / "events.jsonl").open("w") as out, (directory / "stderr.log").open("w") as err:
            child = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=out, stderr=err, text=True,
                                     start_new_session=True)
            try:
                child.communicate(prompt, timeout=args.timeout)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=5)
                raise RuntimeError("Codex evaluation timed out; process group stopped")
        events = [json.loads(line) for line in (directory / "events.jsonl").read_text().splitlines() if line.startswith("{")]
        usage = [event["usage"] for event in events if event.get("type") == "turn.completed"]
        result = {"exitCode": child.returncode, "elapsedSeconds": round(time.monotonic() - start, 3),
                  "turnUsage": usage, "actualModelRequests": None, "requestCountAvailable": False,
                  "configSha256": digest(config),
                  "configTemplateSha256": digest(config.replace(str(directory), "<RUN_DIRECTORY>")),
                  "promptSha256": digest(prompt),
                  "model": "gpt-5.6-luna", "reasoningEffort": "medium", "authMode": "chatgpt"}
        (directory / "run.json").write_text(json.dumps(result, indent=2))
        return result
    finally:
        if child is not None and child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=5)
        auth.unlink(missing_ok=True)


def run(args):
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.gepa:
        report = {"status": "BLOCKED_AT_ADAPTER_VALIDATION", "modelRequestsStarted": 0,
                  "reason": "Codex exec exposes aggregate turn usage, not a verified pre-provider-request reservation hook. "
                            "A 200-session or post-hoc event cap cannot enforce 200 actual model requests."}
        (output / "gepa-feasibility.json").write_text(json.dumps(report, indent=2))
        print(json.dumps(report))
        return 2
    version = subprocess.check_output([args.codex, "--version"], text=True).strip()
    (output / "codex-version.txt").write_text(version + "\n")
    if args.preflight:
        directory = output / "preflight"
        directory.mkdir(exist_ok=False)
        (directory / "workspace").mkdir()
        print(json.dumps(execute(args, directory, "Do not use tools. Reply exactly LUNA_SUBSCRIPTION_OK.")))
        return 0
    if not args.classpath:
        raise ValueError("--classpath is required for evaluation")
    corpus = json.loads(Path(args.corpus).read_text())
    java = [args.java, "-cp", args.classpath, "io.opencode.loopper.service.PackageDesignLunaProbe"]
    selected = [case for case in corpus["cases"] if case["split"] in args.splits.split(",")
                and (not args.cases or case["id"] in args.cases.split(","))]
    for case in selected:
        for repeat in range(args.repeats):
            directory = output / f'{case["id"]}-{repeat + 1}'
            directory.mkdir(exist_ok=False)
            workspace = directory / "workspace"
            workspace.mkdir()
            target = workspace / case["target"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(case["repositoryFixture"])
            fixture = directory / "fixture.json"
            fixture_data = {key: case[key] for key in ("id", "requirement", "technology", "target", "symbol")}
            if args.contract == "PACKAGE_DESIGN_V2":
                fixture_data.update(contractVersion=args.contract, projectRoot=str(workspace))
            fixture.write_text(json.dumps(fixture_data, ensure_ascii=False))
            descriptor = directory / "bridge.json"
            descriptor.write_text(json.dumps({"java": java, "fixture": str(fixture), "ledger": str(directory / "attempts.json"), "contract": args.contract}))
            prompt = subprocess.check_output(java + ["prompt", str(fixture), str(workspace)], text=True)
            prompt += "\nFrozen bounded repository evidence (synthetic fixture, not production implementation):\n" + case["repositoryFixture"]
            (directory / "prompt.txt").write_text(prompt)
            before = {str(path.relative_to(workspace)): digest(path.read_text()) for path in workspace.rglob("*") if path.is_file()}
            result = execute(args, directory, prompt, descriptor)
            after = {str(path.relative_to(workspace)): digest(path.read_text()) for path in workspace.rglob("*") if path.is_file()}
            result.update(caseId=case["id"], contract=args.contract, semanticPreparationTurns=0, repeat=repeat + 1, fixtureUnchanged=before == after,
                          submitted=(directory / "attempts.json").exists(),
                          semanticReview="PENDING_INDEPENDENT_CHECKLIST", corpusSha256=digest(Path(args.corpus).read_text()))
            (directory / "run.json").write_text(json.dumps(result, indent=2))
            print(json.dumps(result), flush=True)
            if result["exitCode"] or not result["fixtureUnchanged"]:
                raise RuntimeError("Evaluation failed; stopping without provider/model/credit fallback")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge")
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--java", default="java")
    parser.add_argument("--classpath")
    parser.add_argument("--contract", choices=["PACKAGE_DESIGN_V1", "PACKAGE_DESIGN_V2"], default="PACKAGE_DESIGN_V1")
    parser.add_argument("--corpus", default="src/test/resources/package-design-luna/corpus.json")
    parser.add_argument("--output")
    parser.add_argument("--splits", default="train,dev,heldout")
    parser.add_argument("--cases")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--gepa", action="store_true")
    args = parser.parse_args()
    if args.bridge:
        bridge(args.bridge)
        return 0
    if not args.output:
        parser.error("--output is required")
    return run(args)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    sys.exit(main())
