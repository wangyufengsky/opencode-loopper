#!/usr/bin/env python3
"""Protocol and isolation regression tests; no model or subscription calls."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("qualify-package-design-luna.py")
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("luna", SCRIPT)
luna = importlib.util.module_from_spec(spec)
spec.loader.exec_module(luna)


class QualificationProtocolTest(unittest.TestCase):
    def test_actual_request_budget_is_not_replaced_by_a_session_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run([sys.executable, str(SCRIPT), "--gepa", "--output", tmp], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            report = json.loads(result.stdout)
            self.assertEqual(report["modelRequestsStarted"], 0)
            self.assertEqual(report["status"], "BLOCKED_AT_ADAPTER_VALIDATION")

    def test_isolation_copies_only_auth_and_removes_provider_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            user = base / "personal"
            user.mkdir()
            (user / "auth.json").write_text('{"synthetic":true}')
            (user / "AGENTS.md").write_text("must never be copied")
            run = base / "run"
            run.mkdir()
            with patch.dict(os.environ, {"CODEX_HOME": str(user), "OPENAI_API_KEY": "synthetic", "CODEX_THREAD_ID": "personal"}):
                env, auth, config = luna.isolated_config(run)
            self.assertNotIn("OPENAI_API_KEY", env)
            self.assertNotIn("CODEX_THREAD_ID", env)
            self.assertEqual(auth.stat().st_mode & 0o777, 0o600)
            self.assertFalse((auth.parent / "AGENTS.md").exists())
            self.assertIn('forced_login_method = "chatgpt"', config)
            self.assertIn('model_reasoning_effort = "medium"', config)
            self.assertIn('shell_tool = false', config)

    def test_four_candidates_and_idempotent_replay_have_distinct_accounting(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            compiler = base / "compiler.py"
            compiler.write_text('import json,sys\n'
                                'if sys.argv[1]=="schema": print("{}"); sys.exit(0)\n'
                                'for line in sys.stdin: print(json.dumps({"outcome":"REJECTED","retryable":True,'
                                '"action":"FIX_AND_RESUBMIT","problems":[]}),flush=True)\n')
            descriptor = base / "bridge.json"
            ledger = base / "attempts.json"
            descriptor.write_text(json.dumps({"java": [sys.executable, str(compiler)], "fixture": "unused", "ledger": str(ledger)}))
            def request(number, revision, key, candidate):
                return {"jsonrpc": "2.0", "id": number, "method": "tools/call", "params": {"name": "submit_package_design",
                        "arguments": {"runId": "qualification", "expectedSubmissionRevision": revision,
                                      "idempotencyKey": key, "candidate": candidate}}}
            messages = [request(1, 0, "one", {"value": 1}), request(2, 0, "one", {"value": 1}),
                        request(3, 1, "one", {"value": 2}), request(4, 1, "two", {"value": 2}),
                        request(5, 2, "three", {"value": 3}), request(6, 3, "four", {"value": 4}),
                        request(7, 4, "five", {"value": 5})]
            result = subprocess.run([sys.executable, str(SCRIPT), "--bridge", str(descriptor)],
                                    input="\n".join(json.dumps(item) for item in messages) + "\n", text=True, capture_output=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            values = [json.loads(json.loads(line)["result"]["content"][0]["text"]) for line in result.stdout.splitlines()]
            self.assertEqual(values[0], values[1])
            self.assertEqual(values[2]["outcome"], "REVISION_CONFLICT")
            self.assertEqual(values[-1]["outcome"], "WAITING_INPUT")
            self.assertEqual(len(json.loads(ledger.read_text())), 4)


if __name__ == "__main__":
    unittest.main()
