#!/usr/bin/env bash
# A tag can be re-pointed; a commit SHA cannot. Every `uses` in the workflows must be written as
#   uses: owner/repo@<40-character commit SHA> # vX.Y.Z
# with the release in the comment, or name a local ./ action. The check reads lines, not YAML, so it
# fails on anything else it could misread: another way of writing the key (flow style, a quoted or
# explicit key, the value on the next line), a `uses:` in a comment (which may be the continuation
# of a quoted scalar), and a key it cannot read (an escape in a quoted key, an alias as a key).
# The optional argument is the workflow directory (default: .github/workflows, which must use at
# least one action, or the check would pass without having looked at anything).
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

default=.github/workflows
workflows=${1:-$default}
[ -d "$workflows" ] || fail "no workflow directory at $workflows"
shopt -s nullglob
files=("$workflows"/*.yml "$workflows"/*.yaml)
[ "${#files[@]}" -gt 0 ] || fail "no workflow files in $workflows"

# Where a mapping key can start: after indentation, a list dash, or a flow-style brace, bracket or
# comma.
start='(^|[[:space:]{,[])'
uses_key=$start'["'\'']?uses["'\'']?[[:space:]]*:'
explicit_key=$start'\?[[:space:]]+["'\'']?uses["'\'']?([[:space:]]|$)'
escaped_key=$start'"[^"]*\\[^"]*"[[:space:]]*:'
alias_key=$start'\*[^[:space:]:]+[[:space:]]*:'
pinned='^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+(\./[^[:space:]]+([[:space:]]+#.*)?|[^@[:space:]]+@[0-9a-f]{40}[[:space:]]+#[[:space:]]*v[0-9][0-9A-Za-z.+-]*)[[:space:]]*$'
count=0
rejected=()
for file in "${files[@]}"; do
  number=0
  while IFS= read -r line || [ -n "$line" ]; do
    number=$((number + 1))
    line=${line%$'\r'}
    if [[ "$line" =~ $uses_key || "$line" =~ $explicit_key ]]; then
      count=$((count + 1))
      [[ "$line" =~ $pinned ]] || rejected+=("$file:$number:$line")
    elif [[ "$line" =~ $escaped_key || "$line" =~ $alias_key ]]; then
      rejected+=("$file:$number:$line")
    fi
  done < "$file"
done

if [ "${#rejected[@]}" -gt 0 ]; then
  printf '%s\n' "${rejected[@]}"
  fail "write every action as 'uses: owner/repo@<full commit SHA> # vX.Y.Z' on one line, and keep 'uses:' out of comments"
fi
if [ "$count" -eq 0 ] && [ "$workflows" = "$default" ]; then
  fail "no 'uses:' found in $workflows; the check has nothing to verify"
fi
echo "$count uses in $workflows: every action is pinned to a commit SHA with its release"
