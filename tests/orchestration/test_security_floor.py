"""The security floor covers the fixed releases installed over the Airflow constraints."""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "cicd" / "airflow" / "check_security_floor.py"
OVERRIDES = REPOSITORY / "orchestration" / "airflow" / "security-overrides.txt"

_spec = importlib.util.spec_from_file_location("check_security_floor", SCRIPT)
assert _spec is not None and _spec.loader is not None, SCRIPT
floor = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(floor)


def run_main(installed):
    output = io.StringIO()
    with mock.patch.object(floor, "version", side_effect=lambda name: installed[name]), \
            contextlib.redirect_stdout(output):
        status = floor.main()
    return status, output.getvalue()


class SecurityFloorTests(unittest.TestCase):
    def overrides(self, text):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "security-overrides.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_every_override_is_a_floor(self):
        floors = floor.floors()
        self.assertEqual({"apache-airflow": "3.3.2", "apache-airflow-providers-fab": "3.9.0"},
                         {name: floors[name] for name in floor.FLOORS})
        for name, release in floor.override_floors(OVERRIDES).items():
            self.assertEqual(release, floors[name], name)
        self.assertIn("certifi", floors)
        self.assertIn("urllib3", floors)

    def test_a_release_below_an_override_fails(self):
        # What the official apache/airflow:3.3.2 image and its constraints install.
        installed = dict(floor.floors())
        installed.update(certifi="2026.7.22", urllib3="2.7.0")
        status, output = run_main(installed)
        self.assertEqual(1, status, output)
        self.assertIn("::error::certifi 2026.7.22 is below the security floor", output)
        self.assertIn("::error::urllib3 2.7.0 is below the security floor", output)

    def test_the_floors_and_newer_releases_pass(self):
        installed = dict(floor.floors())
        self.assertEqual(0, run_main(installed)[0])
        installed["urllib3"] = "2.9.0"
        self.assertEqual(0, run_main(installed)[0])

    def test_the_override_file_holds_exact_pins_only(self):
        self.assertEqual({"certifi": "2026.10.23", "urllib3": "2.8.0"},
                         floor.override_floors(self.overrides(
                             "# Fixed releases.\n\ncertifi==2026.10.23\nurllib3==2.8.0  # proxy TLS\n")))
        for text in ("certifi>=2026.10.23\n", "urllib3\n", "# only a comment\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                floor.override_floors(self.overrides(text))


if __name__ == "__main__":
    unittest.main()
