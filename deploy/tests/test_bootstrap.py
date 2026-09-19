"""Exercise initializer error propagation with an isolated executable boundary."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "airflow-bootstrap.sh"


class BootstrapTests(unittest.TestCase):
    def run_bootstrap(self, failure="", existing=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = root / "airflow"
            command.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "args = sys.argv[1:]\n"
                "with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(args) + '\\n')\n"
                "if ' '.join(args[:2]) == os.environ['FAILURE']: sys.exit(7)\n"
                "if args[:2] == ['users', 'list']:\n"
                "    print(json.dumps([{'username': os.environ['AIRFLOW_ADMIN_USERNAME']}] if os.environ['EXISTING'] == 'yes' else []))\n"
            )
            command.chmod(0o755)
            calls = root / "calls.jsonl"
            env = {**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                   "CALLS": str(calls), "FAILURE": failure, "EXISTING": "yes" if existing else "no",
                   "AIRFLOW_ADMIN_USERNAME": "test'admin", "AIRFLOW_ADMIN_PASSWORD": "-test' $(literal)",
                   "AIRFLOW_ADMIN_EMAIL": "test@example.invalid", "TMPDIR": str(root)}
            result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
            actions = [json.loads(line) for line in calls.read_text().splitlines()]
            self.assertEqual({"airflow", "calls.jsonl"}, {p.name for p in root.iterdir()})
            return result, actions

    def test_migration_failures_are_not_success(self):
        for stage in ("db migrate", "fab-db migrate"):
            result, calls = self.run_bootstrap(failure=stage)
            self.assertEqual(7, result.returncode)
            self.assertFalse(any(call[:1] == ["users"] for call in calls))

    def test_user_lookup_and_creation_failures_are_not_ignored(self):
        for stage in ("users list", "users create"):
            result, _ = self.run_bootstrap(failure=stage)
            self.assertEqual(7, result.returncode)

    def test_existing_user_is_idempotent(self):
        result, calls = self.run_bootstrap(existing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(call[:2] == ["users", "create"] for call in calls))

    def test_password_and_username_are_literal_arguments(self):
        result, calls = self.run_bootstrap()
        self.assertEqual(0, result.returncode, result.stderr)
        args = calls[-1]
        self.assertIn("--password=-test' $(literal)", args)
        self.assertIn("--username=test'admin", args)


if __name__ == "__main__":
    unittest.main()
