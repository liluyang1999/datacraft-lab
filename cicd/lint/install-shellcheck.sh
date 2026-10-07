#!/usr/bin/env bash
# Installs the ShellCheck release that check-shell-scripts.sh is verified against into the current
# Python environment: the PyPI package shellcheck-py carries the official ShellCheck binary. A fixed
# release keeps the lint result independent of the runner image, whose own ShellCheck is older and
# changes with the image. PYTHON selects the interpreter (default: the first of python3 and python
# that runs). Outside a CI runner, run it inside a virtual environment.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

# The PyPI package appends its own build number to the ShellCheck version.
package_version=0.11.0.1
shellcheck_version=${package_version%.*}

python=$(python_command) || fail "no working Python interpreter found (set PYTHON)"
"$python" -m pip install "shellcheck-py==$package_version"
installed=$(shellcheck --version 2> /dev/null | sed -n 's/^version: //p') || installed=
[ "$installed" = "$shellcheck_version" ] ||
  fail "shellcheck on PATH is ${installed:-missing}, expected $shellcheck_version: is the Python environment's bin directory first on PATH?"
echo "shellcheck $installed"
