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

RUN = re.compile(r"^\s*(?:-\s+)?run:\s*(.*)$")
# Repository paths a command names: a script under cicd/ or a test entry point under tests/.
REFERENCED_PATH = re.compile(r"(?<![\w./-])((?:cicd|tests)/[\w./-]+\.(?:sh|py|java|cjs))")


def workflow_files():
    return sorted(WORKFLOWS.glob("*.y*ml"))


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


class WorkflowTests(unittest.TestCase):
    def test_workflows_exist_and_run_commands(self):
        self.assertTrue(workflow_files(), "no workflow under .github/workflows")
        self.assertTrue(run_commands(), "no run steps found")

    def test_run_steps_are_single_commands(self):
        # A block scalar (`run: |` or `run: >`) would put shell logic back into the YAML.
        offenders = [f"{name}:{number}: run: {command}" for name, number, command in run_commands()
                     if not command or command[0] in "|>"]
        self.assertEqual([], offenders, "move multi-line step logic into a script under cicd/")

    def test_scripts_named_by_the_workflow_exist(self):
        missing = [f"{name}:{number}: {path}" for name, number, command in run_commands()
                   for path in REFERENCED_PATH.findall(command)
                   if not (REPOSITORY / path).is_file()]
        self.assertEqual([], missing)

    def test_every_cicd_file_is_used_by_the_pipeline(self):
        sources = {path: path.read_text(encoding="utf-8")
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


@unittest.skipIf(sys.platform == "win32", "runs the check with a POSIX bash")
class ActionsPinningCheckTests(unittest.TestCase):
    SHA = "3d3c42e5aac5ba805825da76410c181273ba90b1"

    def check(self, workflow_text):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "ci.yml").write_text(workflow_text, encoding="utf-8")
            return subprocess.run(["bash", str(PINNING_CHECK), directory], capture_output=True,
                                  text=True, timeout=60, env={"PATH": "/usr/bin:/bin"})

    def test_actions_pinned_to_commit_shas_and_local_actions_pass(self):
        result = self.check("steps:\n"
                            f"  - uses: actions/checkout@{self.SHA} # v7.0.1\n"
                            f"  - name: x\n    uses: actions/setup-java@{self.SHA}\n"
                            "  - uses: ./.github/actions/local\n")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("pinned to a commit SHA", result.stdout)

    def test_tags_branches_and_short_shas_fail_and_are_listed(self):
        for reference in ("v7", "main", self.SHA[:12]):
            with self.subTest(reference=reference):
                result = self.check(f"steps:\n  - uses: actions/checkout@{reference}\n")
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn(f"actions/checkout@{reference}", result.stdout)
                self.assertIn("pin every action to a full commit SHA", result.stderr)


if __name__ == "__main__":
    unittest.main()
