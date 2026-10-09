"""cicd/images/smoke-swarm.sh against a recording docker stub: the steps it takes, what it refuses,
and that it always leaves the swarm it created. The real rehearsal needs Docker and runs in CI."""

import contextlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "cicd" / "images" / "smoke-swarm.sh"
STACK = REPOSITORY / "deploy" / "swarm" / "docker-stack.yml"

# A swarm of one node as the rehearsal sees it. Records argv and, for `stack deploy`, the settings
# swarm-deploy.sh exported. The environment turns single answers into the faults under test; a call
# the stub does not know fails, so the script cannot grow a command these tests have not seen.
DOCKER_STUB = r"""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
record = {'args': args}
env_file = os.path.join(os.environ['STUB_ROOT'], 'deploy', 'compose', '.env')
state_file = os.path.join(os.environ['STUB_ROOT'], 'stub-state.json')
state = json.load(open(state_file)) if os.path.exists(state_file) else {'runs': [], 'first_tag': None}


def setting(key):
    for line in open(env_file):
        if line.startswith(key + '='):
            return line.split('=', 1)[1].strip()
    return ''


def image(service):
    if service in ('postgres', 'redis'):
        return {'postgres': 'postgres:18', 'redis': 'redis:8'}[service] + '@sha256:base'
    tag = state['first_tag'] if service == os.environ.get('STUCK_SERVICE') else setting('DATACRAFT_TAG')
    kind = 'jvm' if service == 'datacraft-api' else 'airflow'
    return f"{setting('DATACRAFT_REGISTRY')}/datacraft-{kind}:{tag}@sha256:{kind}"


def service_of(name):
    return name[len('datacraft_'):]


status = 0
if args[:1] == ['info']:
    print(os.environ.get('SWARM_STATE', 'inactive'))
elif args[:2] == ['image', 'inspect']:
    status = 1 if args[2] == os.environ.get('MISSING_IMAGE') else 0
elif args[:2] in (['swarm', 'init'], ['swarm', 'leave'], ['node', 'ls'], ['stack', 'rm'], ['compose', 'version']):
    pass
elif args[:3] == ['node', 'inspect', 'self']:
    print('runner-1')
elif args[:1] in (['tag'], ['push'], ['rm']) or args[:2] == ['volume', 'ls']:
    pass
elif args[:2] == ['run', '--detach']:
    print('registry-container')
elif args[:2] == ['run', '--rm']:
    status = int(os.environ.get('OVERLAY_CHECK_STATUS', '0'))
elif args[:1] == ['compose'] and 'config' in args:
    env = {'POSTGRES_PASSWORD': os.environ['POSTGRES_PASSWORD'],
           'AIRFLOW_ADMIN_PASSWORD': os.environ['AIRFLOW_ADMIN_PASSWORD'],
           'AIRFLOW__API__SECRET_KEY': os.environ['AIRFLOW_API_SECRET_KEY'],
           'AIRFLOW__API_AUTH__JWT_SECRET': os.environ['AIRFLOW_JWT_SECRET'],
           'AIRFLOW__CORE__FERNET_KEY': os.environ['AIRFLOW_FERNET_KEY']}
    print(json.dumps({'services': {'postgres': {'environment': {'POSTGRES_PASSWORD': env['POSTGRES_PASSWORD']}},
                                   'airflow-scheduler': {'environment': env},
                                   'airflow-init': {'environment': env}}}))
elif args[:2] == ['stack', 'deploy']:
    record['settings'] = {key: os.environ.get(key) for key in
                          ('DATACRAFT_REGISTRY', 'DATACRAFT_TAG', 'DATACRAFT_DATA_NODE')}
    state['first_tag'] = state['first_tag'] or os.environ['DATACRAFT_TAG']
    # The script removes .env when it ends; the tests look up what it held.
    with open(os.path.join(os.environ['STUB_ROOT'], 'env-at-deploy'), 'w') as copy:
        copy.write(open(env_file).read())
elif args[:2] == ['service', 'inspect']:
    if 'ContainerSpec.Image' in args[3]:
        print(image(service_of(args[4])))
    elif 'PublishedPort' in args[3]:
        print(os.environ['AIRFLOW_PORT'])
    else:
        print(os.environ.get('PUBLISHED_API_PORTS', '0'))
elif args[:2] == ['service', 'ps']:
    service = service_of(args[-1])
    if service == 'airflow-init':
        print(image(service) + '|Complete 1 second ago')
    else:
        current = 'Starting' if service == os.environ.get('NEVER_SETTLES') else 'Running'
        print(f'{image(service)}|{current} 5 seconds ago')
elif args[:2] in (['service', 'logs'], ['stack', 'ps'], ['stack', 'services']):
    print('state from ' + ' '.join(args[:2]))
elif args[:2] == ['ps', '--quiet']:
    label = args[3]
    if label.startswith('label=com.docker.swarm.service.name='):
        print('container-of-' + service_of(label.split('=', 2)[2]))
elif args[:1] == ['exec'] and args[2:3] == ['airflow']:
    command = args[3:]
    if command[:2] == ['config', 'get-value']:
        print(os.environ.get('EXECUTOR', 'CeleryExecutor'))
    elif command[:2] == ['dags', 'trigger']:
        state['runs'].append(command[3])
    elif command[:2] == ['dags', 'state']:
        print(os.environ.get('RUN_STATE', 'success') if command[3] in state['runs'] else 'None')
    elif command[:2] not in (['dags', 'details'], ['dags', 'unpause']):
        status = 97
elif args[:1] == ['exec'] and args[2:3] in (['python'], ['sh']):
    pass
elif args[:1] == ['exec'] and args[-2:] == ['redis-cli', 'ping']:
    # Redis as deployed: an answer for the password of .env, which the client hands over in
    # REDISCLI_AUTH, and for nothing else.
    record['authenticated'] = args[1:3] == ['--env', 'REDISCLI_AUTH']
    presented = os.environ.get('REDISCLI_AUTH') if record['authenticated'] else None
    known = presented == setting('REDIS_PASSWORD') and os.environ.get('REDIS') != 'rejects everyone'
    print('PONG' if known or os.environ.get('REDIS') == 'open' else 'NOAUTH Authentication required.')
elif args[:2] == ['network', 'inspect']:
    print(os.environ.get('NETWORK_ENCRYPTED', 'true'))
else:
    print('unexpected docker call: ' + ' '.join(args), file=sys.stderr)
    status = 97
with open(os.environ['CALLS'], 'a') as out:
    out.write(json.dumps(record) + '\n')
json.dump(state, open(state_file, 'w'))
sys.exit(status)
"""


def found(pattern, text):
    """The first group of the first place pattern matches in text."""
    match = re.search(pattern, text)
    if match is None:
        raise AssertionError(f"{pattern!r} matches nothing")
    return match.group(1)


def listed(text, name):
    """The words of the bash array `name=(...)` in text."""
    return found(rf"(?m)^{name}=\(([^)]*)\)", text).split()


class SignIn(http.server.BaseHTTPRequestHandler):
    """Stands in for the Airflow API server: a token for every password but an obviously wrong one."""

    lenient = False

    def log_message(self, format, *args):  # the base class's names; requests are not logged
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        wrong = body.get("password", "").endswith("-wrong") and not self.lenient
        self.send_response(401 if wrong or self.path != "/auth/token" else 201)
        self.send_header("Content-Length", "0")
        self.end_headers()


@contextlib.contextmanager
def airflow(lenient=False):
    """The port of a running stand-in for the Airflow API server."""
    handler = type("Handler", (SignIn,), {"lenient": lenient})
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=10)


class SwarmRehearsalTests(unittest.TestCase):
    def test_it_follows_the_services_the_stack_name_and_the_dags_of_the_deployment(self):
        script = SCRIPT.read_text(encoding="utf-8")
        services = found(r"(?ms)^services:\n(.*?)^\S", STACK.read_text(encoding="utf-8"))
        deployed = set(re.findall(r"(?m)^  ([a-z][a-z0-9-]*):\s*$", services))
        # Every service of the stack is waited for, except the one that completes.
        self.assertIn("airflow-init", deployed)
        self.assertEqual(deployed - {"airflow-init"}, set(listed(script, "services")))
        # The upgrade check skips the first two, which are the services that do not use our images.
        self.assertEqual(["postgres", "redis"], listed(script, "services")[:2])
        self.assertIn("\nstack=datacraft\n", script)
        for name in ("swarm-deploy.sh", "airflow-init.sh"):
            self.assertIn("datacraft", (REPOSITORY / "deploy" / "scripts" / name).read_text(encoding="utf-8"))
            self.assertIn(f"\nbash deploy/scripts/{name}\n", script)
        dags = {path.stem for path in (REPOSITORY / "orchestration" / "airflow" / "dags").glob("datacraft_*.py")}
        self.assertEqual(dags - {"datacraft_common"}, set(listed(script, "dags")))


@unittest.skipUnless(sys.platform == "linux", "runs the script with bash and GNU sed")
class SwarmRehearsalRunTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / "cicd" / "images").mkdir(parents=True)
        shutil.copy(REPOSITORY / "cicd" / "lib.sh", self.root / "cicd" / "lib.sh")
        for name in (SCRIPT.name, "check_access.py"):
            shutil.copy(SCRIPT.with_name(name), self.root / "cicd" / "images" / name)
        for part in ("scripts", "compose", "swarm"):
            shutil.copytree(REPOSITORY / "deploy" / part, self.root / "deploy" / part,
                            ignore=shutil.ignore_patterns(".env"))
        self.env_file = self.root / "deploy" / "compose" / ".env"
        stubs = self.root / "bin"
        stubs.mkdir()
        (stubs / "docker").write_text(DOCKER_STUB)
        # The registry is not there, and no wait needs to take time.
        for name in ("curl", "sleep"):
            (stubs / name).write_text("#!/bin/sh\nexit 0\n")
        for stub in stubs.iterdir():
            stub.chmod(0o755)
        self.calls = self.root / "calls.jsonl"
        self.calls.write_text("")
        self.env = {key: value for key, value in os.environ.items()
                    if key not in ("CI", "GITHUB_ACTIONS", "GITHUB_RUN_ID")}
        self.env.update(PATH=f"{stubs}{os.pathsep}{os.environ['PATH']}", CALLS=str(self.calls),
                        STUB_ROOT=str(self.root), GITHUB_RUN_ID="77", DATACRAFT_INIT_POLL_SECONDS="0")

    def rehearse(self, lenient_sign_in=False, **env):
        with airflow(lenient_sign_in) as port:
            result = subprocess.run(["bash", str(self.root / "cicd" / "images" / SCRIPT.name)],
                                    env={**self.env, "AIRFLOW_PORT": str(port), **env},
                                    stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=300)
        records = [json.loads(line) for line in self.calls.read_text().splitlines()]
        return result, [record["args"] for record in records], records

    def assert_torn_down(self, calls):
        """The swarm this run created is left, with its stack and registry removed, and no .env stays."""
        self.assertIn(["stack", "rm", "datacraft"], calls)
        self.assertEqual(["swarm", "leave", "--force"], calls[[call[:2] for call in calls].index(["swarm", "leave"])])
        self.assertIn(["rm", "--force", "--volumes", "datacraft-rehearsal-registry-77"], calls)
        self.assertLess(calls.index(["stack", "rm", "datacraft"]), calls.index(["swarm", "leave", "--force"]))
        self.assertFalse(self.env_file.exists())

    def assert_refused(self, result, calls, message):
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertEqual(f"error: {message}\n", result.stderr)
        self.assertFalse(any(call[:1] in (["swarm"], ["stack"], ["run"], ["rm"]) for call in calls), calls)

    def test_it_runs_on_ci_runners_only(self):
        result, calls, _ = self.rehearse()
        self.assert_refused(result, calls, "Run only on an isolated CI runner.")
        self.assertEqual([], calls)

    def test_it_never_touches_an_existing_env_file(self):
        self.env_file.write_text("POSTGRES_PASSWORD=kept\n")
        result, calls, _ = self.rehearse(CI="true")
        self.assert_refused(result, calls, "Preserving existing .env; refusing the rehearsal.")
        self.assertEqual("POSTGRES_PASSWORD=kept\n", self.env_file.read_text())
        self.assertEqual([], calls)

    def test_it_leaves_a_swarm_it_did_not_create_alone(self):
        result, calls, _ = self.rehearse(CI="true", SWARM_STATE="active")
        self.assert_refused(result, calls, "This Docker engine is already part of a swarm; refusing the rehearsal.")
        self.assertEqual([["info", "--format", "{{.Swarm.LocalNodeState}}"]], calls)

    def test_it_needs_the_images_before_it_changes_anything(self):
        result, calls, _ = self.rehearse(CI="true", MISSING_IMAGE="datacraft/jvm:latest")
        self.assert_refused(result, calls,
                            "datacraft/jvm:latest is not built; run cicd/images/smoke-images.sh first.")
        self.assertFalse(self.env_file.exists())

    def test_it_deploys_checks_upgrades_and_tears_down(self):
        result, calls, records = self.rehearse(CI="true")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        # One node, a registry on loopback and both images under an exact tag.
        self.assertIn(["swarm", "init", "--advertise-addr", "127.0.0.1"], calls)
        self.assertIn(["run", "--detach", "--name", "datacraft-rehearsal-registry-77", "--publish",
                       "127.0.0.1:5000:5000", "registry:3"], calls)
        pushed = [call[-1] for call in calls if call[:1] == ["push"]]
        self.assertEqual([f"127.0.0.1:5000/datacraft-{image}:rehearsal-77{suffix}"
                          for suffix in ("", "-upgrade") for image in ("airflow", "jvm")], pushed)
        # Two deployments through the operator's script, each with the settings an operator writes.
        deployments = [record["settings"] for record in records if record["args"][:2] == ["stack", "deploy"]]
        self.assertEqual([{"DATACRAFT_REGISTRY": "127.0.0.1:5000", "DATACRAFT_TAG": tag,
                           "DATACRAFT_DATA_NODE": "runner-1"} for tag in ("rehearsal-77", "rehearsal-77-upgrade")],
                         deployments)
        # The engine API is asked from a container on the overlay network, with the stack's .env.
        overlay = next(call for call in calls if call[:2] == ["run", "--rm"])
        self.assertEqual(["--network", "datacraft_datacraft-net", "--env-file", str(self.env_file)], overlay[2:6])
        self.assertEqual(["--api", "http://datacraft-api:8080"], overlay[-2:])
        # One run before the upgrade and one after it, both through the scheduler's container.
        triggered = [call for call in calls if call[3:5] == ["dags", "trigger"]]
        self.assertEqual([["exec", "container-of-airflow-scheduler", "airflow", "dags", "trigger", "--run-id", run,
                           "datacraft_engine_jobs"] for run in ("swarm-rehearsal", "swarm-rehearsal-upgraded")],
                         triggered)
        self.assertLess(calls.index(triggered[0]), [call[:2] for call in calls].index(["stack", "deploy"], 30))
        # Redis is asked twice in its own container: without the password, then with it in the
        # client's environment, never on a command line.
        pings = [record for record in records if record["args"][-2:] == ["redis-cli", "ping"]]
        self.assertEqual([False, True], [record["authenticated"] for record in pings])
        self.assertTrue(all(record["args"][-3] == "container-of-redis" for record in pings))
        password = found(r"(?m)^REDIS_PASSWORD=(.+)$", (self.root / "env-at-deploy").read_text())
        self.assertFalse(any(password in argument for call in calls for argument in call))
        self.assertIn(["network", "inspect", "--format", '{{index .Options "encrypted"}}', "datacraft_datacraft-net"],
                      calls)
        self.assert_torn_down(calls)
        self.assertIn("Swarm stack rehearsed on one node: ", result.stdout)
        self.assertNotIn("--- Swarm:", result.stdout)

    def assert_failed(self, message, lenient_sign_in=False, **env):
        result, calls, _ = self.rehearse(lenient_sign_in, CI="true", **env)
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn(f"error: {message}\n", result.stderr)
        # The state of the stack is reported before the stack is removed.
        self.assertIn("--- Swarm: services\nstate from stack services\n", result.stdout)
        self.assertLess(calls.index(["service", "logs", "--raw", "--tail", "30", "datacraft_airflow-init"]),
                        calls.index(["stack", "rm", "datacraft"]))
        self.assert_torn_down(calls)
        self.assertNotIn("Swarm stack rehearsed", result.stdout)
        return calls

    def test_a_service_that_does_not_settle_fails_the_rehearsal(self):
        calls = self.assert_failed(
            "First deployment: after 0 s these services do not run the tasks of their current specification: "
            "airflow-worker ", NEVER_SETTLES="airflow-worker", DATACRAFT_REHEARSAL_TIMEOUT="0")
        self.assertFalse(any(call[3:5] == ["dags", "trigger"] for call in calls))

    def test_a_published_or_open_engine_api_fails_the_rehearsal(self):
        self.assert_failed("The stack publishes a port of datacraft-api.", PUBLISHED_API_PORTS="1")
        self.calls.write_text("")
        (self.root / "stub-state.json").unlink()
        self.assert_failed("datacraft-api on the overlay network does not answer its token, or answers without it.",
                           OVERLAY_CHECK_STATUS="1")

    def test_a_sign_in_that_accepts_any_password_fails_the_rehearsal(self):
        self.assert_failed("The Airflow UI on the routing mesh does not sign the generated admin in, or signs "
                           "anyone in.", lenient_sign_in=True)

    def test_a_redis_without_its_password_or_an_open_network_fails_the_rehearsal(self):
        self.assert_failed("Redis answers a client that has not presented the password: PONG", REDIS="open")
        self.calls.write_text("")
        (self.root / "stub-state.json").unlink()
        self.assert_failed("Redis does not answer the REDIS_PASSWORD of .env.", REDIS="rejects everyone")
        self.calls.write_text("")
        (self.root / "stub-state.json").unlink()
        self.assert_failed("The overlay network datacraft_datacraft-net was not created encrypted.",
                           NETWORK_ENCRYPTED="<no value>")

    def test_another_executor_or_a_failed_run_fails_the_rehearsal(self):
        self.assert_failed("The scheduler of the Swarm stack does not use CeleryExecutor.", EXECUTOR="LocalExecutor")
        self.calls.write_text("")
        (self.root / "stub-state.json").unlink()
        self.assert_failed("Run swarm-rehearsal of datacraft_engine_jobs failed.", RUN_STATE="failed")

    def test_a_service_left_on_the_old_tag_fails_the_upgrade(self):
        calls = self.assert_failed("datacraft_airflow-worker was not moved to the tag rehearsal-77-upgrade.",
                                   STUCK_SERVICE="airflow-worker")
        self.assertEqual(2, sum(call[:2] == ["stack", "deploy"] for call in calls))
        self.assertFalse(any("swarm-rehearsal-upgraded" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
