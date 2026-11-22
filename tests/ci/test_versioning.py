"""The product has one version, written once (docs/versioning.md), and every consumer reads it.

The root pom.xml `revision` is the only version literal: every module and the desktop inherit it
through `${revision}`, the CLI and the desktop read it from a filtered resource, the packaging,
the installer and the workflows read it through cicd/release/version.py. No consumer writes the
number again, and the changelog has an entry for it. Also: the release gate of the CI/CD entry
(cicd/release/deploy.py) and the installer job's outputs (cicd/desktop/release.py outputs).
"""

import importlib.util
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree

REPOSITORY = Path(__file__).resolve().parents[2]
NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, REPOSITORY / relative)
    assert spec is not None and spec.loader is not None, relative
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


version = load("version", "cicd/release/version.py")
deploy = load("deploy", "cicd/release/deploy.py")
release = load("release", "cicd/desktop/release.py")

# Files that consume the version and must never write it: build manifests, sources, scripts,
# workflows, the installer, the entry points and the tests. Documents may name the current version.
CONSUMERS = re.compile(
    r"^(?:\.github/|cicd/|deploy/|desktop/|modules/|tests/|orchestration/|Makefile$|build\.bat$|"
    r"run\.bat$|pom\.xml$)")


def tracked_files():
    listed = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                            cwd=REPOSITORY, capture_output=True, check=True, timeout=120).stdout
    return [name for name in listed.decode("utf-8", "surrogateescape").split("\0")
            if name and (REPOSITORY / name).is_file()]


class VersionSourceTests(unittest.TestCase):
    def test_the_root_pom_revision_is_a_three_number_release(self):
        current = version.product_version(REPOSITORY)
        self.assertRegex(current, r"^\d+\.\d+\.\d+$")
        root = ElementTree.parse(REPOSITORY / "pom.xml").getroot()
        self.assertEqual("${revision}", root.findtext(f"{NAMESPACE}version"))

    def test_malformed_versions_are_refused(self):
        for value in ("2.4", "2.4.1-SNAPSHOT", "02.4.1", "2.4.1.0", "65536.0.0", " 2.4.1", "v2.4.1"):
            with self.subTest(value=value), self.assertRaises(version.InvalidVersion):
                version.check(value)
        self.assertEqual("65535.0.9", version.check("65535.0.9"))

    def test_every_module_and_the_desktop_inherit_the_revision(self):
        poms = sorted(REPOSITORY.glob("modules/*/*/pom.xml")) + [REPOSITORY / "desktop/pom.xml"]
        self.assertGreaterEqual(len(poms), 9)
        for pom in poms:
            with self.subTest(pom=pom.relative_to(REPOSITORY).as_posix()):
                project = ElementTree.parse(pom).getroot()
                self.assertEqual("${revision}", project.findtext(f"{NAMESPACE}parent/{NAMESPACE}version"))
                self.assertIsNone(project.find(f"{NAMESPACE}version"), "a module sets no version")

    def test_no_consumer_writes_the_version_again(self):
        current = version.product_version(REPOSITORY)
        literal = re.compile(rf"(?<![\w.]){re.escape(current)}(?![\w.])")
        offenders = []
        for name in tracked_files():
            if not CONSUMERS.match(name) or name.endswith((".jar", ".ttf", ".png", ".ico")):
                continue
            text = (REPOSITORY / name).read_text(encoding="utf-8", errors="replace")
            for number, line in enumerate(text.splitlines(), 1):
                if name == "pom.xml" and "<revision>" in line:
                    continue
                if literal.search(line):
                    offenders.append(f"{name}:{number}: {line.strip()}")
        self.assertEqual([], offenders, "read the version from the root pom.xml revision instead")

    def test_the_changelog_has_an_entry_for_the_version(self):
        current = version.product_version(REPOSITORY)
        changelog = (REPOSITORY / "docs/CHANGELOG.md").read_text(encoding="utf-8")
        self.assertRegex(changelog, r"(?m)^## \[Unreleased\]$")
        self.assertRegex(changelog, rf"(?m)^## \[{re.escape(current)}\] - ")
        self.assertLess(changelog.index("## [Unreleased]"), changelog.index(f"## [{current}]"))

    def test_the_cli_and_the_desktop_read_the_build_version(self):
        parser = (REPOSITORY / "modules/interfaces/datacraft-cli/src/main/scala/com/example/"
                  "datacraft/cli/CliParser.scala").read_text(encoding="utf-8")
        self.assertIn("head(AppInfo.DEFAULT_APP_NAME, AppInfo.version())", parser)
        for resource in ("modules/core/datacraft-common/src/main/resources/com/example/datacraft/"
                         "common/version.properties",
                         "desktop/src/main/resources/com/example/datacraft/desktop/version.properties"):
            with self.subTest(resource=resource):
                self.assertIn("version=${project.version}\n",
                              (REPOSITORY / resource).read_text(encoding="utf-8"))


class DeployGateTests(unittest.TestCase):
    CURRENT = version.product_version(REPOSITORY)

    def environment(self, **values):
        base = {"REF": "refs/heads/main", "SHA": "a" * 40, "REQUESTED": self.CURRENT}
        base.update(values)
        return base

    def test_a_request_is_accepted_only_on_main_with_the_source_version_and_a_free_tag(self):
        free = lambda tag, root: None  # noqa: E731
        self.assertEqual(self.CURRENT, deploy.request(self.environment(), REPOSITORY, free))
        same = lambda tag, root: "a" * 40  # noqa: E731
        self.assertEqual(self.CURRENT, deploy.request(self.environment(), REPOSITORY, same))
        refusals = [
            (self.environment(REF="refs/heads/develop"), free, "main only"),
            (self.environment(REQUESTED="0.0.1"), free, "differs from the version source"),
            (self.environment(REQUESTED=""), free, "differs from the version source"),
            (self.environment(), lambda tag, root: "b" * 40, "already names"),
        ]
        for environment, lookup, reason in refusals:
            with self.subTest(reason=reason), self.assertRaisesRegex(deploy.Refused, reason):
                deploy.request(environment, REPOSITORY, lookup)

    def test_publishing_checks_the_built_version_and_publishes_nothing(self):
        environment = self.environment(PACKAGED=self.CURRENT, ARTIFACT="release-x", SHA256="c" * 64)
        summary = deploy.publish(environment)
        self.assertIn(f"Deploy {self.CURRENT}", summary)
        self.assertIn("nothing was published", summary)
        self.assertIn("c" * 64, summary)
        with self.assertRaisesRegex(deploy.Refused, "the build produced"):
            deploy.publish(self.environment(PACKAGED="9.9.9"))
        with self.assertRaisesRegex(deploy.Refused, "main only"):
            deploy.publish(self.environment(REF="refs/heads/develop", PACKAGED=self.CURRENT))


class InstallerOutputsTests(unittest.TestCase):
    def test_the_outputs_name_the_version_the_run_and_the_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_bytes((REPOSITORY / "pom.xml").read_bytes())
            (root / "dist").mkdir()
            digest = "d" * 64
            (root / "dist/Setup.exe.sha256").write_text(f"{digest}  Setup.exe\n", encoding="utf-8")
            output, summary = root / "out.txt", root / "summary.md"
            environment = {"ARTIFACT_PREFIX": "release", "GITHUB_RUN_NUMBER": "12",
                           "GITHUB_RUN_ATTEMPT": "2", "GITHUB_OUTPUT": str(output),
                           "GITHUB_STEP_SUMMARY": str(summary)}
            self.assertEqual(0, release.outputs(environment, root))
            current = version.product_version(REPOSITORY)
            self.assertEqual(
                f"version={current}\n"
                f"artifact=release-DatacraftLab-{current}-windows-x64-run12-attempt2\n"
                f"sha256={digest}\n", output.read_text(encoding="utf-8"))
            self.assertIn(digest, summary.read_text(encoding="utf-8"))
            (root / "dist/Setup.exe.sha256").write_text("not a checksum\n", encoding="utf-8")
            self.assertEqual(1, release.outputs({}, root))


if __name__ == "__main__":
    unittest.main()
