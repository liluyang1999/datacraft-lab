#!/usr/bin/env bash
# The shaded jar runs without Spark, which is a provided dependency: the control commands, the
# built-in jobs and the plain-JVM data jobs work under plain `java -jar`, and a failed quality gate
# exits 1. Run after the jar is built; CLI_JAR overrides its path.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

jar=$CLI_JAR
[ -f "$jar" ] || fail "CLI jar missing at $jar"
work=$(scratch_dir cli-smoke)
trap 'rm -rf -- "$work"' EXIT

java -jar "$jar" --command list-jobs
java -jar "$jar" --command echo --param message=ci
java -jar "$jar" --command noop --lifecycle prod

sample="$work/sample.csv"
printf 'id,note\n1,"a, b"\n2,c\n' > "$sample"
java -jar "$jar" --command csv-profile --param input="$sample" --param expectedRows=2 --json
java -jar "$jar" --command file-checksum --param input="$sample" \
  --param expectedSha256="$(sha256sum "$sample" | cut -d' ' -f1)" --json

rc=0
java -jar "$jar" --command csv-profile --param input="$sample" --param expectedRows=3 || rc=$?
[ "$rc" -eq 1 ] || fail "csv-profile row gate exited $rc, expected 1"
echo "the CLI jar runs its plain-JVM jobs and enforces their quality gates"
