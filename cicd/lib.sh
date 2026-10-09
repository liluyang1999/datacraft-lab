# shellcheck shell=bash
# Shared helpers for the CI/CD scripts under cicd/. Source it from a script two levels below the
# repository root (cicd/<stage>/<script>.sh); it is not meant to run on its own.

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export REPO_ROOT

# The shaded CLI jar the build produces, relative to REPO_ROOT; CI sets CLI_JAR to the same path.
export CLI_JAR=${CLI_JAR:-modules/interfaces/datacraft-cli/target/datacraft-cli.jar}

# workflow_data TEXT: TEXT as the message of a GitHub workflow command. Without the escapes a
# message would end at its first line break, and the rest would be ordinary log output.
workflow_data() {
  local text=$1
  text=${text//'%'/%25}
  text=${text//$'\r'/%0D}
  text=${text//$'\n'/%0A}
  printf '%s' "$text"
}

# fail MESSAGE...: report an error (as a GitHub Actions annotation on a runner) and exit 1.
fail() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error::$(workflow_data "$*")"
  else
    echo "error: $*" >&2
  fi
  exit 1
}

# notice TITLE MESSAGE...: record what a check established. On a runner this is a notice annotation,
# which can be read through the public API like any annotation, unlike the job log.
notice() {
  local title=$1
  shift
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    # A property value also reserves the two characters that separate properties.
    title=$(workflow_data "$title")
    title=${title//:/%3A}
    title=${title//,/%2C}
    echo "::notice title=$title::$(workflow_data "$*")"
  else
    echo "$title: $*"
  fi
}

# diagnose TITLE COMMAND...: print what COMMAND reports while a failure is being handled; whether
# COMMAND itself succeeds does not matter. On a runner the output is a named group of the log, and
# cicd/step.py repeats the last groups of a failed step as annotations, so the state a script
# collects before it tears a stack down (services, container logs) is public like the failure.
diagnose() {
  local title=$1 output
  shift
  # Captured, so that the closing marker starts a line whatever the output ends with.
  output=$("$@" 2>&1) || true
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    printf '::group::%s\n%s\n::endgroup::\n' "$(workflow_data "$title")" "$output"
  else
    printf -- '--- %s\n%s\n' "$title" "$output"
  fi
}

# scratch_dir NAME: a new private directory under the runner's temporary directory (TMPDIR or /tmp
# outside CI). The caller removes it, usually with a trap.
scratch_dir() {
  mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/$1.XXXXXX"
}

# python_command: prints the interpreter to use: PYTHON when it is set, otherwise the first of
# python3 and python that actually runs (on Windows a Store alias named python3 can sit on PATH
# and only exit non-zero). Fails when none works.
python_command() {
  local candidate
  if [ -n "${PYTHON:-}" ]; then
    "$PYTHON" -c '' > /dev/null 2>&1 || return 1
    echo "$PYTHON"
    return 0
  fi
  for candidate in python3 python; do
    if "$candidate" -c '' > /dev/null 2>&1; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}
