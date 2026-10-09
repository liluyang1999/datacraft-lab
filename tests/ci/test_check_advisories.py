"""The advisory check finds every pin, matches advisories to it and never passes unasked.

No test here reaches the network: OSV's answers are stubbed with the shapes it really returns.
"""

import contextlib
from email.message import Message
import importlib.util
import io
from pathlib import Path
import re
import tempfile
import unittest
from unittest import mock
import urllib.error
import xml.etree.ElementTree as ET

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "cicd" / "security" / "check_advisories.py"

_spec = importlib.util.spec_from_file_location("check_advisories", SCRIPT)
assert _spec is not None and _spec.loader is not None, SCRIPT
advisories = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(advisories)

POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <properties>
    <scala.binary.version>2.13</scala.binary.version>
    <scopt.version>4.2.0</scopt.version>
    <jackson.version>2.22.3</jackson.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.github.scopt</groupId>
        <artifactId>scopt_${scala.binary.version}</artifactId>
        <version>${scopt.version}</version>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson</groupId>
        <artifactId>jackson-bom</artifactId>
        <version>${jackson.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
"""
SHA = "3d3c42e5aac5ba805825da76410c181273ba90b1"


def run_main(*arguments):
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        status = advisories.main(list(arguments))
    return status, output.getvalue()


class PinnedVersionsTests(unittest.TestCase):
    def tree(self, pom=POM):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        files = {
            "pom.xml": pom,
            "cicd/airflow/install-airflow.sh": "pip install 'apache-airflow==3.3.2'\npip install 'pyspark==4.2.0'\n",
            "cicd/airflow/check_security_floor.py": 'FLOORS = {"apache-airflow": "3.3.2", '
                                                    '"apache-airflow-providers-fab": "3.9.0"}\n',
            "cicd/lint/install-shellcheck.sh": "package_version=0.11.0.1\n",
            "Makefile": "PYRIGHT ?= npx --yes pyright@1.1.414 --warnings\n",
            ".github/workflows/ci.yml": f"steps:\n  - uses: actions/checkout@{SHA} # v7.0.1\n"
                                        f"  - name: x\n    uses: actions/setup-java@{SHA} # v6.0.1\n"
                                        "  - uses: ./.github/actions/local\n",
        }
        for name, content in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(content, encoding="utf-8")
        return root

    def test_every_kind_of_pin_is_found_with_the_file_that_holds_it(self):
        self.assertEqual([
            ("GitHub Actions", "actions/checkout", "7.0.1", ".github/workflows/ci.yml"),
            ("GitHub Actions", "actions/setup-java", "6.0.1", ".github/workflows/ci.yml"),
            ("Maven", "com.fasterxml.jackson.core:jackson-core", "2.22.3", "pom.xml"),
            ("Maven", "com.fasterxml.jackson.core:jackson-databind", "2.22.3", "pom.xml"),
            ("Maven", "com.github.scopt:scopt_2.13", "4.2.0", "pom.xml"),
            ("PyPI", "apache-airflow", "3.3.2", "cicd/airflow/install-airflow.sh"),
            ("PyPI", "apache-airflow-providers-fab", "3.9.0", "cicd/airflow/check_security_floor.py"),
            ("PyPI", "pyspark", "4.2.0", "cicd/airflow/install-airflow.sh"),
            ("PyPI", "shellcheck-py", "0.11.0.1", "cicd/lint/install-shellcheck.sh"),
            ("npm", "pyright", "1.1.414", "Makefile"),
        ], advisories.pinned(self.tree()))

    def test_a_pin_that_cannot_be_read_is_an_error_not_a_shorter_list(self):
        cases = {
            "cannot resolve": POM.replace("<scopt.version>4.2.0</scopt.version>", ""),
            "list the members to check": POM.replace("jackson-bom", "other-bom"),
            "manages no dependency": "<project/>",
        }
        for message, pom in cases.items():
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                advisories.pinned(self.tree(pom))
        for name in ("cicd/airflow/install-airflow.sh", "cicd/airflow/check_security_floor.py",
                     "cicd/lint/install-shellcheck.sh", "Makefile", ".github/workflows/ci.yml"):
            root = self.tree()
            (root / name).write_text("# reworded\n", encoding="utf-8")
            with self.subTest(file=name), self.assertRaisesRegex(ValueError, "no pinned"):
                advisories.pinned(root)
            status, output = run_main(str(root))
            self.assertEqual(2, status, output)
            self.assertIn("::error::cannot read the pinned versions", output)

    def test_the_repository_pins_are_all_covered(self):
        found = advisories.pinned(REPOSITORY)
        names = {(ecosystem, name) for ecosystem, name, _, _ in found}
        pom = ET.parse(REPOSITORY / "pom.xml").getroot()
        managed = pom.findall("{*}dependencyManagement/{*}dependencies/{*}dependency")
        imported = [entry for entry in managed if entry.findtext("{*}scope") == "import"]
        self.assertEqual(1, len(imported))
        # Every managed dependency is one pin; the BOM stands for its two members.
        self.assertEqual(len(managed) - 1 + 2, sum(1 for ecosystem, _ in names if ecosystem == "Maven"))
        self.assertIn(("Maven", "org.apache.spark:spark-sql_2.13"), names)
        workflows = "".join(path.read_text(encoding="utf-8")
                            for path in (REPOSITORY / ".github" / "workflows").glob("*.y*ml"))
        used = set(re.findall(r"uses:\s+([^@\s./][^@\s]*)@", workflows))
        self.assertTrue(used)
        self.assertEqual(used, {name for ecosystem, name in names if ecosystem == "GitHub Actions"})
        for expected in (("PyPI", "apache-airflow"), ("PyPI", "pyspark"), ("PyPI", "shellcheck-py"),
                         ("PyPI", "apache-airflow-providers-fab"), ("npm", "pyright")):
            self.assertIn(expected, names)
        for _, name, version, _ in found:
            self.assertRegex(version, r"^\d+(\.\d+)*$", name)

    def test_list_prints_the_pins_and_asks_nothing(self):
        with mock.patch.object(advisories, "call", side_effect=AssertionError("asked OSV")):
            status, output = run_main("--list", str(self.tree()))
        self.assertEqual(0, status)
        self.assertIn("Maven: com.github.scopt:scopt_2.13 4.2.0 (pom.xml)", output.splitlines())
        self.assertEqual(10, len(output.splitlines()))


class AdvisoryMatchingTests(unittest.TestCase):
    def test_action_ranges_follow_the_osv_evaluation(self):
        fixed = [{"type": "ECOSYSTEM", "events": [{"introduced": "0"}, {"fixed": "46.0.1"}]}]
        window = [{"type": "ECOSYSTEM", "events": [{"introduced": "4.0.0"}, {"fixed": "4.1.3"}]}]
        last = [{"type": "ECOSYSTEM", "events": [{"introduced": "2"}, {"last_affected": "2.5.0"}]}]
        twice = [{"type": "ECOSYSTEM", "events": [{"introduced": "1.0.0"}, {"fixed": "1.2.0"},
                                                    {"introduced": "2.0.0"}, {"fixed": "2.0.3"}]}]
        cases = [
            (fixed, {"45.0.7": True, "v45": True, "46.0.1": False, "46.1": False, "41": True}),
            (window, {"3.9.9": False, "4.0.0": True, "4.1.2": True, "4.1.3": False, "8.0.2": False}),
            (last, {"1.9": False, "2.5.0": True, "2.5.1": False}),
            (twice, {"1.1.0": True, "1.2.0": False, "1.9.0": False, "2.0.2": True, "2.0.3": False}),
            (window + fixed, {"45.0.7": True, "46.0.1": False}),
            ([], {"1.0.0": False}),
        ]
        for ranges, expectations in cases:
            for version, expected in expectations.items():
                with self.subTest(ranges=ranges, version=version):
                    self.assertEqual(expected, advisories.in_ranges(version, ranges))

    def test_query_maps_batch_answers_by_position_and_matches_actions_itself(self):
        packages = [("GitHub Actions", "Actions/Download-Artifact", "4.1.2", "ci.yml"),
                    ("GitHub Actions", "actions/checkout", "7.0.1", "ci.yml"),
                    ("Maven", "g:clean", "1.0", "pom.xml"),
                    ("Maven", "g:vulnerable", "2.0", "pom.xml"),
                    ("PyPI", "also-vulnerable", "3.0", "install.sh")]
        asked = []

        def answer(path, payload=None):
            assert payload is not None, path
            asked.append((path, payload))
            if path == "/querybatch":
                self.assertEqual([{"package": {"ecosystem": "Maven", "name": "g:clean"}, "version": "1.0"},
                                  {"package": {"ecosystem": "Maven", "name": "g:vulnerable"}, "version": "2.0"},
                                  {"package": {"ecosystem": "PyPI", "name": "also-vulnerable"}, "version": "3.0"}],
                                 payload["queries"])
                return {"results": [{}, {"vulns": [{"id": "GHSA-1"}, {"id": "GHSA-2"}]},
                                    {"vulns": [{"id": "PYSEC-3"}]}]}
            self.assertEqual("/query", path)
            self.assertNotIn("version", payload)
            if payload["package"]["name"].lower() != "actions/download-artifact":
                return {}
            return {"vulns": [
                {"id": "GHSA-cxww", "affected": [{
                    "package": {"ecosystem": "GitHub Actions", "name": "actions/download-artifact"},
                    "ranges": [{"type": "ECOSYSTEM", "events": [{"introduced": "4.0.0"}, {"fixed": "4.1.3"}]}]}]},
                {"id": "GHSA-other-package", "affected": [{
                    "package": {"ecosystem": "npm", "name": "actions/download-artifact"},
                    "ranges": [{"type": "ECOSYSTEM", "events": [{"introduced": "0"}]}]}]},
                {"id": "GHSA-listed", "affected": [{
                    "package": {"ecosystem": "GitHub Actions", "name": "actions/download-artifact"},
                    "versions": ["v4.1.2"]}]},
                {"id": "GHSA-fixed-earlier", "affected": [{
                    "package": {"ecosystem": "GitHub Actions", "name": "actions/download-artifact"},
                    "ranges": [{"type": "ECOSYSTEM", "events": [{"introduced": "0"}, {"fixed": "3.0.0"}]}]}]},
            ]}

        with mock.patch.object(advisories, "call", side_effect=answer):
            found = advisories.query(packages)
        self.assertEqual({("GitHub Actions", "Actions/Download-Artifact", "4.1.2"): ["GHSA-cxww", "GHSA-listed"],
                          ("Maven", "g:vulnerable", "2.0"): ["GHSA-1", "GHSA-2"],
                          ("PyPI", "also-vulnerable", "3.0"): ["PYSEC-3"]}, found)
        self.assertEqual(["/querybatch", "/query", "/query"], [path for path, _ in asked])

    def test_an_answer_that_does_not_cover_every_query_is_not_trusted(self):
        with mock.patch.object(advisories, "call", return_value={"results": [{}]}), \
                self.assertRaisesRegex(advisories.LookupFailed, "1 of 2 queries"):
            advisories.query([("Maven", "g:a", "1", "pom.xml"), ("Maven", "g:b", "1", "pom.xml")])

    def test_osv_being_unreachable_or_refusing_is_a_failed_lookup(self):
        with mock.patch.object(advisories.time, "sleep"), \
                mock.patch.object(advisories.urllib.request, "urlopen",
                                  side_effect=urllib.error.URLError("no route")) as opened, \
                self.assertRaisesRegex(advisories.LookupFailed, "not reachable"):
            advisories.call("/querybatch", {"queries": []})
        self.assertEqual(4, opened.call_count)
        refused = urllib.error.HTTPError("https://api.osv.dev/v1/query", 400, "Bad Request", Message(), None)
        with mock.patch.object(advisories.urllib.request, "urlopen", side_effect=refused) as opened, \
                self.assertRaisesRegex(advisories.LookupFailed, "HTTP 400"):
            advisories.call("/query", {})
        self.assertEqual(1, opened.call_count)


class ExitStatusTests(unittest.TestCase):
    def test_no_advisory_passes_and_says_how_many_pins_were_checked(self):
        with mock.patch.object(advisories, "query", return_value={}):
            status, output = run_main(str(REPOSITORY))
        self.assertEqual(0, status, output)
        self.assertRegex(output, r"^no known advisory for \d\d+ pinned versions\n$")

    def test_an_advisory_fails_and_names_the_pin_its_file_and_the_advisory(self):
        hit = {("Maven", "org.apache.spark:spark-sql_2.13",
                next(version for _, name, version, _ in advisories.pinned(REPOSITORY)
                     if name == "org.apache.spark:spark-sql_2.13")): ["GHSA-aaaa", "GHSA-bbbb"]}
        with mock.patch.object(advisories, "query", return_value=hit):
            status, output = run_main(str(REPOSITORY))
        self.assertEqual(1, status, output)
        lines = output.splitlines()
        self.assertEqual(1, len(lines), output)
        self.assertTrue(lines[0].startswith("::error::Maven org.apache.spark:spark-sql_2.13 "), lines[0])
        self.assertTrue(lines[0].endswith(
            ", pinned in pom.xml, has advisories: https://osv.dev/vulnerability/GHSA-aaaa, "
            "https://osv.dev/vulnerability/GHSA-bbbb"), lines[0])

    def test_a_failed_lookup_is_neither_a_pass_nor_an_advisory(self):
        with mock.patch.object(advisories, "query", side_effect=advisories.LookupFailed("OSV is not reachable: x")):
            status, output = run_main(str(REPOSITORY))
        self.assertEqual(2, status, output)
        self.assertRegex(output, r"^::error::OSV is not reachable: x; the \d\d+ pinned versions were not checked\n$")


if __name__ == "__main__":
    unittest.main()
