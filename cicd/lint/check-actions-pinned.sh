#!/usr/bin/env bash
# A tag can be re-pointed; a commit SHA cannot. Every `uses:` in the workflows must name its action
# by a full 40-character commit SHA, with a '# vX.Y.Z' comment for the release; local ./ actions are
# exempt. The optional argument is the workflow directory (default: .github/workflows).
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

workflows=${1:-.github/workflows}
[ -d "$workflows" ] || fail "no workflow directory at $workflows"
unpinned=$(grep -rnE --include='*.yml' --include='*.yaml' '^[[:space:]]*(-[[:space:]]+)?uses:' "$workflows" \
  | grep -vE 'uses:[[:space:]]+(\./|[^@[:space:]]+@[0-9a-f]{40}([[:space:]]|$))' || true)
if [ -n "$unpinned" ]; then
  echo "$unpinned"
  fail "pin every action to a full commit SHA with a '# vX.Y.Z' comment"
fi
echo "every action in $workflows is pinned to a commit SHA"
