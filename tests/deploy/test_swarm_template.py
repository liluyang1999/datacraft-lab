"""Static contracts of the Swarm and Compose templates (stdlib only: no Docker or PyYAML needed)."""

from pathlib import Path
import re
import unittest

DEPLOY = Path(__file__).resolve().parents[2] / "deploy"
DATA_NODE = 'constraints: ["node.hostname == ${DATACRAFT_DATA_NODE:?Set the persistent data hostname}"]'
HEALTHCHECKED = ("airflow-apiserver", "airflow-scheduler", "airflow-dag-processor", "airflow-triggerer")


def children(text, indent):
    """Split a YAML block into {key: block text} for the keys at exactly `indent` spaces."""
    result, name = {}, None
    for line in text.splitlines():
        match = re.match(rf"^ {{{indent}}}([\w.-]+):", line)
        if match:
            name = match.group(1)
            result[name] = []
        if name is not None:
            result[name].append(line)
    return {key: "\n".join(lines) for key, lines in result.items()}


def template(path):
    top = children(path.read_text(encoding="utf-8"), 0)
    return top, children(top["services"], 2)


def env_example():
    lines = (DEPLOY / "compose" / ".env.example").read_text(encoding="utf-8").splitlines()
    return dict(line.split("=", 1) for line in lines if line and not line.startswith("#"))


class SwarmTemplateTests(unittest.TestCase):
    def setUp(self):
        self.top, self.services = template(DEPLOY / "swarm" / "docker-stack.yml")
        self.common = self.top["x-airflow-common"]

    def test_one_shot_initializer_replaces_docker_exec(self):
        init = self.services["airflow-init"]
        self.assertIn("<<: *airflow-common", init)
        self.assertIn("entrypoint: /opt/datacraft/airflow-bootstrap.sh", init)
        self.assertRegex(init, r"restart_policy:\n\s+condition: on-failure\n\s+delay: 10s")
        self.assertIn(DATA_NODE, init)
        self.assertNotIn("docker exec", (DEPLOY / "scripts" / "airflow-init.sh").read_text(encoding="utf-8"))

    def test_every_service_has_bounded_logs(self):
        self.assertRegex(self.top["x-logging"],
                         r'&bounded-logging\n\s+driver: json-file\n\s+options:\n\s+max-size: "10m"\n\s+max-file: "3"')
        self.assertIn("logging: *bounded-logging", self.common)
        for name, block in self.services.items():
            with self.subTest(service=name):
                self.assertTrue("logging: *bounded-logging" in block or "<<: *airflow-common" in block)

    def test_airflow_healthchecks_match_the_compose_stack(self):
        _, compose = template(DEPLOY / "compose" / "docker-compose.yml")
        for name in HEALTHCHECKED:
            with self.subTest(service=name):
                stack_check = children(self.services[name], 4).get("healthcheck")
                self.assertIsNotNone(stack_check)
                self.assertEqual(children(compose[name], 4)["healthcheck"], stack_check)

    def test_worker_drains_warmly_on_the_data_node(self):
        worker = self.services["airflow-worker"]
        environment = children(worker, 4)["environment"]
        self.assertIn("<<: *airflow-env", environment)  # a bare environment block would drop the anchor
        self.assertIn('DUMB_INIT_SETSID: "0"', environment)
        self.assertIn("environment: &airflow-env", self.common)
        self.assertIn("AIRFLOW__CORE__EXECUTOR: CeleryExecutor", self.common)
        self.assertRegex(worker, r"(?m)^    stop_grace_period: \$\{DATACRAFT_TASK_TIMEOUT_MINUTES:-60\}m")
        self.assertIn("replicas: ${AIRFLOW_WORKER_REPLICAS:-1}", worker)
        self.assertIn(DATA_NODE, worker)

    def test_template_runs_one_task_at_a_time_on_the_data_node(self):
        env = env_example()
        self.assertEqual(1, int(env["AIRFLOW_WORKER_REPLICAS"]) * int(env["AIRFLOW_WORKER_CONCURRENCY"]))
        self.assertNotIn("spread across", (DEPLOY / "compose" / ".env.example").read_text(encoding="utf-8"))
        self.assertEqual("", env["DATACRAFT_TAG"])  # swarm-deploy.sh requires an exact pushed version

    def test_api_reads_the_node_local_data_volume(self):
        api = self.services["datacraft-api"]
        self.assertIn("- datacraft-data:/opt/datacraft/data:ro", api)
        self.assertIn("DATACRAFT_DATA_ROOT: /opt/datacraft/data", api)
        self.assertIn(DATA_NODE, api)
        self.assertNotIn("ports:", api)  # overlay-only: the API has no authentication
        self.assertIn("DATACRAFT_DATA_ROOT: /opt/datacraft/data", self.common)


class ComposeTemplateTests(unittest.TestCase):
    def setUp(self):
        self.top, self.services = template(DEPLOY / "compose" / "docker-compose.yml")
        self.common = self.top["x-airflow-common"]

    def test_locally_built_images_are_never_pulled(self):
        self.assertIn("pull_policy: never", self.common)
        for name, block in self.services.items():
            if "build:" in block or "<<: *airflow-common" in block:
                with self.subTest(service=name):
                    self.assertTrue("pull_policy: never" in block or "<<: *airflow-common" in block)

    def test_dags_come_from_the_image(self):
        self.assertNotIn("/opt/airflow/dags", self.common)
        self.assertIn("DATACRAFT_DATA_ROOT: /opt/datacraft/data", self.common)

    def test_api_is_loopback_only_and_reads_data_read_only(self):
        api = children(self.services["datacraft-api"], 4)
        self.assertEqual(['      - "127.0.0.1:${DATACRAFT_API_PORT:-8088}:8080"'], api["ports"].splitlines()[1:])
        self.assertIn("- datacraft-data:/opt/datacraft/data:ro", api["volumes"])
        self.assertIn("DATACRAFT_DATA_ROOT: /opt/datacraft/data", api["environment"])


if __name__ == "__main__":
    unittest.main()
