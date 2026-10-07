#!/usr/bin/env bash
# Syntax-checks every shell script in the repository with `bash -n`, then runs ShellCheck on them:
# every finding fails, down to style notes, and sourced files are followed, so a script is checked
# together with the library it sources. The scripts are the *.sh files git knows, tracked or not yet
# ignored, so a new directory cannot be skipped. Needs git and ShellCheck; CI installs the release
# pinned in install-shellcheck.sh.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

git rev-parse --is-inside-work-tree > /dev/null 2>&1 || fail "git cannot list the shell scripts here"
scripts=()
# NUL-separated: git quotes a path with unusual characters in its line output, which would not name
# a file.
while IFS= read -r -d '' script; do
  # A deleted file stays in the index until the deletion is staged.
  if [ -f "$script" ]; then scripts+=("$script"); fi
done < <(git ls-files -z --cached --others --exclude-standard -- '*.sh')
[ "${#scripts[@]}" -gt 0 ] || fail "no shell scripts found"
for script in "${scripts[@]}"; do
  echo "bash -n $script"
  bash -n "$script"
done

command -v shellcheck > /dev/null 2>&1 || fail "shellcheck is not installed"
shellcheck --version | sed -n 's/^version: /shellcheck /p'
shellcheck --external-sources --source-path=SCRIPTDIR --severity=style "${scripts[@]}"
echo "${#scripts[@]} shell scripts pass bash -n and ShellCheck"
