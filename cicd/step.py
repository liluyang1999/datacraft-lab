"""Runs one pipeline step and, when it fails on a GitHub runner, repeats the decisive lines of its
output as an error annotation.

A job log can only be read with access to the repository, while the annotations of a run are public
(GET /repos/{owner}/{repo}/check-runs/{job id}/annotations). Without this wrapper a failed step
leaves a single annotation, "Process completed with exit code 1.", which names neither the test nor
the command that failed. The workflow makes this script the shell of every run step:

    defaults:
      run:
        shell: python3 -B cicd/step.py bash -e {0}

The runner replaces {0} with a file holding the step's command, and `bash -e` is what it would have
run by default. The command runs unchanged: its output is passed through as it arrives (standard
error joins standard output), and the exit status is the command's. Outside GitHub Actions nothing
else is printed.

A step can also name parts of its output with the runner's own folding commands, `::group::TITLE`
and `::endgroup::` (`diagnose` in cicd/lib.sh prints them around a command). When the step fails,
each of its last groups becomes an annotation of its own under that title, so the state a script
collects after a failure (services, container logs) is public as well, each part within its own
message limit. Lines inside a group are left out of the step's own annotation.

Usage: python3 -B cicd/step.py COMMAND [ARGUMENT...]
Exit status: the command's; 127 when it cannot be started, 2 without a command.
"""

import collections
import os
import re
import signal
import subprocess
import sys

# The runner cuts an annotation message at 4096 characters (actions/runner, ExecutionContext.cs).
MESSAGE_LIMIT = 4000
# Of that, what the earliest failure lines may take from the last lines of output.
FAILURE_RESERVE = 1200
FAILURE_LINES = 20
TAIL_LINES = 30
LINE_LIMIT = 300
TITLE_LIMIT = 200
OMISSION = "[...]"
# The groups of a failed step that become annotations, counted from the end. The runner keeps ten
# annotations of a level for a step: these, the step's own and the one of the script's `fail`.
GROUPS = 8
# More lines than one message can hold are not worth keeping.
GROUP_LINES = 400
GROUP = "::group::"
END_GROUP = "::endgroup::"

# Lines that name what went wrong: Maven and Docker errors, failed JUnit, ScalaTest and unittest
# tests, exception and traceback headers, git and shell fatal errors.
FAILURE = re.compile(r"\[ERROR\]|\bERROR\b|\berror:|\bFAIL(?:ED|URE)?\b|\*\*\* FAILED"
                     r"|\w+(?:Error|Exception)\b|Traceback \(most recent call last\)|\bfatal:")
COLOUR = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")


def text(raw):
    """One line of output as annotation text: decoded, without colour codes or control characters."""
    line = COLOUR.sub("", raw.decode("utf-8", errors="replace"))
    line = "".join(char if char >= " " or char == "\t" else " " for char in line).rstrip()
    return line if len(line) <= LINE_LIMIT else line[:LINE_LIMIT - 3] + "..."


def run(command):
    """Runs command and copies its output through.

    Returns (exit status, failure lines, last lines, whether the output ended inside a line,
    the last GROUPS groups as (title, lines)).

    A failure line is one FAILURE matches, or an indented line right after it, which carries the
    detail (ScalaTest prints the assertion message there). Only the first FAILURE_LINES are kept.
    Failure lines and last lines are those outside a group.
    """
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except OSError as error:
        message = f"cannot start {command[0]}: {error}"
        print(message, file=sys.stderr, flush=True)
        return 127, [message], [], False, []

    def forward(signum, _frame):
        # A cancelled job signals this process only; the step must still run its clean-up traps.
        try:
            process.send_signal(signum)
        except (OSError, ValueError):
            pass

    for name in ("SIGINT", "SIGTERM"):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), forward)

    failures, tail, follows_failure = [], collections.deque(maxlen=TAIL_LINES), False
    groups, group = collections.deque(maxlen=GROUPS), None
    unfinished = False
    assert process.stdout is not None
    for raw in process.stdout:
        sys.stdout.buffer.write(raw)
        sys.stdout.buffer.flush()
        unfinished = not raw.endswith(b"\n")
        line = text(raw)
        if line == END_GROUP:
            group, follows_failure = None, False
            continue
        if line.startswith(GROUP):
            # The runner does not nest groups: a new one ends the one before it.
            group, follows_failure = collections.deque(maxlen=GROUP_LINES), False
            groups.append((plain(line[len(GROUP):]).strip(), group))
            continue
        if group is not None:
            group.append(line)
            continue
        tail.append(line)
        matched = FAILURE.search(line) is not None
        detail = follows_failure and line[:1].isspace()
        if line and (matched or detail) and len(failures) < FAILURE_LINES:
            failures.append(line)
        follows_failure = matched
    status = process.wait()
    # A command killed by signal N reports as a shell would: 128 + N.
    groups = [(name, list(lines)) for name, lines in groups]
    return (128 - status if status < 0 else status), failures, list(tail), unfinished, groups


def step_command(command):
    """The step as its author wrote it: the content of the runner's script file, else the command."""
    script, temp = command[-1], os.environ.get("RUNNER_TEMP", "")
    try:
        if (len(command) > 1 and temp and os.path.isfile(script) and os.path.getsize(script) <= 4096
                and os.path.commonpath([os.path.realpath(temp), os.path.realpath(script)])
                == os.path.realpath(temp)):
            with open(script, encoding="utf-8", errors="replace") as source:
                lines = [line.strip() for line in source if line.strip()]
            if lines:
                return lines[0] if len(lines) == 1 else f"{lines[0]} (+{len(lines) - 1} lines)"
    except (OSError, ValueError):
        pass
    return " ".join(command)


def fit(lines, budget, from_end=False):
    """The longest run of lines, from one end, whose text with separators stays within budget."""
    kept, used = [], 0
    for line in reversed(lines) if from_end else lines:
        if used + len(line) + 1 > budget:
            break
        kept.append(line)
        used += len(line) + 1
    return (kept[::-1] if from_end else kept), used


def message(failures, tail):
    """The failure lines the last lines do not already show, a marker, then the last lines."""
    last = [line for line in tail if line]
    shown = set(last)
    earlier = [line for line in failures if line not in shown]
    reserve = min(FAILURE_RESERVE, sum(len(line) + 1 for line in earlier) + len(OMISSION) + 1)
    last, used = fit(last, MESSAGE_LIMIT - reserve, from_end=True)
    earlier, _ = fit(earlier, MESSAGE_LIMIT - used - len(OMISSION) - 1)
    lines = earlier + [OMISSION] + last if earlier else last
    return "\n".join(lines) or "(the step printed nothing)"


def excerpt(lines):
    """The last of a group's lines that fit into one message."""
    kept, _ = fit([line for line in lines if line], MESSAGE_LIMIT, from_end=True)
    return "\n".join(kept) or "(no output)"


def data(value):
    """value escaped as the message of a workflow command."""
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def plain(value):
    """The text a workflow command carried in escaped form; the inverse of data."""
    return value.replace("%0D", "\r").replace("%0A", "\n").replace("%25", "%")


def error(name, suffix, body):
    """An error annotation titled with the job, name and suffix; name is cut to make them fit."""
    job = os.environ.get("GITHUB_JOB", "")
    title = (f"{job}: " if job else "") + name
    if len(title) + len(suffix) > TITLE_LIMIT:
        title = title[:TITLE_LIMIT - len(suffix) - 3] + "..."
    # A property value also reserves the two characters that separate properties.
    title = data(title + suffix).replace(":", "%3A").replace(",", "%2C")
    return f"::error title={title}::{data(body)}"


def annotations(command, status, failures, tail, groups):
    """The workflow commands that record a failed step: the step itself, then its last groups."""
    step = error(step_command(command), f" failed (exit {status})", message(failures, tail))
    return [step] + [error(name, "", excerpt(lines)) for name, lines in groups]


def main(argv=None):
    command = sys.argv[1:] if argv is None else argv
    if not command:
        print("usage: python3 -B cicd/step.py COMMAND [ARGUMENT...]", file=sys.stderr)
        return 2
    status, failures, tail, unfinished, groups = run(command)
    if status != 0 and os.environ.get("GITHUB_ACTIONS"):
        # A workflow command is only recognised at the start of a line. Bytes, not print(): the
        # text may not exist in the locale's charset.
        commands = annotations(command, status, failures, tail, groups)
        lines = ("\n" if unfinished else "") + "".join(line + "\n" for line in commands)
        sys.stdout.buffer.write(lines.encode("utf-8"))
        sys.stdout.buffer.flush()
    return status


if __name__ == "__main__":
    sys.exit(main())
