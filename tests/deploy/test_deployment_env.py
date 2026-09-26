"""Deployment preparation must preserve existing secrets and reject unsafe defaults."""

import base64
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any
import unittest

DEPLOY = Path(__file__).resolve().parents[2] / "deploy"
SCRIPT = DEPLOY / "scripts" / "deployment_env.py"
STACK_FILES = (DEPLOY / "compose" / "docker-compose.yml", DEPLOY / "swarm" / "docker-stack.yml")
SECRET_VARIABLES = ("POSTGRES_PASSWORD", "AIRFLOW_FERNET_KEY", "AIRFLOW_API_SECRET_KEY", "AIRFLOW_JWT_SECRET")


def load_module():
    spec = importlib.util.spec_from_file_location("deployment_env", SCRIPT)
    assert spec is not None and spec.loader is not None, SCRIPT
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def valid_services() -> dict[str, Any]:
    env = {"POSTGRES_PASSWORD": "a" * 40, "AIRFLOW_ADMIN_PASSWORD": "b" * 40,
           "AIRFLOW__API__SECRET_KEY": "c" * 40, "AIRFLOW__API_AUTH__JWT_SECRET": "d" * 40,
           "AIRFLOW__CORE__FERNET_KEY": base64.urlsafe_b64encode(b"e" * 32).decode()}
    return {"postgres": {"environment": {"POSTGRES_PASSWORD": env["POSTGRES_PASSWORD"]}},
            "airflow-scheduler": {"environment": env}, "airflow-init": {"environment": env}}


class DeploymentEnvironmentTests(unittest.TestCase):
    def test_initialization_creates_independent_secrets_without_printing_them(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            result = subprocess.run([sys.executable, "-B", str(SCRIPT), "init", str(path)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            values = dict(line.split("=", 1) for line in path.read_text().splitlines()
                          if line and not line.startswith("#"))
            keys = ("POSTGRES_PASSWORD", "AIRFLOW_ADMIN_PASSWORD", "AIRFLOW_API_SECRET_KEY",
                    "AIRFLOW_JWT_SECRET", "AIRFLOW_FERNET_KEY")
            self.assertEqual(5, len({values[key] for key in keys}))
            self.assertEqual(32, len(base64.urlsafe_b64decode(values["AIRFLOW_FERNET_KEY"])))
            for key in keys:
                self.assertNotIn(values[key], result.stdout + result.stderr)
            before = path.read_bytes()
            again = subprocess.run([sys.executable, "-B", str(SCRIPT), "init", str(path)],
                                   capture_output=True, text=True)
            self.assertNotEqual(0, again.returncode)
            self.assertEqual(before, path.read_bytes())
            if sys.platform != "win32":
                self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_preflight_rejects_placeholder_or_inconsistent_effective_configuration(self):
        module = load_module()
        env = {"POSTGRES_PASSWORD": "a" * 40, "AIRFLOW_ADMIN_PASSWORD": "b" * 40,
               "AIRFLOW__API__SECRET_KEY": "c" * 40,
               "AIRFLOW__API_AUTH__JWT_SECRET": "d" * 40,
               "AIRFLOW__CORE__FERNET_KEY": base64.urlsafe_b64encode(b"e" * 32).decode()}
        services: dict[str, Any] = {"postgres": {"environment": {"POSTGRES_PASSWORD": env["POSTGRES_PASSWORD"]}},
                    "airflow-scheduler": {"environment": env},
                    "airflow-init": {"environment": env}}
        module.validate({"services": services})
        for key, value in [("AIRFLOW_ADMIN_PASSWORD", "change-me-admin"),
                           ("AIRFLOW__CORE__FERNET_KEY", "invalid"),
                           ("AIRFLOW__API_AUTH__JWT_SECRET", ""),
                           ("AIRFLOW__API_AUTH__JWT_SECRET", "f" * 40)]:
            broken = json.loads(json.dumps(services))
            broken["airflow-init"]["environment"][key] = value
            with self.assertRaises(ValueError):
                module.validate({"services": broken})
        services["postgres"]["environment"]["POSTGRES_PASSWORD"] = "has@reserved:chars"
        with self.assertRaises(ValueError):
            module.validate({"services": services})

    def test_preflight_rejects_publishing_the_unauthenticated_api_beyond_loopback(self):
        module = load_module()
        services = valid_services()
        port = {"mode": "ingress", "target": 8080, "published": "8088", "protocol": "tcp"}
        for ports in ([{**port, "host_ip": "127.0.0.1"}], [{**port, "host_ip": "::1"}], []):
            services["datacraft-api"] = {"ports": ports}
            module.validate({"services": services})
        for ports in ([port], [{**port, "host_ip": "0.0.0.0"}], [{**port, "host_ip": ""}],
                      [{**port, "host_ip": "127.0.0.1"}, port], ["127.0.0.1:8088:8080"], "8088:8080"):
            services["datacraft-api"] = {"ports": ports}
            with self.subTest(ports=ports), self.assertRaisesRegex(ValueError, "loopback"):
                module.validate({"services": services})

    def test_preflight_only_accepts_read_only_api_mounts(self):
        module = load_module()
        services = valid_services()
        data = {"type": "volume", "source": "datacraft-data", "target": "/opt/datacraft/data", "volume": {}}
        for volumes in ([{**data, "read_only": True}], [{"type": "tmpfs", "target": "/tmp"}], []):
            services["datacraft-api"] = {"volumes": volumes}
            module.validate({"services": services})
        for volumes in ([data], [{**data, "read_only": False}], [{**data, "type": "bind"}],
                        ["datacraft-data:/opt/datacraft/data:ro"], {"data": data}):
            services["datacraft-api"] = {"volumes": volumes}
            with self.subTest(volumes=volumes), self.assertRaisesRegex(ValueError, "read-only"):
                module.validate({"services": services})

    def test_stack_files_refuse_to_start_without_every_secret(self):
        reference = re.compile(r"\$\{(" + "|".join(SECRET_VARIABLES) + r")([^}]*)\}")
        for path in STACK_FILES:
            text = path.read_text(encoding="utf-8")
            found = set()
            for match in reference.finditer(text):
                found.add(match.group(1))
                with self.subTest(file=path.name, reference=match.group(0)):
                    self.assertTrue(match.group(2).startswith(":?"), "secrets must use ${NAME:?message}")
            self.assertEqual(set(SECRET_VARIABLES), found, path.name)

    def test_preflight_errors_never_echo_secret_or_raw_configuration(self):
        payload = {"services": {"postgres": {"environment": {"POSTGRES_PASSWORD": "PRIVATE@VALUE"}}}}
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), "check"],
                                input=json.dumps(payload), capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("PRIVATE@VALUE", result.stdout + result.stderr)

    @unittest.skipIf(sys.platform == "win32", "POSIX symlink and mode contract")
    def test_initialization_never_follows_an_existing_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / "original"
            original.write_text("preserve me")
            link = root / ".env"
            link.symlink_to(original)
            result = subprocess.run([sys.executable, "-B", str(SCRIPT), "init", str(link)],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("preserve me", original.read_text())
            self.assertTrue(link.is_symlink())


if __name__ == "__main__":
    unittest.main()
