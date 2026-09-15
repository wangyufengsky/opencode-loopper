#!/usr/bin/env python3
"""Bounded Luna direct document/code effect qualification; production Java contracts, isolated synthetic receipts.
Does not substitute for production HTTP/database lifecycle or actual test execution evidence.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value

base = module("document_probe", "qualify-document-luna.py")


def tool_specs(data):
    text = {"type": "string", "maxLength": 1024}
    number = lambda lo, hi: {"type": "integer", "minimum": lo, "maximum": hi}
    fields = {
        "describe_submission_contract": dict(runId=text, pointer={"type": "string", "maxLength": 512}),
        "get_document_review_work": dict(runId=text, offset=number(0, 100000), limit=number(1, 100)),
        "check_document_review_candidate": dict(runId=text, candidate=data["schema"]["properties"]["candidate"]),
        "list_requirement_code": dict(runId=text, query=text, after=text, limit=number(1, 100)),
        "read_requirement_code": dict(runId=text, path=text, blobSha=text, startLine=number(1, 10000000), limit=number(1, 200)),
        "search_requirement_code": dict(runId=text, path=text, blobSha=text, query=text, afterLine=number(0, 10000000)),
        "list_requirement_assessments": dict(runId=text, after=number(-1, 100000), limit=number(1, 100)),
        "read_requirement_assessment": dict(runId=text, ordinal=number(0, 100000), expectedSha256=text),
        "read_document_resource": dict(uri={"type": "string", "maxLength": 2048}),
    }
    specs = base.source_tools() + [dict(name=name, description="Read only this frozen synthetic qualification scope.",
        inputSchema=base.object_schema(props), annotations=dict(readOnlyHint=True, destructiveHint=False, openWorldHint=False)) for name, props in fields.items()]
    specs.append(dict(name=data["toolName"], description="Submit a complete candidate to the production source/code validator. Acceptance is structural, not a semantic pass.",
        inputSchema=data["schema"], annotations=dict(readOnlyHint=True, destructiveHint=False, openWorldHint=False)))
    return {s["name"]: s for s in specs}


def bridge(path):
    cfg = json.loads(Path(path).read_text()); data = json.loads(Path(cfg["input"]).read_text())
    sources = {s["section"]: s for s in data["sources"]}; code = {c["path"]: c for c in data["code"]}
    specs = tool_specs(data); ledger, reads, code_reads, keys = [], set(), [], {}
    revision, terminal = 0, False
    candidate_hash = base.sha(json.dumps(data["assessment"], ensure_ascii=False, sort_keys=True).encode())
    child = subprocess.Popen(cfg["validate"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        for line in sys.stdin:
            request = json.loads(line)
            if "id" not in request: continue
            method = request["method"]
            if method == "initialize":
                result = dict(protocolVersion=request.get("params", {}).get("protocolVersion", "2024-11-05"), capabilities={"tools": {}}, serverInfo=dict(name="direct-document-qualification", version="2"))
            elif method == "tools/list": result = {"tools": list(specs.values())}
            elif method == "tools/call":
                args, name = request["params"].get("arguments", {}), request["params"]["name"]; error = False
                try:
                    if len(ledger) >= 200 or name not in specs: raise ValueError("工具未授权或本轮工具预算耗尽")
                    base.validate_shape(args, specs[name]["inputSchema"])
                    if name != "read_document_resource" and args["runId"] != data["runId"]: raise ValueError("冻结运行身份不匹配")
                    if args.get("fileId", "azx0-document") != "azx0-document": raise ValueError("冻结文档身份不匹配")
                    if name == "describe_submission_contract":
                        selected = data["schema"]
                        if args["pointer"]:
                            if not args["pointer"].startswith("/"): raise ValueError("参数路径必须为 JSON Pointer")
                            for key in args["pointer"][1:].split("/"):
                                key = key.replace("~1", "/").replace("~0", "~")
                                selected = selected[int(key)] if isinstance(selected, list) else selected[key]
                        value = dict(contract=dict(toolName=data["toolName"], expectedSubmissionRevision=revision), inputSchema=selected,
                            guidance=["issues 仅写业务待澄清；代码证据缺口放 assessment.limitations。", "snapshotSha 填 null，由服务端绑定。"])
                    elif name == "get_document_review_work":
                        page = list(sources.values())[args["offset"]:args["offset"]+args["limit"]]
                        entries = (data["assessment"] or {}).get("entries", [])
                        value = dict(batchOrdinal=1, sourceRevision=1, assignedSectionTotal=len(sources),
                            sections=[dict(position=x["section"], fileId=x["fileId"], section=x["section"], title=x["title"], sha256=x["sha256"], alreadyRead=x["section"] in reads) for x in page],
                            nextOffset=args["offset"]+len(page) if args["offset"]+len(page)<len(sources) else -1,
                            existingRequirements=[dict(key=x["assessment"]["requirementKey"], title=x["title"], sources=x["sources"]) for x in entries[args["offset"]:args["offset"]+args["limit"]]],
                            requirementTotal=len(entries), instruction="章节目录不是已编译需求。每段必须在 entries.sources 或 skippedSections 有交代；只在读取后判断。")
                    elif name == "check_document_review_candidate":
                        submitted = dict(candidate=None if data["assessment"] is not None else args["candidate"], review=args["candidate"] if data["assessment"] is not None else None,
                                         sourceReads=sorted(reads), codeReads=code_reads)
                        child.stdin.write(json.dumps(submitted, ensure_ascii=False)+"\n"); child.stdin.flush()
                        checked = json.loads(child.stdout.readline())
                        value = dict(valid=checked["outcome"] == "ACCEPTED", accepted=False,
                            detail=checked.get("detail", "预检通过，必须正式提交"), expectedSubmissionRevision=revision)
                    elif name == "list_requirement_documents":
                        value = {"documents": [dict(fileId="azx0-document", name="AZX0模拟需规.docx", sections=len(sources), limitations=data["limitations"])]}
                    elif name == "list_document_sections":
                        page = [s for n, s in sources.items() if n > args["after"]][:args["limit"]]
                        value = dict(items=[{k: v for k, v in s.items() if k != "text"} for s in page], nextCursor=page[-1]["section"] if page and page[-1]["section"] < max(sources) else None)
                    elif name == "read_document_section":
                        value = sources.get(args["section"])
                        if value is None or value["sha256"] != args["expectedSha256"]: raise ValueError("冻结分段或哈希不匹配")
                        reads.add(args["section"])
                    elif name == "read_document_resource":
                        prefix = "loopper-document://review/" + data["runId"] + "/"
                        if not args["uri"].startswith(prefix): raise ValueError("原文资源不属于当前角色")
                        suffix = args["uri"][len(prefix):]
                        if suffix == "index/0": value = dict(documents=[dict(fileId="azx0-document", sections=len(sources))])
                        elif suffix == "azx0-document/index0": value = dict(items=[{k:v for k,v in s.items() if k != "text"} for s in sources.values()], nextCursor=None)
                        elif suffix.startswith("azx0-document/") and suffix.split("/")[-1].isdigit() and int(suffix.split("/")[-1]) in sources:
                            n = int(suffix.split("/")[-1]); reads.add(n); value = sources[n]
                        else: raise ValueError("原文资源位置无效")
                    elif name == "list_requirement_code":
                        files = [c for p, c in code.items() if p > args["after"] and args["query"].lower() in p.lower()]
                        page = files[:args["limit"]]
                        value = dict(items=[{k:v for k,v in c.items() if k != "content"} for c in page], nextCursor=page[-1]["path"] if len(files)>len(page) else None)
                    elif name in ("read_requirement_code", "search_requirement_code"):
                        c = code.get(args["path"])
                        if c is None or c["blobSha"] != args["blobSha"]: raise ValueError("冻结代码身份不匹配")
                        lines = c["content"].split("\n")
                        if name == "read_requirement_code":
                            start = args["startLine"]; end = min(len(lines), start + args["limit"] - 1)
                            if start > end: raise ValueError("代码读取起始位置超出文件")
                            content = "\n".join(lines[start-1:end]); code_reads.append(dict(path=c["path"], startLine=start, endLine=end))
                            value = dict(path=c["path"], blobSha=c["blobSha"], startLine=start, endLine=end, content=content, sha256=base.sha(content.encode()), hasMore=end<len(lines))
                        else:
                            if not args["query"]: raise ValueError("检索文本不能为空")
                            hits = [dict(line=n+1, text=line) for n,line in enumerate(lines) if n+1 > args["afterLine"] and args["query"] in line]
                            value = dict(items=hits[:100], hasMore=len(hits)>100)
                    elif name == "list_requirement_assessments":
                        value = dict(items=[dict(ordinal=0, sha256=candidate_hash)] if data["assessment"] is not None and args["after"] < 0 else [], nextCursor=None)
                    elif name == "read_requirement_assessment":
                        if data["assessment"] is None or args["ordinal"] != 0 or args["expectedSha256"] != candidate_hash: raise ValueError("评审结果身份不匹配")
                        value = data["assessment"]
                    else:
                        key = args["idempotencyKey"]; digest = base.sha(json.dumps(args, ensure_ascii=False, sort_keys=True).encode())
                        if key in keys:
                            if keys[key][0] != digest: raise ValueError("幂等键内容冲突")
                            value = keys[key][1]
                        else:
                            if terminal or revision >= 4 or args["expectedSubmissionRevision"] != revision: raise ValueError("提交结束、候选预算耗尽或版本冲突")
                            if reads != set(sources): raise ValueError("请实际读取本批所有原文章节")
                            submitted = dict(candidate=None if data["assessment"] is not None else args["candidate"], review=args["candidate"] if data["assessment"] is not None else None,
                                             sourceReads=sorted(reads), codeReads=code_reads)
                            child.stdin.write(json.dumps(submitted, ensure_ascii=False)+"\n"); child.stdin.flush()
                            value = json.loads(child.stdout.readline()); revision += 1; value["submissionRevision"] = revision
                            keys[key] = (digest, value)
                            if value["outcome"] == "ACCEPTED":
                                Path(cfg["accepted"]).write_text(json.dumps(value["canonicalCandidate"], ensure_ascii=False, indent=2)); terminal = True
                    ledger.append(dict(tool=name, arguments=args, result=value))
                except (ValueError, KeyError) as exc:
                    error = True; value = {"error": str(exc)}; ledger.append(dict(tool=name, error=value))
                Path(cfg["ledger"]).write_text(json.dumps(ledger, ensure_ascii=False, indent=2))
                result = dict(content=[dict(type="text", text=json.dumps(value, ensure_ascii=False))], isError=error)
            else:
                print(json.dumps(dict(jsonrpc="2.0", id=request["id"], error=dict(code=-32601, message="Unsupported method"))), flush=True); continue
            print(json.dumps(dict(jsonrpc="2.0", id=request["id"], result=result)), flush=True)
    finally:
        child.stdin.close()
        try: child.wait(timeout=5)
        except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=5)


def run(args):
    output = Path(args.output).resolve(); output.mkdir(parents=True, exist_ok=False)
    sample = output / "sample.docx"; sample.write_bytes(Path(args.document).read_bytes())
    fixture = output / "code"; fixture.mkdir()
    for path in sorted(Path(args.fixture).glob("*.java")): (fixture / path.name).write_bytes(path.read_bytes())
    runner = module("luna_runner", "qualify-package-design-luna.py"); original_config = runner.isolated_config
    java = [args.java, "-cp", args.classpath, "DirectDocumentLunaProbe"]
    manifest = dict(model="gpt-5.6-luna", effort="medium", sampleSha256=base.sha(sample.read_bytes()),
        codeSha256={p.name: base.sha(p.read_bytes()) for p in fixture.iterdir()},
        codexVersion=subprocess.check_output([args.codex, "--version"], text=True).strip(),
        scope="direct source/code model effect with production contracts; isolated receipt adapter, not HTTP/DB lifecycle",
        budget=dict(sessions=2, secondsPerSession=600, candidatesPerSession=4, toolCallsPerSession=200), runs=[])
    for role in ["analysis", "review"]:
        directory = output / role; directory.mkdir(); (directory / "workspace").mkdir()
        candidate = output / "analysis/accepted.json"
        command = [str(sample), role, str(candidate), str(fixture)]
        data = json.loads(subprocess.check_output(java + ["prepare"] + command, text=True))
        input_file = directory / "input.json"; input_file.write_text(json.dumps(data, ensure_ascii=False, indent=2))
        descriptor = directory / "bridge.json"
        descriptor.write_text(json.dumps(dict(input=str(input_file), validate=java + ["validate"] + command,
            accepted=str(directory / "accepted.json"), ledger=str(directory / "tools.json"))))
        def config_override(run_dir, unused=None):
            env, auth, config = original_config(run_dir)
            config += '\n[mcp_servers.qualification]\ncommand = ' + json.dumps(sys.executable) + '\nargs = ' + json.dumps([str(Path(__file__).resolve()), "--bridge", str(descriptor)]) + '\nstartup_timeout_sec = 60\ntool_timeout_sec = 120\n'
            (Path(env["CODEX_HOME"]) / "config.toml").write_text(config)
            return env, auth, config
        runner.isolated_config = config_override
        (directory / "prompt.txt").write_text(data["prompt"])
        print("START " + role, flush=True)
        result = runner.execute(SimpleNamespace(codex=args.codex, timeout=600), directory, data["prompt"])
        result["accepted"] = (directory / "accepted.json").exists()
        result["workspaceUnchanged"] = not any((directory / "workspace").iterdir())
        manifest["runs"].append(dict(role=role, **result)); (output / "manifest.json").write_text(json.dumps(manifest, indent=2))
        print(json.dumps(dict(role=role, **result)), flush=True)
        if not result["accepted"] or not result["workspaceUnchanged"]: raise RuntimeError("Qualification incomplete; no automatic retry")
    if base.sha(sample.read_bytes()) != manifest["sampleSha256"] or any(base.sha((fixture / name).read_bytes()) != sha for name, sha in manifest["codeSha256"].items()):
        raise RuntimeError("Frozen inputs changed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("bridge", "document", "fixture", "output", "classpath"): parser.add_argument("--"+key)
    parser.add_argument("--java", default="java"); parser.add_argument("--codex", default="codex")
    args = parser.parse_args()
    if args.bridge: bridge(args.bridge)
    elif all((args.document, args.fixture, args.output, args.classpath)): run(args)
    else: parser.error("--document, --fixture, --output and --classpath are required")
