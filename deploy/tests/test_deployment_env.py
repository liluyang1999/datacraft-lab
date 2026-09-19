"""Deployment preparation must preserve existing secrets and reject unsafe defaults."""

import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "deployment_env.py"


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
        spec = importlib.util.spec_from_file_location("deployment_env", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        env = {"POSTGRES_PASSWORD": "a" * 40, "AIRFLOW_ADMIN_PASSWORD": "b" * 40,
               "AIRFLOW__API__SECRET_KEY": "c" * 40,
               "AIRFLOW__API_AUTH__JWT_SECRET": "d" * 40,
               "AIRFLOW__CORE__FERNET_KEY": base64.urlsafe_b64encode(b"e" * 32).decode()}
        services = {"postgres": {"environment": {"POSTGRES_PASSWORD": env["POSTGRES_PASSWORD"]}},
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
