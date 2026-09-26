#!/usr/bin/env bash
# Callers branch on the documented exit codes: 1 = the job failed and its FAILED result was still
# written, 2 = unknown command or bad arguments. Neither may end in an uncaught exception. Run after
# the jar is built; CLI_JAR overrides its path.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

jar=$CLI_JAR
[ -f "$jar" ] || fail "CLI jar missing at $jar"
out=$(scratch_dir cli-exit-codes)
trap 'rm -rf -- "$out"' EXIT

expect_exit() {
  local expected=$1 rc=0
  shift
  java -jar "$jar" "$@" 2> "$out/stderr.txt" || rc=$?
  cat "$out/stderr.txt"
  [ "$rc" -eq "$expected" ] || fail "'$*' exited $rc, expected $expected"
  if grep -q 'Exception in thread' "$out/stderr.txt"; then
    fail "'$*' ended with an uncaught exception"
  fi
}

# Spark is provided, so on a plain JVM a Spark job must fail as a structured result.
expect_exit 1 --command spark-version --json --result-file "$out/spark.json"
[ -f "$out/spark.json" ] || fail "spark-version wrote no --result-file"
cat "$out/spark.json"
if ! grep -q '"jobName":"spark-version"' "$out/spark.json" \
  || ! grep -q '"status":"FAILED"' "$out/spark.json"; then
  fail "spark-version did not write a FAILED result"
fi
expect_exit 2 --command does-not-exist
expect_exit 2 --command echo --param broken
expect_exit 2 --command echo --config /nonexistent.properties
echo "the CLI exits 1 for a failed job and 2 for bad arguments, without uncaught exceptions"
