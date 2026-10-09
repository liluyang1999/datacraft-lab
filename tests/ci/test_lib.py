"""cicd/lib.sh: how the pipeline scripts report to a GitHub runner, and what they print elsewhere."""

import os
from pathlib import Path
import subprocess
import sys
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
LIB = REPOSITORY / "cicd" / "lib.sh"
STEP = REPOSITORY / "cicd" / "step.py"


def run_script(script, runner=True, wrapper=()):
    """Runs script in bash with lib.sh sourced under the options the pipeline scripts set.

    Returns (exit status, standard output, standard error).
    """
    env = {key: value for key, value in os.environ.items()
           if key not in ("GITHUB_ACTIONS", "GITHUB_JOB", "RUNNER_TEMP")}
    if runner:
        env["GITHUB_ACTIONS"] = "true"
    command = ["bash", "-c", f'set -euo pipefail\n. "$1"\n{script}', "bash", str(LIB)]
    result = subprocess.run([*wrapper, *command], capture_output=True, env=env, timeout=60)
    # Decoded here: text mode would turn the carriage returns under test into line feeds.
    return result.returncode, result.stdout.decode("utf-8"), result.stderr.decode("utf-8")


@unittest.skipIf(sys.platform == "win32", "sources the library with a POSIX bash")
class LibTests(unittest.TestCase):
    def test_fail_is_one_error_annotation_whatever_the_message_holds(self):
        # A message with a line break used to end the annotation at its first line.
        script = "fail 'GET /jobs: expected 401, got:' $'{\"status\":\"UP\"}\\n200 at 50%'\necho unreachable"
        self.assertEqual((1, '::error::GET /jobs: expected 401, got: {"status":"UP"}%0A200 at 50%25\n', ""),
                         run_script(script))
        self.assertEqual((1, "", 'error: GET /jobs: expected 401, got: {"status":"UP"}\n200 at 50%\n'),
                         run_script(script, runner=False))

    def test_notice_escapes_its_title_as_a_property_and_its_message_as_data(self):
        script = "notice 'Checked: a, b' 'first' $'second\\r\\nline at 100%'\necho after"
        self.assertEqual((0, "::notice title=Checked%3A a%2C b::first second%0D%0Aline at 100%25\nafter\n", ""),
                         run_script(script))
        self.assertEqual((0, "Checked: a, b: first second\r\nline at 100%\nafter\n", ""),
                         run_script(script, runner=False))

    def test_diagnose_groups_the_output_of_a_command_and_ignores_its_failure(self):
        script = ("diagnose 'Services: 50%' sh -c 'echo out; echo err >&2; printf unfinished; exit 7'\n"
                  "echo \"after $?\"")
        # The closing marker starts a line of its own even after output without a final newline.
        self.assertEqual((0, "::group::Services: 50%25\nout\nerr\nunfinished\n::endgroup::\nafter 0\n", ""),
                         run_script(script))
        self.assertEqual((0, "--- Services: 50%\nout\nerr\nunfinished\nafter 0\n", ""),
                         run_script(script, runner=False))

    def test_a_failed_step_publishes_its_diagnosis_as_annotations(self):
        # The contract between the two files: lib.sh prints the groups step.py repeats.
        script = ("echo 'waiting for the stack'\n"
                  "diagnose 'Stack: services' printf 'api 0/1\\nscheduler 1/1\\n'\n"
                  "diagnose 'Stack: api log' echo 'address already in use'\n"
                  "fail 'The stack did not converge.'")
        status, output, _ = run_script(script, wrapper=(sys.executable, "-B", str(STEP)))
        self.assertEqual(1, status)
        annotations = [line for line in output.splitlines() if line.startswith("::error")]
        self.assertEqual("::error::The stack did not converge.", annotations[0])
        self.assertTrue(annotations[1].endswith("::waiting for the stack%0A::error::The stack did not converge."),
                        annotations[1])
        self.assertEqual(["::error title=Stack%3A services::api 0/1%0Ascheduler 1/1",
                          "::error title=Stack%3A api log::address already in use"], annotations[2:])


if __name__ == "__main__":
    unittest.main()
