"""Single-host Compose scripts against a recording docker stub (no daemon needed)."""

import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

DEPLOY = Path(__file__).resolve().parents[2] / "deploy"

# Records where and how Compose is asked for something. `compose ... config` answers with the
# configuration in STUB_CONFIG, as the real command would render the files it resolved.
DOCKER_STUB = r"""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out:
    out.write(json.dumps({'cwd': os.getcwd(), 'args': args}) + '\n')
if args[:1] == ['compose'] and 'config' in args:
    with open(os.environ['STUB_CONFIG']) as source: sys.stdout.write(source.read())
"""


def effective_configuration(**api) -> dict:
    env = {"POSTGRES_PASSWORD": "a" * 40, "AIRFLOW_ADMIN_PASSWORD": "b" * 40,
           "AIRFLOW__API__SECRET_KEY": "c" * 40, "AIRFLOW__API_AUTH__JWT_SECRET": "d" * 40,
           "AIRFLOW__CORE__FERNET_KEY": base64.urlsafe_b64encode(b"e" * 32).decode()}
    return {"services": {
        "postgres": {"environment": {"POSTGRES_PASSWORD": env["POSTGRES_PASSWORD"]}},
        "airflow-scheduler": {"environment": env}, "airflow-init": {"environment": env},
        "datacraft-api": {"environment": {"DATACRAFT_API_TOKEN": "f" * 40}, **api}}}


def file_selection(call) -> tuple:
    """What decides which Compose files and project a call addresses."""
    args = call["args"]
    command = next(index for index, arg in enumerate(args)
                   if arg in ("config", "up", "down", "ps"))
    return os.path.realpath(call["cwd"]), tuple(args[1:command])


class ComposeScriptTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for part in ("scripts", "compose"):
            shutil.copytree(DEPLOY / part, self.root / "deploy" / part)
        (self.root / "deploy" / "compose" / ".env").write_text("# generated elsewhere\n")
        stub = self.root / "bin" / "docker"
        stub.parent.mkdir()
        stub.write_text(DOCKER_STUB)
        stub.chmod(0o755)
        self.calls = self.root / "calls.jsonl"
        self.config = self.root / "config.json"
        self.env = {**os.environ, "PATH": f"{stub.parent}{os.pathsep}{os.environ['PATH']}",
                    "CALLS": str(self.calls), "STUB_CONFIG": str(self.config)}

    def run_script(self, name, *arguments, configuration=None):
        self.calls.write_text("")
        self.config.write_text(json.dumps(configuration or effective_configuration()))
        result = subprocess.run(["bash", str(self.root / "deploy" / "scripts" / name), *arguments],
                                env=self.env, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                                timeout=60)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        return result, [call for call in calls if call["args"][:1] == ["compose"]
                        and call["args"][1:2] != ["version"]]

    def only(self, calls, command):
        found = [call for call in calls if command in call["args"]]
        self.assertEqual(1, len(found), (command, calls))
        return found[0]

    def test_the_configuration_that_is_validated_is_the_one_that_starts(self):
        # Compose resolves its files from the directory it runs in (docker-compose.yml, an override
        # file next to it, COMPOSE_FILE) unless -f names them. A check that names one file while the
        # start names none would approve a configuration other than the one that runs.
        result, calls = self.run_script("compose-up.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        checked, started = self.only(calls, "config"), self.only(calls, "up")
        self.assertEqual(file_selection(checked), file_selection(started))
        self.assertEqual(os.path.realpath(self.root / "deploy" / "compose"), file_selection(started)[0])
        self.assertLess(calls.index(checked), calls.index(started))

    def test_a_configuration_that_fails_the_check_is_never_started(self):
        published = effective_configuration(ports=[{"host_ip": "0.0.0.0", "published": "8088", "target": 8080}])
        result, calls = self.run_script("compose-up.sh", configuration=published)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("loopback", result.stderr)
        self.assertEqual([], [call for call in calls if "up" in call["args"]])

    def test_stopping_addresses_the_project_that_was_started(self):
        _, started = self.run_script("compose-up.sh")
        result, stopped = self.run_script("compose-down.sh", "-v")
        self.assertEqual(0, result.returncode, result.stderr)
        down = self.only(stopped, "down")
        self.assertEqual(file_selection(self.only(started, "up")), file_selection(down))
        self.assertEqual(["down", "-v"], down["args"][-2:])


if __name__ == "__main__":
    unittest.main()
