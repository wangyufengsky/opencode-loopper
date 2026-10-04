"""Safety checks for the test launcher. These do not validate Spring or models."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("isolated_server", Path(__file__).with_name("isolated-server.py"))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class LauncherSafetyTest(unittest.TestCase):
    def test_runtime_hash_uses_browser_relative_string_order_and_utf8(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = root / "classes"
            entries = []
            for name in ("prompt/v1/角色.txt", "prompt-v1/角色.txt"):
                file = classes / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b"synthetic")
                entries.append((name, hashlib.sha256(b"synthetic").hexdigest()))
            jar = root / "synthetic.jar"
            jar.write_bytes(b"jar")
            entries.sort(key=lambda item: item[0])
            expected = hashlib.sha256(json.dumps(entries, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
            actual = server.runtime_hashes(classes, str(jar))
            self.assertEqual(expected, actual["classesSha256"])
            self.assertEqual(2, actual["classFileCount"])
            self.assertEqual(hashlib.sha256(b"jar").hexdigest(), actual["dependencies"][0]["sha256"])

    def test_rejects_relative_paths(self):
        with self.assertRaises(ValueError):
            server.absolute_path("./data")

    def test_rejects_symlink_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "link").symlink_to(root)
            with self.assertRaises(ValueError):
                server.absolute_path(str(root / "link" / "data"))

    def test_environment_does_not_inherit_secrets_or_proxies(self):
        with patch.dict(os.environ, {"OPENCODE_PASSWORD": "synthetic-do-not-inherit", "HTTPS_PROXY": "synthetic"}):
            result = server.isolated_environment(Path("/tmp/isolated"), Path("/tmp/jdk"))
        self.assertNotIn("OPENCODE_PASSWORD", result)
        self.assertNotIn("HTTPS_PROXY", result)
        self.assertNotIn("HOME", result)
        self.assertEqual("fake", result["LOOPPER_OPENCODE_MODE"])
        self.assertEqual("fake/model", result["OPENCODE_MODEL"])
        self.assertEqual("/dev/null", result["GIT_CONFIG_GLOBAL"])
        self.assertEqual("0", result["GIT_TERMINAL_PROMPT"])

    def test_command_keeps_paths_literal_and_disables_background_writers(self):
        command = server.command(Path("/tmp/repo"), Path("/tmp/a b;$literal"), Path("/tmp/jdk"), "classes:jars", 18080)
        self.assertIn("--loopper.allowed-root=/tmp/a b;$literal/projects", command)
        self.assertIn("--loopper.scheduling.enabled=false", command)
        self.assertIn("--loopper.startup-recovery.enabled=false", command)
        self.assertIn("--server.address=127.0.0.1", command)
        self.assertIn("-Duser.home=/tmp/a b;$literal/home", command)

    def test_rejects_privileged_ports(self):
        with self.assertRaises(ValueError):
            server.command(Path("/tmp/repo"), Path("/tmp/run"), Path("/tmp/jdk"), "classes", 80)

    def test_owned_child_terminates_without_input(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=True)
        try:
            server.stop_owned_child(child)
            self.assertIsNotNone(child.poll())
        finally:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=5)

    def test_finished_child_is_not_signalled(self):
        child = subprocess.Popen([sys.executable, "-c", "pass"], start_new_session=True)
        child.wait(timeout=5)
        with patch.object(os, "killpg", side_effect=AssertionError("Cannot signal an exited child")):
            server.stop_owned_child(child)


if __name__ == "__main__":
    unittest.main()
