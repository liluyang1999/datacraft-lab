"""Exercise initializer error propagation with an isolated executable boundary."""

import json
import os
from pathlib import Path
import select
import signal
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "deploy" / "scripts" / "airflow-bootstrap.sh"
PASSWORD = "-test' $(literal)"

# Stands in for the Airflow CLI. Without --password or --use-random-password, `users create` reads
# the password exactly like FAB 3.9.0's user_command._create_password: two getpass prompts that must
# match. getpass reads the controlling terminal when there is one and falls back to stdin otherwise.
STUB = """#!/usr/bin/env python3
import getpass, json, os, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(args) + '\\n')
if ' '.join(args[:2]) == os.environ['FAILURE']: sys.exit(7)
if args[:2] == ['users', 'export']:
    print('2026-01-01T00:00:00Z [warning  ] import-time log line on stdout [py.warnings]')
    with open(args[2], 'w') as users:
        users.write(json.dumps([{'username': os.environ['AIRFLOW_ADMIN_USERNAME']}] if os.environ['EXISTING'] == 'yes' else []))
if args[:2] == ['users', 'create'] and not any(
        arg in ('-p', '--use-random-password') or arg.startswith('--password') for arg in args):
    password = getpass.getpass('Password:')
    if password != getpass.getpass('Repeat for confirmation:'):
        sys.exit('Passwords did not match')
    with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(['<getpass>', password]) + '\\n')
"""


class BootstrapTests(unittest.TestCase):
    def run_bootstrap(self, failure="", existing=False, terminal=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = root / "airflow"
            command.write_text(STUB)
            command.chmod(0o755)
            calls = root / "calls.jsonl"
            env = {**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                   "CALLS": str(calls), "FAILURE": failure, "EXISTING": "yes" if existing else "no",
                   "AIRFLOW_ADMIN_USERNAME": "test'admin", "AIRFLOW_ADMIN_PASSWORD": PASSWORD,
                   "AIRFLOW_ADMIN_EMAIL": "test@example.invalid", "TMPDIR": str(root)}
            if terminal:
                result = self.run_on_terminal(env)
            else:
                result = subprocess.run(["bash", str(SCRIPT)], env=env, stdin=subprocess.DEVNULL,
                                        capture_output=True, text=True, timeout=60)
            actions = [json.loads(line) for line in calls.read_text().splitlines()]
            self.assertEqual({"airflow", "calls.jsonl"}, {p.name for p in root.iterdir()})
            return result, actions

    def run_on_terminal(self, env):
        """Run the bootstrap as `docker compose run` or `exec -it` would: on a controlling terminal."""
        import pty  # POSIX only

        pid, master = pty.fork()
        if pid == 0:
            try:
                os.execvpe("bash", ["bash", str(SCRIPT)], env)
            finally:
                os._exit(127)
        output, deadline = b"", time.monotonic() + 30
        try:
            while True:
                ready, _, _ = select.select([master], [], [], max(0.0, deadline - time.monotonic()))
                if not ready:
                    os.kill(pid, signal.SIGKILL)
                    os.waitpid(pid, 0)
                    self.fail("Bootstrap blocked reading the controlling terminal: " + output.decode(errors="replace"))
                try:
                    chunk = os.read(master, 4096)
                except OSError:  # EIO: every process holding the terminal has exited
                    break
                if not chunk:
                    break
                output += chunk
        finally:
            os.close(master)
        _, status = os.waitpid(pid, 0)
        return subprocess.CompletedProcess(["bash", str(SCRIPT)], os.waitstatus_to_exitcode(status),
                                           output.decode(errors="replace"), "")

    def test_migration_failures_are_not_success(self):
        for stage in ("db migrate", "fab-db migrate"):
            result, calls = self.run_bootstrap(failure=stage)
            self.assertEqual(7, result.returncode)
            self.assertFalse(any(call[:1] == ["users"] for call in calls))

    def test_user_lookup_and_creation_failures_are_not_ignored(self):
        for stage in ("users export", "users create"):
            result, _ = self.run_bootstrap(failure=stage)
            self.assertEqual(7, result.returncode)

    def test_existing_user_is_idempotent(self):
        result, calls = self.run_bootstrap(existing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(call[:2] == ["users", "create"] for call in calls))

    def test_password_is_passed_on_stdin_not_argv(self):
        result, calls = self.run_bootstrap()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["<getpass>", PASSWORD], calls[-1])
        create = calls[-2]
        self.assertEqual(["users", "create"], create[:2])
        self.assertIn("--username=test'admin", create)
        for call in calls[:-1]:
            for arg in call:
                self.assertNotIn(PASSWORD, arg)
                self.assertFalse(arg == "-p" or arg.startswith("--password"), call)

    @unittest.skipUnless(hasattr(os, "openpty"), "needs a POSIX pseudo-terminal")
    def test_password_prompt_never_waits_for_a_controlling_terminal(self):
        result, calls = self.run_bootstrap(terminal=True)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(["<getpass>", PASSWORD], calls[-1])
        self.assertNotIn(PASSWORD, result.stdout)


if __name__ == "__main__":
    unittest.main()
