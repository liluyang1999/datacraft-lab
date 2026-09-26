"""The CI test-count gate must fail when a module's suite silently stops running."""

import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "tests" / "ci" / "check_test_counts.py"

_spec = importlib.util.spec_from_file_location("check_test_counts", SCRIPT)
assert _spec is not None and _spec.loader is not None, SCRIPT
counts = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(counts)


def reports_dir(root, module):
    return root / counts.MODULE_DIRS[module] / "target" / "surefire-reports"


def write_report(root, module, name, tests, skipped=None, skipped_cases=0):
    """Writes a JUnit XML report with one <testcase> per test, the first skipped_cases of them
    marked <skipped/>. Surefire also writes the suite-level skipped attribute; ScalaTest does not,
    so skipped=None produces ScalaTest's form."""
    reports = reports_dir(root, module)
    reports.mkdir(parents=True, exist_ok=True)
    skipped_attribute = "" if skipped is None else f' skipped="{skipped}"'
    cases = "".join(
        f'\n  <testcase name="test {index}" classname="{name}" time="0.0">'
        + ("\n    <skipped/>" if index < skipped_cases else "")
        + "\n</testcase>"
        for index in range(tests))
    (reports / f"TEST-{name}.xml").write_text(
        f'<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{name}" tests="{tests}"{skipped_attribute} errors="0" failures="0">'
        f"{cases}\n</testsuite>\n",
        encoding="utf-8")
    return reports


def write_reports_at_floors(root):
    """Every module meets its floor exactly; one is split across files, ScalaTest adds empties."""
    for module, floor in counts.FLOORS.items():
        if module == "datacraft-spark":
            write_report(root, module, "com.example.FirstSpec", floor - 1)
            write_report(root, module, "com.example.SecondSpec", 1)
            write_report(root, module, "org.scalatest.tools.DiscoverySuite-1", 0)
        else:
            write_report(root, module, f"com.example.{module}.Test", floor, skipped=0)


def run_check(root):
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        status = counts.main([str(root)])
    return status, output.getvalue()


class CheckTestCountsTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        write_reports_at_floors(self.root)

    def test_modules_exactly_at_their_floors_pass(self):
        status, output = run_check(self.root)
        self.assertEqual(0, status, output)
        self.assertNotIn("::error::", output)
        for module in counts.FLOORS:
            self.assertIn(module, output)

    def test_module_that_ran_no_tests_fails_and_is_named(self):
        for report in (reports_dir(self.root, "datacraft-io")).iterdir():
            report.unlink()
        write_report(self.root, "datacraft-io", "com.example.io.Test", 0, skipped=0)
        status, output = run_check(self.root)
        self.assertEqual(1, status, output)
        floor = counts.FLOORS["datacraft-io"]
        self.assertIn(f"::error::datacraft-io executed 0 tests, expected at least {floor}", output)

    def test_module_without_reports_directory_fails(self):
        reports = reports_dir(self.root, "datacraft-common")
        for report in reports.iterdir():
            report.unlink()
        reports.rmdir()
        status, output = run_check(self.root)
        self.assertEqual(1, status, output)
        self.assertIn("::error::datacraft-common: no reports directory", output)

    def test_module_with_empty_reports_directory_fails(self):
        for report in (reports_dir(self.root, "datacraft-api")).iterdir():
            report.unlink()
        status, output = run_check(self.root)
        self.assertEqual(1, status, output)
        self.assertIn("::error::datacraft-api executed 0 tests", output)

    def test_surefire_skipped_or_aborted_tests_do_not_count(self):
        floor = counts.FLOORS["datacraft-io"]
        for report in (reports_dir(self.root, "datacraft-io")).iterdir():
            report.unlink()
        write_report(self.root, "datacraft-io", "com.example.io.Test", floor, skipped=1,
                     skipped_cases=1)
        status, output = run_check(self.root)
        self.assertEqual(1, status, output)
        self.assertIn(f"datacraft-io: executed {floor - 1} (skipped 1)", output)
        self.assertIn(f"::error::datacraft-io executed {floor - 1} tests", output)

    def test_scalatest_canceled_tests_do_not_count(self):
        floor = counts.FLOORS["datacraft-cli"]
        for report in (reports_dir(self.root, "datacraft-cli")).iterdir():
            report.unlink()
        write_report(self.root, "datacraft-cli", "com.example.CliSpec", floor, skipped_cases=1)
        write_report(self.root, "datacraft-cli", "org.scalatest.tools.DiscoverySuite-1", 0)
        status, output = run_check(self.root)
        self.assertEqual(1, status, output)
        self.assertIn(f"datacraft-cli: executed {floor - 1} (skipped 1)", output)
        self.assertIn(f"::error::datacraft-cli executed {floor - 1} tests", output)

    def test_every_scalatest_test_canceled_fails(self):
        floor = counts.FLOORS["datacraft-spark"]
        for report in (reports_dir(self.root, "datacraft-spark")).iterdir():
            report.unlink()
        write_report(self.root, "datacraft-spark", "com.example.SparkSpec", floor,
                     skipped_cases=floor)
        status, output = run_check(self.root)
        self.assertEqual(1, status, output)
        self.assertIn("::error::datacraft-spark executed 0 tests", output)

    def test_testsuites_root_element_is_summed(self):
        reports = reports_dir(self.root, "datacraft-config")
        for report in reports.iterdir():
            report.unlink()
        floor = counts.FLOORS["datacraft-config"]
        (reports / "TEST-aggregate.xml").write_text(
            f'<testsuites><testsuite name="a" tests="{floor - 1}" skipped="0"/>'
            '<testsuite name="b" tests="1"/></testsuites>', encoding="utf-8")
        status, output = run_check(self.root)
        self.assertEqual(0, status, output)
        self.assertIn(f"datacraft-config: executed {floor} (skipped 0)", output)

    def test_unreadable_report_fails_without_a_traceback(self):
        reports = reports_dir(self.root, "datacraft-engine")
        (reports / "TEST-truncated.xml").write_text('<testsuite tests="1"', encoding="utf-8")
        (reports_dir(self.root, "datacraft-cli") / "TEST-bad.xml").write_text(
            '<testsuite tests="many"/>', encoding="utf-8")
        status, output = run_check(self.root)
        self.assertEqual(1, status, output)
        self.assertIn("::error::datacraft-engine: cannot parse TEST-truncated.xml", output)
        self.assertIn("::error::datacraft-cli: TEST-bad.xml: non-integer tests='many'", output)

    def test_command_line_reports_errors_and_exit_status(self):
        passing = subprocess.run([sys.executable, "-B", str(SCRIPT), str(self.root)],
                                 capture_output=True, text=True)
        self.assertEqual(0, passing.returncode, passing.stdout + passing.stderr)
        write_report(self.root, "datacraft-spark", "com.example.FirstSpec", 0)
        failing = subprocess.run([sys.executable, "-B", str(SCRIPT), str(self.root)],
                                 capture_output=True, text=True)
        self.assertEqual(1, failing.returncode, failing.stdout + failing.stderr)
        self.assertIn("::error::datacraft-spark executed 1 tests", failing.stdout)
        self.assertEqual("", failing.stderr)

    def test_every_reactor_module_has_a_positive_floor(self):
        pom = ET.parse(REPOSITORY / "pom.xml").getroot()
        modules = {(module.text or "").strip() for module in pom.findall("{*}modules/{*}module")}
        self.assertTrue(modules)
        # Every reactor module has a directory mapping and a floor; a moved module updates both.
        self.assertEqual(modules, set(counts.MODULE_DIRS.values()))
        self.assertEqual(set(counts.MODULE_DIRS), set(counts.FLOORS))
        for module, directory in counts.MODULE_DIRS.items():
            self.assertEqual(module, Path(directory).name)
        for module, floor in counts.FLOORS.items():
            self.assertIsInstance(floor, int, module)
            self.assertGreater(floor, 0, module)


if __name__ == "__main__":
    unittest.main()
