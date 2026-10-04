#!/usr/bin/env python3
"""Own one temporary fake-provider Spring process; never reuse a user's data root.

Run in the foreground. SIGINT/SIGTERM cleans up only the child process group
created here. The proof is ready only after the real health/runtime endpoints pass.
This is test infrastructure, not a production launcher or a packaged JAR.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
from urllib.request import build_opener, ProxyHandler


BASELINE = "11ca25a3bb764a2d80ac924350139a7087639121"


def absolute_path(value):
    path = Path(value)
    if not path.is_absolute() or path.resolve() != path:
        raise ValueError("Paths must be absolute, canonical and contain no symlinks")
    return path


def isolated_environment(run_root, java_home):
    # Deliberately do not inherit provider, Git, proxy, credential or MCP settings.
    return {
        "PATH": f"{java_home / 'bin'}:/usr/bin:/bin",
        "JAVA_HOME": str(java_home), "LANG": "C.UTF-8", "TZ": "UTC",
        "TMPDIR": str(run_root / "tmp"),
        "LOOPPER_DATA_DIR": str(run_root / "data"),
        "LOOPPER_ALLOWED_ROOT": str(run_root / "projects"),
        "LOOPPER_OPENCODE_MODE": "fake", "OPENCODE_MODEL": "fake/model",
        "LOOPPER_OPEN_BROWSER": "false",
        # Local synthetic Git fixtures must not consult global user credential/config files.
        "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_TERMINAL_PROMPT": "0",
    }


def command(repo, run_root, java_home, classpath, port):
    if not 1024 <= port <= 65535:
        raise ValueError("A nonprivileged loopback port is required")
    return [
        str(java_home / "bin/java"), "-Djava.awt.headless=true",
        f"-Djava.io.tmpdir={run_root / 'tmp'}", f"-Duser.home={run_root / 'home'}",
        "-cp", classpath, "io.opencode.loopper.LoopperApplication",
        "--server.address=127.0.0.1", f"--server.port={port}",
        "--loopper.scheduling.enabled=false", "--loopper.startup-recovery.enabled=false",
        "--loopper.opencode.mode=fake", "--loopper.opencode.model=fake/model",
        f"--loopper.data-dir={run_root / 'data'}",
        f"--loopper.allowed-root={run_root / 'projects'}",
    ]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def get_json(base_url, path):
    # Do not send local test traffic through inherited proxies.
    with build_opener(ProxyHandler({})).open(base_url + path, timeout=3) as response:
        return json.load(response)


def runtime_hashes(classes, dependencies):
    entries = []
    # The browser verifier sorts relative path strings, not Path components.
    # e.g. prompt-v1/... must precede prompt/v1/... in both producers.
    for path in sorted(classes.rglob("*"), key=lambda item: str(item.relative_to(classes))):
        if path.is_file():
            entries.append((str(path.relative_to(classes)), hashlib.sha256(path.read_bytes()).hexdigest()))
    return {
        "classesSha256": hashlib.sha256(json.dumps(entries, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest(),
        "classFileCount": len(entries),
        "dependencies": [{"file": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                         for path in map(Path, dependencies.split(os.pathsep))],
    }


def stop_owned_child(child):
    if child.poll() is None:
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            child.wait(timeout=5)
            return
        try:
            child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, type=absolute_path)
    parser.add_argument("--run-root", required=True, type=absolute_path)
    parser.add_argument("--java-home", required=True, type=absolute_path)
    parser.add_argument("--classpath-file", required=True, type=absolute_path)
    parser.add_argument("--expected-revision", default=BASELINE)
    parser.add_argument("--port", type=int)
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.repo, text=True).strip()
    if revision != args.expected_revision:
        raise ValueError("HEAD does not match the explicit test revision")
    runtime_changes = subprocess.check_output(
        ["git", "diff", "HEAD", "--name-only", "--", "src/main", "pom.xml", ".mvn"],
        cwd=args.repo, text=True,
    ).strip()
    if runtime_changes:
        raise ValueError("Commit runtime changes before claiming a frozen test revision")
    classes = args.repo / "target/backend-dev/classes"
    if not (classes / "io/opencode/loopper/LoopperApplication.class").is_file():
        raise ValueError("Compile the backend-dev classes first")
    dependencies = args.classpath_file.read_text().strip()
    if not dependencies or any(not Path(part).is_file() for part in dependencies.split(os.pathsep)):
        raise ValueError("All locked runtime classpath entries must exist")
    # mkdir without exist_ok: never attach to/reuse a previous test run or database.
    args.run_root.mkdir(mode=0o700)
    for part in ("data", "projects", "tmp", "home", "evidence"):
        (args.run_root / part).mkdir(mode=0o700)
    for owner in ("A", "B"):
        (args.run_root / "projects" / owner).mkdir(mode=0o700)
    port = args.port or free_port()
    classpath = str(classes) + os.pathsep + dependencies
    proof_path = args.run_root / "isolation.json"
    proof = {
        "revision": revision, "baseUrl": f"http://127.0.0.1:{port}",
        "runRoot": str(args.run_root),
        "dataDir": str(args.run_root / "data"), "projectRoot": str(args.run_root / "projects"),
        "opencodeMode": "fake", "model": "fake/model",
        "schedulingEnabled": False, "startupRecoveryEnabled": False, "ready": False,
        "runtimeHashes": runtime_hashes(classes, dependencies),
    }
    stopping = False

    def request_stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    child = None
    try:
        with (args.run_root / "spring.log").open("xb") as log:
            child = subprocess.Popen(command(args.repo, args.run_root, args.java_home, classpath, port),
                                     cwd=args.run_root, env=isolated_environment(args.run_root, args.java_home),
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            proof["ownedJavaPid"] = child.pid
            proof_path.write_text(json.dumps(proof, indent=2) + "\n")
            deadline = time.monotonic() + 120
            while not stopping and child.poll() is None and time.monotonic() < deadline:
                try:
                    health = get_json(proof["baseUrl"], "/actuator/health")
                    runtime = get_json(proof["baseUrl"], "/api/runtime/opencode")
                    if health.get("status") == "UP" and runtime.get("status") == "AVAILABLE":
                        if runtime.get("managed") is not False or "fake" not in str(runtime.get("version", "")).lower():
                            raise ValueError("The runtime endpoint did not prove the fake provider")
                        proof.update(ready=True, health=health, runtime=runtime)
                        proof_path.write_text(json.dumps(proof, indent=2) + "\n")
                        print(json.dumps({"ready": True, "proof": str(proof_path), "baseUrl": proof["baseUrl"]}), flush=True)
                        break
                except (OSError, json.JSONDecodeError):
                    pass
                time.sleep(0.25)
            if not proof["ready"] and not stopping:
                raise RuntimeError("Real Spring health/fake-runtime readiness failed; inspect the isolated spring.log")
            while not stopping and child.poll() is None:
                time.sleep(0.25)
            if child.poll() is not None and not stopping:
                raise RuntimeError(f"Owned Spring process exited {child.returncode}")
    finally:
        if child is not None:
            stop_owned_child(child)
        # This is proof of the owned Java exit, not a claim about every GC object or descendant.
        proof.update(ready=False, stopped=True, javaExitCode=None if child is None else child.returncode)
        proof_path.write_text(json.dumps(proof, indent=2) + "\n")


if __name__ == "__main__":
    main()
