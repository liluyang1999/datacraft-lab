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

# Records argv. `compose ... config` renders what validate() needs from the exported .env values.
# `service inspect` answers from SERVICE_SPEC (the service's container spec as JSON, or its image),
# `service ps` renders the `id|image|state` lines of the SERVICE_PS files, one file per poll, in the
# format asked for, and `inspect --type task` answers the container spec of a task from TASK_SPECS.
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
    if not os.environ.get('SERVICE_SPEC'):
        sys.exit('Error: no such service: datacraft_airflow-init')
    spec = os.environ['SERVICE_SPEC']
    print(json.loads(spec)['Image'] if 'ContainerSpec.Image' in args[3] else spec)
elif args[:2] == ['service', 'ps']:
    polls = sum(1 for line in open(os.environ['CALLS']) if json.loads(line)[:2] == ['service', 'ps'])
    responses = sorted(os.environ['SERVICE_PS'].split(os.pathsep))
    with open(responses[min(polls, len(responses)) - 1]) as source:
        for line in source.read().splitlines():
            task, image, state = line.split('|')
            print(args[4].replace('{{.ID}}', task).replace('{{.Image}}', image)
                  .replace('{{.CurrentState}}', state))
elif args[:3] == ['inspect', '--type', 'task']:
    print(json.dumps(json.loads(os.environ['TASK_SPECS'])[args[-1]]))
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

    def test_an_environment_file_the_shell_would_interpret_is_never_sourced(self):
        # The script reads .env with `source`. A value with shell syntax in it would run as a
        # command there, and reach the containers, through Docker's env_file, as different text.
        marker = self.root / "executed"
        for line in (f"AIRFLOW_ADMIN_EMAIL=$(touch {marker})", f"AIRFLOW_ADMIN_EMAIL=a@example.com; touch {marker}",
                     f"touch {marker}"):
            with self.subTest(line=line):
                self.write_env(DATACRAFT_REGISTRY="registry.example.com:5000", DATACRAFT_TAG="1.2.3")
                env_file = self.root / "deploy" / "compose" / ".env"
                env_file.write_text(env_file.read_text() + line + "\n")
                try:
                    result, calls = self.run_script("swarm-deploy.sh")
                finally:
                    env_file.unlink()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(marker.exists(), "a line of .env ran as a command")
                self.assertIn("deploy/compose/.env", result.stderr)
                self.assertEqual([], self.deployments(calls))

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

    def test_a_missing_weak_or_reused_redis_password_never_reaches_the_swarm(self):
        self.write_env(DATACRAFT_TAG="1.2.3")
        env_file = self.root / "deploy" / "compose" / ".env"
        generated = env_file.read_text()
        secrets = dict(line.split("=", 1) for line in generated.splitlines()
                       if "=" in line and not line.startswith("#"))
        cases = {
            "": "Set REDIS_PASSWORD",
            "short-password": "Set REDIS_PASSWORD",
            "p" * 40 + "@host": "Set REDIS_PASSWORD",  # it sits in the broker URL, so URL-safe characters only
            secrets["POSTGRES_PASSWORD"]: "REDIS_PASSWORD must be independent of POSTGRES_PASSWORD.",
            secrets["DATACRAFT_API_TOKEN"]: "REDIS_PASSWORD must be independent of DATACRAFT_API_TOKEN.",
        }
        for value, message in cases.items():
            with self.subTest(message=message, length=len(value)):
                env_file.write_text(re.sub(r"(?m)^REDIS_PASSWORD=.*$", f"REDIS_PASSWORD={value}", generated))
                result, calls = self.run_script("swarm-deploy.sh")
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)
                if value:
                    self.assertNotIn(value, result.stdout + result.stderr)
                self.assertEqual([], self.deployments(calls))
        # A .env from before the stack asked for the password has no such line at all.
        env_file.write_text(re.sub(r"(?m)^REDIS_PASSWORD=.*\n", "", generated))
        result, calls = self.run_script("swarm-deploy.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Set REDIS_PASSWORD", result.stderr)
        self.assertEqual([], self.deployments(calls))

    def ps_responses(self, *responses):
        paths = []
        for index, response in enumerate(responses):
            path = self.root / f"ps-{index:02d}.txt"
            path.write_text(response)
            paths.append(str(path))
        return os.pathsep.join(paths)

    def run_init(self, service, tasks, *responses, **env):
        """airflow-init.sh against a service spec, the specs of its tasks and one poll per response."""
        return self.run_script("airflow-init.sh", SERVICE_SPEC=json.dumps(service), TASK_SPECS=json.dumps(tasks),
                               SERVICE_PS=self.ps_responses(*responses), **env)

    # Right after a redeploy the previous, complete task is still the newest one for a moment.
    REDEPLOY = ("old|{old}|Complete 5 minutes ago\n",
                "new|{new}|Running 2 seconds ago\nold|{old}|Complete 5 minutes ago\n",
                "new|{new}|Complete 1 second ago\nold|{old}|Complete 5 minutes ago\n")

    def assert_waited_for_the_new_task(self, result, calls):
        self.assertEqual(0, result.returncode, result.stderr)
        polls = [call for call in calls if call[:2] == ["service", "ps"]]
        self.assertEqual(3, len(polls))
        self.assertIn("datacraft_airflow-init", polls[0])
        self.assertFalse(any(call[:1] == ["exec"] for call in calls))

    def test_init_waits_for_the_current_specs_task_to_complete(self):
        new, old = "reg/datacraft-airflow:2@sha256:new", "reg/datacraft-airflow:1@sha256:old"
        # As a stack deploy on a real engine leaves them (2026-10-26): the service spec carries the
        # stop grace period and an empty DNS configuration, which the tasks' copies leave out.
        service = {"Image": new, "Env": ["A=1"], "StopGracePeriod": 10000000000, "DNSConfig": {}, "Init": False}
        tasks = {"old": {"Image": old, "Env": ["A=1"]}, "new": {"Image": new, "Env": ["A=1"]}}
        result, calls = self.run_init(service, tasks, *(r.format(old=old, new=new) for r in self.REDEPLOY))
        self.assert_waited_for_the_new_task(result, calls)

    def test_init_waits_for_the_task_of_a_redeploy_that_changed_only_env(self):
        # A new POSTGRES_PASSWORD in .env, redeployed under the same tag: the old complete task has
        # the same image, so an image comparison took it for the current one.
        image = "reg/datacraft-airflow:2@sha256:same"
        service = {"Image": image, "Env": ["POSTGRES_PASSWORD=rotated"], "StopGracePeriod": 10000000000}
        tasks = {"old": {"Image": image, "Env": ["POSTGRES_PASSWORD=before"]},
                 "new": {"Image": image, "Env": ["POSTGRES_PASSWORD=rotated"]}}
        result, calls = self.run_init(service, tasks, *(r.format(old=image, new=image) for r in self.REDEPLOY))
        self.assert_waited_for_the_new_task(result, calls)
        self.assertNotIn("rotated", " ".join(" ".join(call) for call in calls))

    def test_init_times_out_with_diagnostics(self):
        image = "reg/datacraft-airflow:2"
        result, _ = self.run_init({"Image": image}, {"t1": {"Image": image}}, f"t1|{image}|Failed 3 seconds ago\n",
                                  DATACRAFT_INIT_TIMEOUT="0")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("docker service logs datacraft_airflow-init", result.stderr)
        self.assertIn("Failed", result.stderr)

    def test_init_requires_a_deployed_stack(self):
        result, calls = self.run_script("airflow-init.sh", SERVICE_SPEC="", SERVICE_PS="")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("swarm-deploy.sh", result.stderr)
        self.assertFalse(any(call[:2] == ["service", "ps"] for call in calls))

    def test_init_no_longer_borrows_the_scheduler_container(self):
        text = (DEPLOY / "scripts" / "airflow-init.sh").read_text(encoding="utf-8")
        self.assertNotIn("docker exec", text)


if __name__ == "__main__":
    unittest.main()
