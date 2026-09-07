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
    def test_preparation_disables_mcp_then_resumes_exact_conversation(self):
        from types import SimpleNamespace
        args = SimpleNamespace(codex="codex")
        directory = Path("/isolated/case")
        prepare = luna.codex_command(args, directory, preparation=True)
        self.assertIn("mcp_servers.qualification.enabled=false", prepare)
        self.assertNotIn("--ephemeral", prepare)
        resume = luna.codex_command(args, directory, thread="exact-thread-id")
        self.assertEqual(resume[:3], ["codex", "exec", "resume"])
        self.assertEqual(resume[-2:], ["exact-thread-id", "-"])
        self.assertNotIn("--last", resume)
        self.assertIn("--ephemeral", luna.codex_command(args, directory))

    def test_independent_review_uses_new_thread_and_unconfirmed_review_prevents_candidate(self):
        from types import SimpleNamespace
        for approved in (True, False):
            with tempfile.TemporaryDirectory() as tmp:
                base = Path(tmp)
                personal = base / "personal"
                personal.mkdir()
                (personal / "auth.json").write_text('{"synthetic":true}')
                run = base / "run"
                run.mkdir()
                (run / "workspace").mkdir()
                codex = base / "fake-codex"
                codex.write_text("#!/usr/bin/env python3\nimport json,sys\n"
                    "if sys.argv[1:3]==['login','status']: print('Logged in using ChatGPT'); sys.exit(0)\n"
                    "text=sys.stdin.read().strip()\n"
                    "from pathlib import Path\nPath(sys.argv[sys.argv.index('--output-last-message')+1]).write_text('material')\n"
                    "thread='review-thread' if text=='review prompt' else 'main-thread'\n"
                    "print(json.dumps({'type':'thread.started','thread_id':thread}))\n"
                    "print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':'commentary that is not final JSON'}}))\n"
                    "print(json.dumps({'type':'turn.completed','usage':{'input_tokens':1,'output_tokens':1}}))\n")
                codex.chmod(0o700)
                java = base / "fake-java.py"
                java.write_text("import json,sys\n"
                    "if sys.argv[1]=='behavior-review-prompt': print('review prompt')\n"
                    f"elif sys.argv[1]=='behavior-review': print(json.dumps({{'accepted':{approved!r},'reviewJson':'review'}}))\n"
                    "elif sys.argv[1]=='prompt': print('candidate prompt')\n")
                descriptor = run / "bridge.json"
                descriptor.write_text(json.dumps({"java":[sys.executable,str(java)], "fixture":"fixture", "contract":"PACKAGE_DESIGN_V2"}))
                args = SimpleNamespace(codex=str(codex), timeout=15)
                with patch.dict(os.environ, {"CODEX_HOME":str(personal)}):
                    result = luna.execute(args, run, "candidate prompt", descriptor,
                        {"enabled":True,"prompt":"extract prompt"}, behavior=True)
                self.assertEqual((run / "source-review-output.txt").read_text(), "material")
                self.assertEqual(result["sourceReviewTurns"], 1)
                self.assertEqual(result["candidateDispatched"], approved)
                self.assertIsNone(result["actualModelRequests"])
                self.assertEqual(len(result["phases"]), 3 if approved else 2)
                self.assertNotEqual(result["phases"][0]["threadIds"], result["phases"][1]["threadIds"])
                self.assertFalse((run / "codex-config" / "auth.json").exists())

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
