"""cicd/images/check_access.py against a stand-in for the deployed stack (no Docker needed)."""

import contextlib
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
CHECK = REPOSITORY / "cicd" / "images" / "check_access.py"
USER = "admin"
PASSWORD = "p" * 40
TOKEN = "t" * 64


def handler(faults):
    """A request handler that answers like the stack, except for the named faults."""

    class Stack(http.server.BaseHTTPRequestHandler):
        def log_message(self, format, *args):  # the base class's names; requests are not logged
            pass

        def reply(self, status):
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            presented = self.headers.get("Authorization")
            if self.path == "/health":
                self.reply(401 if "health needs a token" in faults and presented != f"Bearer {TOKEN}" else 200)
            elif self.path == "/jobs":
                allowed = (presented == f"Bearer {TOKEN}" or "open api" in faults
                           or ("any token" in faults and presented is not None))
                self.reply(500 if "broken api" in faults else 200 if allowed else 401)
            else:
                self.reply(404)

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            if self.path != "/auth/token":
                self.reply(404)
            elif body == {"username": USER, "password": PASSWORD} or "any password" in faults:
                self.reply(403 if "admin locked out" in faults else 201)
            else:
                self.reply(401)

    return Stack


@contextlib.contextmanager
def stack(*faults):
    """The base URL of a running stand-in."""
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler(faults))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=10)


def run_check(*arguments, **environment):
    env = {key: value for key, value in os.environ.items()
           if key not in ("AIRFLOW_ADMIN_USERNAME", "AIRFLOW_ADMIN_PASSWORD", "DATACRAFT_API_TOKEN")}
    env.update(environment)
    return subprocess.run([sys.executable, "-B", str(CHECK), *arguments], capture_output=True, text=True,
                          env=env, timeout=120)


class CheckAccessTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.env_file = Path(directory.name) / ".env"
        self.env_file.write_text("# generated\n\nAIRFLOW_UID=50000\n"
                                 f"AIRFLOW_ADMIN_USERNAME={USER}\nAIRFLOW_ADMIN_PASSWORD={PASSWORD}\n"
                                 f"DATACRAFT_API_TOKEN={TOKEN}\nDATACRAFT_TAG=\n", encoding="utf-8")

    def assert_no_credential(self, result):
        for secret in (PASSWORD, TOKEN):
            self.assertNotIn(secret, result.stdout + result.stderr)

    def test_a_stack_that_answers_only_its_credentials_passes(self):
        with stack() as url:
            result = run_check("--env-file", str(self.env_file), "--airflow", url + "/", "--api", url)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"Airflow at {url} gives a token for the admin password only\n"
                         f"the engine API at {url} lists its jobs for its token only\n", result.stdout)
        self.assertEqual("", result.stderr)
        self.assert_no_credential(result)

    def test_each_side_can_be_checked_alone_with_credentials_from_the_environment(self):
        with stack() as url:
            api = run_check("--api", url, DATACRAFT_API_TOKEN=TOKEN)
            airflow = run_check("--airflow", url, AIRFLOW_ADMIN_USERNAME=USER, AIRFLOW_ADMIN_PASSWORD=PASSWORD)
        self.assertEqual((0, f"the engine API at {url} lists its jobs for its token only\n"),
                         (api.returncode, api.stdout), api.stderr)
        self.assertEqual((0, f"Airflow at {url} gives a token for the admin password only\n"),
                         (airflow.returncode, airflow.stdout), airflow.stderr)

    def test_every_departure_fails_and_is_named_without_a_credential(self):
        expected = {
            "open api": ["/jobs without a token got 200, expected 401",
                         "/jobs with another token got 200, expected 401"],
            "any token": ["/jobs with another token got 200, expected 401"],
            "broken api": ["/jobs without a token got 500, expected 401",
                           "/jobs with another token got 500, expected 401",
                           "/jobs with the token got 500, expected 200"],
            "health needs a token": ["/health without a token got 401, expected 200"],
            "any password": ["signing in with another password got 201, expected 401"],
            "admin locked out": ["signing in with the admin password got 403, expected 201"],
        }
        for fault, lines in expected.items():
            with self.subTest(fault=fault), stack(fault) as url:
                result = run_check("--env-file", str(self.env_file), "--airflow", url, "--api", url)
                self.assertEqual(1, result.returncode, result.stdout)
                side = "Airflow" if "sign" in lines[0] else "engine API"
                self.assertEqual([f"{side} at {url}: {line}" for line in lines], result.stderr.splitlines())
                self.assertEqual("", result.stdout)
                self.assert_no_credential(result)

    def test_a_stack_that_does_not_answer_fails_without_a_traceback(self):
        with stack() as url:
            pass  # closed again: nothing listens on that port now
        result = run_check("--env-file", str(self.env_file), "--airflow", url)
        self.assertEqual(1, result.returncode)
        lines = result.stderr.splitlines()
        self.assertEqual(2, len(lines), result.stderr)
        self.assertTrue(all(" got no answer (" in line for line in lines), result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assert_no_credential(result)

    def test_a_missing_credential_is_named_and_nothing_is_asked(self):
        with stack() as url:
            result = run_check("--airflow", url, "--api", url, AIRFLOW_ADMIN_USERNAME=USER)
        self.assertEqual(2, result.returncode)
        self.assertEqual("no value for AIRFLOW_ADMIN_PASSWORD, DATACRAFT_API_TOKEN\n", result.stderr)
        # An empty value in the file is a missing one too, and only the checks asked for count.
        self.env_file.write_text(f"AIRFLOW_ADMIN_USERNAME={USER}\nAIRFLOW_ADMIN_PASSWORD=\n", encoding="utf-8")
        with stack() as url:
            result = run_check("--env-file", str(self.env_file), "--airflow", url)
        self.assertEqual((2, "no value for AIRFLOW_ADMIN_PASSWORD\n"), (result.returncode, result.stderr))

    def test_usage_errors_exit_2(self):
        nothing = run_check("--env-file", str(self.env_file))
        self.assertEqual(2, nothing.returncode)
        self.assertIn("name at least one of --airflow and --api", nothing.stderr)
        unreadable = run_check("--env-file", str(self.env_file.with_name("absent")), "--api", "http://127.0.0.1:9")
        self.assertEqual((2, "cannot read the env file: FileNotFoundError\n"),
                         (unreadable.returncode, unreadable.stderr))


if __name__ == "__main__":
    unittest.main()
