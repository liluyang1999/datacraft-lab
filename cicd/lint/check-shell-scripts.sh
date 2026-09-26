#!/usr/bin/env bash
# Syntax-checks every shell script in the repository with `bash -n`, then runs ShellCheck on them at
# warning severity (only its informational notes are allowed). ShellCheck must be installed; CI
# installs it when the runner lacks it.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

shopt -s nullglob
scripts=(cicd/*.sh cicd/*/*.sh deploy/scripts/*.sh tests/smoke/*.sh)
[ "${#scripts[@]}" -gt 0 ] || fail "no shell scripts found"
for script in "${scripts[@]}"; do
  echo "bash -n $script"
  bash -n "$script"
done

command -v shellcheck > /dev/null 2>&1 || fail "shellcheck is not installed"
shellcheck --version | sed -n 's/^version: /shellcheck /p'
shellcheck --severity=warning "${scripts[@]}"
echo "${#scripts[@]} shell scripts pass bash -n and ShellCheck"
