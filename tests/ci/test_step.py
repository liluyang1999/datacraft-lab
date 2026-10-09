"""cicd/step.py runs a pipeline step unchanged and, on a runner, turns its failure into an error
annotation that carries the lines needed to diagnose it."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
STEP = REPOSITORY / "cicd" / "step.py"
# The runner cuts a longer annotation message (actions/runner, ExecutionContext.cs).
RUNNER_MESSAGE_LIMIT = 4096


def run_step(command, runner=True, **environment):
    """Runs step.py around command, as on a GitHub runner unless runner is False."""
    env = {key: value for key, value in os.environ.items()
           if key not in ("GITHUB_ACTIONS", "GITHUB_JOB", "RUNNER_TEMP")}
    if runner:
        env["GITHUB_ACTIONS"] = "true"
    env.update(environment)
    return subprocess.run([sys.executable, "-B", str(STEP), *command], capture_output=True, env=env,
                          timeout=120)


def python(code):
    """A command that runs code in a child interpreter."""
    return [sys.executable, "-B", "-c", code]


def unescape(value, mapping):
    for token, character in mapping:
        value = value.replace(token, character)
    return value


def annotations(output):
    """(title, message) of each ::error command in output, unescaped in the runner's order."""
    found = []
    # Split as the runner does, on line feeds only; the step's own output need not be UTF-8.
    for raw in output.split(b"\n"):
        if raw.startswith(b"::error title="):
            line = raw.decode("utf-8").rstrip("\r")
            title, _, message = line[len("::error title="):].partition("::")
            found.append((
                unescape(title, (("%0D", "\r"), ("%0A", "\n"), ("%3A", ":"), ("%2C", ","), ("%25", "%"))),
                unescape(message, (("%0D", "\r"), ("%0A", "\n"), ("%25", "%")))))
    return found


class StepTests(unittest.TestCase):
    def test_a_passing_step_is_passed_through_byte_for_byte_without_an_annotation(self):
        code = ("import sys\n"
                "sys.stdout.buffer.write(b'out \\xff\\xfe 50%\\n'); sys.stdout.flush()\n"
                "sys.stderr.buffer.write(b'err\\r\\n'); sys.stderr.flush()\n"
                "sys.stdout.buffer.write(b'no newline at the end')\n")
        result = run_step(python(code))
        self.assertEqual(0, result.returncode, result.stderr)
        # Standard error joins standard output, in the order the step wrote them.
        self.assertEqual(b"out \xff\xfe 50%\nerr\r\nno newline at the end", result.stdout)
        self.assertEqual(b"", result.stderr)

    def test_a_failing_step_keeps_its_exit_status_and_is_annotated_only_on_a_runner(self):
        command = python("import sys; print('boom', flush=True); sys.exit(3)")
        local = run_step(command, runner=False)
        self.assertEqual(3, local.returncode)
        self.assertEqual(b"boom", local.stdout.strip())
        self.assertEqual([], annotations(local.stdout))

        on_runner = run_step(command)
        self.assertEqual(3, on_runner.returncode)
        self.assertTrue(on_runner.stdout.startswith(local.stdout), on_runner.stdout)
        found = annotations(on_runner.stdout)
        self.assertEqual(1, len(found), on_runner.stdout)
        title, message = found[0]
        self.assertTrue(title.endswith(" failed (exit 3)"), title)
        self.assertEqual("boom", message)

    def test_the_annotation_starts_a_line_of_its_own_after_unfinished_output(self):
        # The runner recognises a workflow command only at the start of a line.
        result = run_step(python("import sys; sys.stdout.write('no newline'); sys.exit(1)"))
        self.assertEqual(1, result.returncode)
        lines = result.stdout.split(b"\n")
        self.assertEqual(b"no newline", lines[0])
        self.assertTrue(lines[1].startswith(b"::error title="), result.stdout)
        self.assertEqual(["no newline"], [message for _, message in annotations(result.stdout)])
        # Complete output gets no blank line in between.
        complete = run_step(python("print('done'); raise SystemExit(1)"))
        self.assertNotIn(b"\n\n", complete.stdout.replace(b"\r", b""))

    def test_the_title_names_the_job_and_the_step_as_written_in_the_workflow(self):
        # The runner writes the step's command into a file under RUNNER_TEMP and passes it last.
        step = "print('50% done: a, b', flush=True); raise SystemExit(5)"
        with tempfile.TemporaryDirectory() as temp:
            script = Path(temp) / "0d4f2c1e"
            script.write_text(step + "\n", encoding="utf-8")
            result = run_step([sys.executable, "-B", str(script)], GITHUB_JOB="build", RUNNER_TEMP=temp)
            self.assertEqual(5, result.returncode, result.stdout)
            self.assertEqual([(f"build: {step} failed (exit 5)", "50% done: a, b")],
                             annotations(result.stdout))
            # The escaped title holds neither of the characters that end a property.
            command = next(line for line in result.stdout.decode().splitlines()
                           if line.startswith("::error"))
            self.assertNotRegex(command.partition("::error title=")[2].partition("::")[0], "[:,]")
            # Elsewhere the last argument is an argument like any other.
            outside = run_step([sys.executable, "-B", str(script)], GITHUB_JOB="build")
            title = annotations(outside.stdout)[0][0]
            self.assertEqual(f"build: {sys.executable} -B {script} failed (exit 5)", title)

    def test_early_failure_lines_are_kept_together_with_the_last_lines(self):
        code = ("for n in range(200): print('ok', n)\n"
                "print('[ERROR] Tests run: 3, Failures: 1 -- in ExampleTest')\n"
                "print('  expected: <1> but was: <2>')\n"
                "print('- converts a file *** FAILED ***')\n"
                "print('  FAILED did not equal SUCCEEDED (Spec.scala:12)')\n"
                "for n in range(200): print('later', n)\n"
                "print('FINAL LINE')\n"
                "raise SystemExit(1)\n")
        result = run_step(python(code))
        self.assertEqual(1, result.returncode)
        (_, message), = annotations(result.stdout)
        lines = message.split("\n")
        self.assertEqual(["[ERROR] Tests run: 3, Failures: 1 -- in ExampleTest",
                          "  expected: <1> but was: <2>",
                          "- converts a file *** FAILED ***",
                          "  FAILED did not equal SUCCEEDED (Spec.scala:12)",
                          "[...]"], lines[:5])
        # Then the last 30 lines of output, and nothing of the 370 unremarkable lines before them.
        self.assertEqual([f"later {n}" for n in range(171, 200)] + ["FINAL LINE"], lines[5:])

    def test_a_failure_line_among_the_last_lines_is_not_repeated(self):
        result = run_step(python("print('one'); print('error: disk full'); print('three'); raise SystemExit(1)"))
        self.assertEqual(["one\nerror: disk full\nthree"], [message for _, message in annotations(result.stdout)])

    def test_the_message_stays_within_what_the_runner_keeps(self):
        code = ("for n in range(60): print('[ERROR] failure %03d ' % n + 'x' * 400)\n"
                "for n in range(100): print('tail %03d ' % n + 'y' * 280)\n"
                "raise SystemExit(1)\n")
        result = run_step(python(code))
        (title, message), = annotations(result.stdout)
        self.assertLessEqual(len(message), 4000)
        self.assertLess(len(message), RUNNER_MESSAGE_LIMIT)
        lines = message.split("\n")
        # The first failure and the very last line both survive; over-long lines are cut.
        self.assertTrue(lines[0].startswith("[ERROR] failure 000 "), lines[0])
        self.assertTrue(lines[-1].startswith("tail 099 "), lines[-1])
        self.assertIn("[...]", lines)
        self.assertTrue(all(len(line) <= 300 for line in lines))
        self.assertTrue(lines[0].endswith("..."))
        self.assertLessEqual(len(title), 200)

    def test_a_failure_among_the_last_lines_survives_when_those_lines_are_cut_for_size(self):
        # Thirty long lines do not fit into one message. The failure is one of them, so it used to
        # count as shown, and then went with the lines that were cut.
        failure = "[ERROR] Tests run: 3, Failures: 1 -- in ExampleTest"
        code = ("print('x' * 260)\nprint('y' * 260)\n"
                f"print({failure!r})\n"
                "for n in range(27): print('tail %02d ' % n + 'z' * 250)\n"
                "raise SystemExit(1)\n")
        result = run_step(python(code))
        (_, message), = annotations(result.stdout)
        lines = message.split("\n")
        self.assertLessEqual(len(message), 4000)
        self.assertEqual([failure, "[...]"], lines[:2])
        self.assertTrue(lines[-1].startswith("tail 26 "), lines[-1])
        self.assertEqual(1, lines.count(failure))
        # A failure the last lines still show is not repeated in front of them.
        short = run_step(python(f"print('start'); print({failure!r}); print('end'); raise SystemExit(1)"))
        self.assertEqual([f"start\n{failure}\nend"], [text for _, text in annotations(short.stdout)])

    @unittest.skipUnless(os.name == "posix", "the wrapper can wait on a pipe only on POSIX; a runner is Linux")
    def test_a_step_ends_with_its_command_and_not_with_a_process_it_left_running(self):
        # A process started in the background inherits the step's output and keeps it open. The
        # runner's own shell ends the step when the command ends, and so must the wrapper.
        child = "import time; time.sleep(25)"
        code = ("import subprocess, sys\n"
                f"subprocess.Popen([sys.executable, '-c', {child!r}])\n"
                "print('started', flush=True)\n"
                "raise SystemExit(3)\n")
        began = time.monotonic()
        result = run_step(python(code))
        self.assertLess(time.monotonic() - began, 15)
        self.assertEqual(3, result.returncode)
        self.assertEqual(["started"], [text for _, text in annotations(result.stdout)])

    def test_output_arrives_while_the_step_runs_and_a_long_line_is_passed_through_whole(self):
        # Longer than a pipe holds, so it reaches the wrapper in several pieces.
        code = ("import sys\n"
                "sys.stdout.buffer.write(b'p' * 300000 + b'\\n'); sys.stdout.flush()\n"
                "print('error: after the long line', flush=True)\n"
                "sys.exit(1)\n")
        result = run_step(python(code))
        self.assertTrue(result.stdout.replace(b"\r", b"").startswith(
            b"p" * 300_000 + b"\nerror: after the long line\n"))
        (_, message), = annotations(result.stdout)
        self.assertEqual(["p" * 297 + "...", "error: after the long line"], message.split("\n"))

    def test_a_long_step_is_cut_in_the_title_which_still_ends_with_the_exit_status(self):
        with tempfile.TemporaryDirectory() as temp:
            script = Path(temp) / "step"
            script.write_text("raise SystemExit(4)  # " + "long " * 200 + "\n", encoding="utf-8")
            result = run_step([sys.executable, "-B", str(script)], GITHUB_JOB="containers", RUNNER_TEMP=temp)
        (title, message), = annotations(result.stdout)
        self.assertEqual(200, len(title))
        self.assertTrue(title.startswith("containers: raise SystemExit(4)  # long long "), title)
        self.assertTrue(title.endswith("... failed (exit 4)"), title)
        self.assertEqual("(the step printed nothing)", message)

    def test_colour_codes_and_control_characters_are_left_out_of_the_annotation_only(self):
        code = ("import sys\n"
                "sys.stdout.buffer.write(b'\\x1b[1;31mERROR\\x1b[0m: red\\x07 \\r\\n\\xff\\n')\n"
                "sys.exit(1)\n")
        result = run_step(python(code))
        self.assertIn(b"\x1b[1;31mERROR\x1b[0m: red\x07 \r\n\xff\n", result.stdout)
        self.assertEqual(["ERROR: red\n�"], [message for _, message in annotations(result.stdout)])

    def test_the_last_groups_of_a_failed_step_become_annotations_of_their_own(self):
        # What a script collects after a failure, each part under its title and within its own limit.
        code = ("print('starting the stack')\n"
                "print('error: the scheduler never became healthy')\n"
                "for n in range(8):\n"
                "    print('::group::Part %d' % n)\n"
                "    print('line of part %d' % n)\n"
                "    print('::endgroup::')\n"
                "print('::group::50%25 of the logs: a, b')\n"
                "for n in range(300): print('[ERROR] log %03d ' % n + 'z' * 60)\n"
                "print('::endgroup::')\n"
                "print('stack removed')\n"
                "raise SystemExit(1)\n")
        result = run_step(python(code), GITHUB_JOB="containers")
        self.assertEqual(1, result.returncode)
        found = annotations(result.stdout)
        # The step's own annotation holds what the step printed outside the groups.
        self.assertEqual("starting the stack\nerror: the scheduler never became healthy\nstack removed",
                         found[0][1])
        # Then the last eight groups, in the order they were printed: with the step's own annotation
        # and one from the script that failed, ten is what a runner keeps for a step.
        self.assertEqual([f"containers: Part {n}" for n in range(1, 8)] + ["containers: 50% of the logs: a, b"],
                         [title for title, _ in found[1:]])
        self.assertEqual([f"line of part {n}" for n in range(1, 8)], [message for _, message in found[1:8]])
        # A group longer than one message keeps its end.
        logs = found[8][1].split("\n")
        self.assertLessEqual(len(found[8][1]), 4000)
        self.assertTrue(logs[-1].startswith("[ERROR] log 299 "), logs[-1])
        self.assertGreater(len(logs), 40)
        self.assertEqual(logs, [f"[ERROR] log {n:03d} " + "z" * 60 for n in range(300 - len(logs), 300)])
        # Every annotation is a line of its own, and the output itself is passed through unchanged.
        self.assertIn(b"::group::Part 0\nline of part 0\n::endgroup::\n", result.stdout.replace(b"\r", b""))

    def test_a_group_without_output_or_without_its_end_is_still_reported(self):
        code = ("print('::group::Empty')\n"
                "print('::endgroup::')\n"
                "print('::group::Second')\n"
                "print('kept')\n"
                "print('::group::Cut short')\n"
                "print('the step was killed here')\n"
                "raise SystemExit(9)\n")
        result = run_step(python(code))
        self.assertEqual([("Empty", "(no output)"), ("Second", "kept"), ("Cut short", "the step was killed here")],
                         annotations(result.stdout)[1:])
        self.assertEqual("(the step printed nothing)", annotations(result.stdout)[0][1])

    def test_groups_add_nothing_to_a_passing_step_or_outside_a_runner(self):
        code = "print('::group::State'); print('fine'); print('::endgroup::'); raise SystemExit(%d)"
        passing = run_step(python(code % 0))
        self.assertEqual(0, passing.returncode)
        self.assertEqual(b"::group::State\nfine\n::endgroup::\n", passing.stdout.replace(b"\r", b""))
        local = run_step(python(code % 1), runner=False)
        self.assertEqual(1, local.returncode)
        self.assertEqual(passing.stdout, local.stdout)

    def test_a_command_that_cannot_start_is_reported_with_status_127(self):
        result = run_step(["datacraft-no-such-command", "--flag"], GITHUB_JOB="scripts")
        self.assertEqual(127, result.returncode)
        self.assertIn(b"cannot start datacraft-no-such-command", result.stderr)
        (title, message), = annotations(result.stdout)
        self.assertEqual("scripts: datacraft-no-such-command --flag failed (exit 127)", title)
        self.assertTrue(message.startswith("cannot start datacraft-no-such-command: "), message)

    def test_without_a_command_it_prints_its_usage_and_exits_2(self):
        result = run_step([])
        self.assertEqual(2, result.returncode)
        self.assertIn(b"usage: python3 -B cicd/step.py COMMAND", result.stderr)
        self.assertEqual(b"", result.stdout)


if __name__ == "__main__":
    unittest.main()
