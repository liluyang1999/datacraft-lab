"""Swarm deployment scripts against a recording docker stub (no daemon needed)."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

DEPLOY = Path(__file__).resolve().parents[2] / "deploy"

# Records argv. `compose ... config` renders what validate() needs from the exported .env values;
# `service inspect` / `service ps` answer from SERVICE_IMAGE and the SERVICE_PS_* files, one per poll.
DOCKER_STUB = r"""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(args) + '\n')
if args[:1] == ['compose'] and 'config' in args:
    env = {'POSTGRES_PASSWORD': os.environ['POSTGRES_PASSWORD'],
           'AIRFLOW_ADMIN_PASSWORD': os.environ['AIRFLOW_ADMIN_PASSWORD'],
           'AIRFLOW__API__SECRET_KEY': os.environ['AIRFLOW_API_SECRET_KEY'],
           'AIRFLOW__API_AUTH__JWT_SECRET': os.environ['AIRFLOW_JWT_SECRET'],
           'AIRFLOW__CORE__FERNET_KEY': os.environ['AIRFLOW_FERNET_KEY']}
    print(json.dumps({'services': {'postgres': {'environment': {'POSTGRES_PASSWORD': env['POSTGRES_PASSWORD']}},
                                   'airflow-scheduler': {'environment': env},
                                   'airflow-init': {'environment': env}}}))
elif args[:2] == ['service', 'inspect']:
    if not os.environ.get('SERVICE_IMAGE'):
        sys.exit('Error: no such service: datacraft_airflow-init')
    print(os.environ['SERVICE_IMAGE'])
elif args[:2] == ['service', 'ps']:
    polls = sum(1 for line in open(os.environ['CALLS']) if json.loads(line)[:2] == ['service', 'ps'])
    responses = sorted(os.environ['SERVICE_PS'].split(os.pathsep))
    with open(responses[min(polls, len(responses)) - 1]) as source: sys.stdout.write(source.read())
"""


class SwarmScriptTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for part in ("scripts", "compose", "swarm"):
            shutil.copytree(DEPLOY / part, self.root / "deploy" / part)
        stub = self.root / "bin" / "docker"
        stub.parent.mkdir()
        stub.write_text(DOCKER_STUB)
        stub.chmod(0o755)
        self.calls = self.root / "calls.jsonl"
        self.env = {**os.environ, "PATH": f"{stub.parent}{os.pathsep}{os.environ['PATH']}",
                    "CALLS": str(self.calls), "DATACRAFT_INIT_POLL_SECONDS": "0"}

    def run_script(self, name, **env):
        self.calls.write_text("")
        result = subprocess.run(["bash", str(self.root / "deploy" / "scripts" / name)],
                                env={**self.env, **env}, stdin=subprocess.DEVNULL,
                                capture_output=True, text=True, timeout=60)
        return result, [json.loads(line) for line in self.calls.read_text().splitlines()]

    def write_env(self, **values):
        env_file = self.root / "deploy" / "compose" / ".env"
        created = subprocess.run([sys.executable, "-B", str(self.root / "deploy/scripts/deployment_env.py"),
                                  "init", str(env_file)], capture_output=True, text=True)
        self.assertEqual(0, created.returncode, created.stderr)
        text = env_file.read_text()
        for key, value in {"DATACRAFT_DATA_NODE": "node-1", **values}.items():
            text, count = re.subn(rf"(?m)^{key}=.*$", f"{key}={value}", text)
            self.assertEqual(1, count, key)
        env_file.write_text(text)

    def deployments(self, calls):
        return [call for call in calls if call[:2] == ["stack", "deploy"]]

    def test_pinned_tag_deploys_with_forwarded_registry_credentials(self):
        self.write_env(DATACRAFT_REGISTRY="registry.example.com:5000", DATACRAFT_TAG="1.2.3")
        result, calls = self.run_script("swarm-deploy.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([["stack", "deploy", "--with-registry-auth", "-c", "deploy/swarm/docker-stack.yml",
                           "datacraft"]], self.deployments(calls))
        self.assertIn("airflow-init.sh", result.stdout)

    def test_moving_or_missing_tags_never_reach_the_swarm(self):
        for tag in ("latest", ""):
            with self.subTest(tag=tag):
                self.write_env(DATACRAFT_TAG=tag)
                try:
                    result, calls = self.run_script("swarm-deploy.sh")
                finally:
                    (self.root / "deploy" / "compose" / ".env").unlink()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("DATACRAFT_TAG", result.stderr)
                self.assertEqual([], self.deployments(calls))

    def test_routing_mesh_registry_on_localhost_is_accepted(self):
        self.write_env(DATACRAFT_REGISTRY="localhost:5000", DATACRAFT_TAG="1.2.3")
        result, calls = self.run_script("swarm-deploy.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(self.deployments(calls)))

    def ps_responses(self, *responses):
        paths = []
        for index, response in enumerate(responses):
            path = self.root / f"ps-{index:02d}.txt"
            path.write_text(response)
            paths.append(str(path))
        return os.pathsep.join(paths)

    def test_init_waits_for_the_current_specs_task_to_complete(self):
        new, old = "reg/datacraft-airflow:2@sha256:new", "reg/datacraft-airflow:1@sha256:old"
        responses = self.ps_responses(
            f"{old}|Complete 5 minutes ago\n",  # the redeploy has not created the new task yet
            f"{new}|Running 2 seconds ago\n{old}|Complete 5 minutes ago\n",
            f"{new}|Complete 1 second ago\n{old}|Complete 5 minutes ago\n")
        result, calls = self.run_script("airflow-init.sh", SERVICE_IMAGE=new, SERVICE_PS=responses)
        self.assertEqual(0, result.returncode, result.stderr)
        polls = [call for call in calls if call[:2] == ["service", "ps"]]
        self.assertEqual(3, len(polls))
        self.assertIn("datacraft_airflow-init", polls[0])
        self.assertFalse(any(call[:1] == ["exec"] for call in calls))

    def test_init_times_out_with_diagnostics(self):
        image = "reg/datacraft-airflow:2"
        result, _ = self.run_script("airflow-init.sh", SERVICE_IMAGE=image, DATACRAFT_INIT_TIMEOUT="0",
                                    SERVICE_PS=self.ps_responses(f"{image}|Failed 3 seconds ago\n"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("docker service logs datacraft_airflow-init", result.stderr)
        self.assertIn("Failed", result.stderr)

    def test_init_requires_a_deployed_stack(self):
        result, calls = self.run_script("airflow-init.sh", SERVICE_IMAGE="", SERVICE_PS="")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("swarm-deploy.sh", result.stderr)
        self.assertFalse(any(call[:2] == ["service", "ps"] for call in calls))

    def test_init_no_longer_borrows_the_scheduler_container(self):
        text = (DEPLOY / "scripts" / "airflow-init.sh").read_text(encoding="utf-8")
        self.assertNotIn("docker exec", text)


if __name__ == "__main__":
    unittest.main()
