# shellcheck shell=bash
# Shared helpers for the CI/CD scripts under cicd/. Source it from a script two levels below the
# repository root (cicd/<stage>/<script>.sh); it is not meant to run on its own.

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export REPO_ROOT

# The shaded CLI jar the build produces, relative to REPO_ROOT; CI sets CLI_JAR to the same path.
export CLI_JAR=${CLI_JAR:-modules/interfaces/datacraft-cli/target/datacraft-cli.jar}

# fail MESSAGE...: report an error (as a GitHub Actions annotation on a runner) and exit 1.
fail() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error::$*"
  else
    echo "error: $*" >&2
  fi
  exit 1
}

# scratch_dir NAME: a new private directory under the runner's temporary directory (TMPDIR or /tmp
# outside CI). The caller removes it, usually with a trap.
scratch_dir() {
  mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/$1.XXXXXX"
}
