#!/usr/bin/env bash
# SparkPipelineSpec boots a real SparkSession, so it is what proves Spark 4.2.0 runs on this JDK. It
# cancels itself on hosts that cannot open NIO selectors, and a canceled suite still leaves the build
# green, which would hide exactly what must be proven. Run after `./mvnw verify`.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

report=modules/processing/datacraft-spark/target/surefire-reports/datacraft-spark-scalatest.txt
[ -f "$report" ] || fail "scalatest report not found at $report"
grep -q "SparkPipelineSpec" "$report" || fail "SparkPipelineSpec never ran"
if ! grep -q "canceled 0," "$report"; then
  cat "$report"
  fail "the Spark suite was canceled: Spark did not start on this JDK"
fi
grep -E "Tests: succeeded" "$report"
