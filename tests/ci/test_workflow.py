"""The workflow only orchestrates: every step's logic lives in cicd/, where it can run and be reviewed
outside GitHub Actions, and every file in cicd/ is used by the pipeline."""

from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
WORKFLOWS = REPOSITORY / ".github" / "workflows"
CICD = REPOSITORY / "cicd"
PINNING_CHECK = CICD / "lint" / "check-actions-pinned.sh"
SPARK_SUITE_CHECK = CICD / "build" / "check-spark-suite.sh"

RUN = re.compile(r"^\s*(?:-\s+)?run:\s*(.*)$")
# Repository paths a command names: a script under cicd/ or a test entry point under tests/.
REFERENCED_PATH = re.compile(r"(?<![\w./-])((?:cicd|tests)/[\w./-]+\.(?:sh|py|java|cjs))")
# Shell syntax that would make a run step more than one command.
COMPOUND = re.compile(r"&&|\|\||[;|`]|\$\(|(?<!\S)[{(](?!\S)")
MAKE_RECIPE = re.compile(r"^\t(.*)$", re.MULTILINE)


def workflow_files():
    return sorted(WORKFLOWS.glob("*.y*ml"))


def without_comments(text):
    """Drops whole-line `#` comments, and trailing ones in YAML: a mention there uses nothing."""
    lines = [line for line in text.splitlines() if not line.lstrip().startswith("#")]
    return "\n".join(re.sub(r"\s+#\s.*$", "", line) for line in lines)


def run_commands():
    """(workflow, line number, command) of every `run:` step."""
    commands = []
    for workflow in workflow_files():
        for number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), 1):
            match = RUN.match(line)
            if match:
                commands.append((workflow.name, number, match.group(1).strip()))
    return commands


def cicd_files():
    return sorted(path for path in CICD.rglob("*")
                  if path.is_file() and "__pycache__" not in path.parts)


def git_lines(*arguments):
    """Output lines of a git command in the repository, or None when git is not available."""
    try:
        result = subprocess.run(["git", *arguments], cwd=REPOSITORY, capture_output=True,
                                text=True, timeout=60)
    except OSError:
        return None
    return result.stdout.splitlines() if result.returncode == 0 else None


class WorkflowTests(unittest.TestCase):
    def test_workflows_exist_and_run_commands(self):
        self.assertTrue(workflow_files(), "no workflow under .github/workflows")
        self.assertTrue(run_commands(), "no run steps found")

    def test_run_steps_are_single_commands(self):
        # A block scalar (`run: |` or `run: >`) or shell operators would put logic back into the YAML.
        offenders = [f"{name}:{number}: run: {command}" for name, number, command in run_commands()
                     if not command or command[0] in "|>" or COMPOUND.search(command)]
        self.assertEqual([], offenders, "move step logic into a script under cicd/")

    def test_compound_commands_are_recognised(self):
        for command in ("a && b", "a || b", "a; b", "a | b", "echo `date`", "echo $(date)",
                        "command -v x || { apt-get install x; }", "( cd x && y )"):
            self.assertTrue(COMPOUND.search(command), command)
        for command in ("bash cicd/build/check-jar-contents.sh", "./mvnw -B -ntp verify",
                        'rm -rf -- "$AIRFLOW_HOME"', "npx --yes pyright@1.1.414 --warnings",
                        "python3 -B tests/smoke/airflow_runtime_smoke.py --jar target/a.jar"):
            self.assertIsNone(COMPOUND.search(command), command)

    def test_scripts_named_by_the_workflow_and_the_makefile_exist(self):
        commands = run_commands()
        makefile = (REPOSITORY / "Makefile").read_text(encoding="utf-8")
        commands += [("Makefile", 0, recipe) for recipe in MAKE_RECIPE.findall(makefile)]
        missing = [f"{name}:{number}: {path}" for name, number, command in commands
                   for path in REFERENCED_PATH.findall(command)
                   if not (REPOSITORY / path).is_file()]
        self.assertEqual([], missing)

    def test_every_cicd_file_is_used_by_the_pipeline(self):
        sources = {path: without_comments(path.read_text(encoding="utf-8"))
                   for path in workflow_files() + cicd_files()}
        unused = []
        for path in cicd_files():
            relative = path.relative_to(REPOSITORY).as_posix()
            # lib.sh is sourced as "$(dirname "$0")/../lib.sh"; everything else by its path.
            names = (relative, "/../lib.sh") if path.name == "lib.sh" else (relative,)
            if not any(any(name in text for name in names)
                       for user, text in sources.items() if user != path):
                unused.append(relative)
        self.assertEqual([], unused, "files in cicd/ that neither a workflow nor a cicd script uses")

    def test_a_mention_in_a_comment_is_not_a_use(self):
        text = "# bash cicd/a.sh\n  run: bash cicd/b.sh # not cicd/c.sh\n  - uses: x/y@abc # v1\n"
        stripped = without_comments(text)
        self.assertIn("cicd/b.sh", stripped)
        for mention in ("cicd/a.sh", "cicd/c.sh", "v1"):
            self.assertNotIn(mention, stripped)

    def test_cicd_files_are_not_git_ignored(self):
        # An unanchored ignore pattern such as `build/` once hid cicd/build/ from git.
        paths = [path.relative_to(REPOSITORY).as_posix() for path in cicd_files()]
        try:
            # NUL-separated bytes: text mode on Windows would append a carriage return to each path.
            result = subprocess.run(["git", "check-ignore", "--no-index", "-z", "--stdin"],
                                    input="\0".join(paths).encode(), cwd=REPOSITORY,
                                    capture_output=True, timeout=60)
        except OSError:
            self.skipTest("git is not available")
        self.assertIn(result.returncode, (0, 1), result.stderr.decode(errors="replace"))
        ignored = [name for name in result.stdout.decode().split("\0") if name]
        self.assertEqual([], ignored, "a .gitignore rule hides these cicd/ files")

    def test_cicd_holds_only_pipeline_logic(self):
        # Tests of the pipeline logic live in tests/ci; cicd/ holds the logic itself.
        tests = [path.relative_to(REPOSITORY).as_posix() for path in cicd_files()
                 if re.fullmatch(r"test_.*\.py|.*_test\.py|.*\.test\.[cm]?js", path.name)]
        self.assertEqual([], tests)

    def test_pipeline_only_shell_scripts_live_in_cicd(self):
        # deploy/scripts is what an operator runs on the host; every other shell script in the
        # repository is a pipeline step and belongs in cicd/.
        scripts = git_lines("ls-files", "--cached", "--others", "--exclude-standard", "--", "*.sh")
        if scripts is None:
            self.skipTest("git is not available")
        elsewhere = [name for name in scripts if (REPOSITORY / name).is_file()
                     and not name.startswith(("cicd/", "deploy/scripts/"))]
        self.assertEqual([], elsewhere)


@unittest.skipIf(sys.platform == "win32", "runs the check with a POSIX bash")
class ActionsPinningCheckTests(unittest.TestCase):
    SHA = "3d3c42e5aac5ba805825da76410c181273ba90b1"

    def check(self, workflow_text, name="ci.yml"):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / name).write_text(workflow_text, encoding="utf-8")
            return subprocess.run(["bash", str(PINNING_CHECK), directory], capture_output=True,
                                  text=True, timeout=60, env={"PATH": "/usr/bin:/bin"})

    def test_actions_pinned_to_commit_shas_with_their_release_and_local_actions_pass(self):
        result = self.check("steps:\n"
                            f"  - uses: actions/checkout@{self.SHA} # v7.0.1\n"
                            f"  - name: x\n    uses: actions/setup-java@{self.SHA}   #v6.0.1\n"
                            "  - uses: ./.github/actions/local\n"
                            "  # A comment that names no action key.\n"
                            "  - name: reuses: nothing\n    run: bash cicd/x.sh\n")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("3 uses", result.stdout)

    def test_every_other_reference_or_spelling_fails_and_is_listed(self):
        pinned = f"actions/checkout@{self.SHA}"
        # reason: (workflow text, number of the line that must be listed)
        offenders = {
            "a tag": ("steps:\n  - uses: actions/checkout@v7 # v7.0.1\n", 2),
            "a branch": ("steps:\n  - uses: actions/checkout@main\n", 2),
            "a short SHA": (f"steps:\n  - uses: actions/checkout@{self.SHA[:12]} # v7.0.1\n", 2),
            "no release comment": (f"steps:\n  - uses: {pinned}\n", 2),
            "a flow mapping": ("steps:\n  - { uses: actions/checkout@v7 }\n", 2),
            "a flow mapping after a key": ("steps:\n  - { name: x, uses: actions/checkout@v7 }\n", 2),
            "a flow mapping with a SHA": (f"steps:\n  - {{ uses: {pinned} }} # v7.0.1\n", 2),
            "a flow sequence": ("steps: [uses: actions/checkout@v7]\n", 1),
            "a quoted key": ('steps:\n  - "uses": actions/checkout@v7\n', 2),
            "an escape in a quoted key": ('steps:\n  - "u\\x73es": actions/checkout@v7\n', 2),
            "an explicit key": ("steps:\n  - ? uses\n    : actions/checkout@v7\n", 2),
            "an alias as the key": ("x-key: &k uses\nsteps:\n  - *k : actions/checkout@v7\n", 3),
            "the value on the next line": ("steps:\n  - uses:\n      actions/checkout@v7\n", 2),
            # To a line reader, the second line of this quoted scalar looks like a comment.
            "a quoted scalar continued on a comment-like line":
                ('steps:\n  - { name: "x\n  # y", uses: actions/checkout@v7 }\n', 3),
            "a commented-out step": ("steps:\n  # - uses: actions/checkout@v7\n", 2),
        }
        for reason, (workflow, line) in offenders.items():
            with self.subTest(reason=reason):
                result = self.check(workflow)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn(f"ci.yml:{line}:", result.stdout)
                self.assertIn("full commit SHA", result.stderr)

    def test_a_directory_without_workflow_files_fails(self):
        result = self.check("steps: []\n", name="notes.txt")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("no workflow files", result.stderr)

    def test_the_repository_workflows_pass_and_use_actions(self):
        result = subprocess.run(["bash", str(PINNING_CHECK)], capture_output=True, text=True,
                                timeout=60, env={"PATH": "/usr/bin:/bin"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertRegex(result.stdout, r"^[1-9]\d* uses in \.github/workflows")


@unittest.skipIf(sys.platform == "win32", "runs the check with a POSIX bash")
class SparkSuiteCheckTests(unittest.TestCase):
    SUMMARY = ("Run completed in 20 seconds.\nSuites: completed 5, aborted 0\n"
               "Tests: succeeded 51, failed 0, canceled {canceled}, ignored 0, pending 0\n"
               "All tests passed.\n")

    def check(self, report_text):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "datacraft-spark-scalatest.txt"
            if report_text is not None:
                report.write_text(report_text, encoding="utf-8")
            return subprocess.run(["bash", str(SPARK_SUITE_CHECK), str(report)],
                                  capture_output=True, text=True, timeout=60,
                                  env={"PATH": "/usr/bin:/bin"})

    def test_a_report_with_the_spark_suite_and_no_canceled_test_passes(self):
        result = self.check("SparkPipelineSpec:\n- spark-version job reports the runtime\n"
                            + self.SUMMARY.format(canceled=0))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Tests: succeeded 51", result.stdout)

    def test_a_missing_report_a_missing_suite_and_canceled_tests_fail(self):
        cases = {
            "scalatest report not found": None,
            "SparkPipelineSpec never ran": "SparkJobsSpec:\n" + self.SUMMARY.format(canceled=0),
            "the Spark suite was canceled": "SparkPipelineSpec:\n" + self.SUMMARY.format(canceled=7),
            "no test summary": "SparkPipelineSpec:\ncanceled 0, but the run stopped here\n",
        }
        for message, report in cases.items():
            with self.subTest(message=message):
                result = self.check(report)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn(message, result.stderr)


if __name__ == "__main__":
    unittest.main()
