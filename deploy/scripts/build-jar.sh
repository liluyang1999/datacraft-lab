#!/usr/bin/env bash
# Builds the shaded datacraft-cli jar using local Maven (no Docker required).
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

cd "${REPO_ROOT}"
if require_cmd mvn; then
  log "Building CLI jar with local Maven..."
  mvn -B -ntp -pl datacraft-cli -am package -DskipTests
elif [[ -x ./mvnw ]]; then
  log "Building CLI jar with the Maven Wrapper..."
  ./mvnw -B -ntp -pl datacraft-cli -am package -DskipTests
else
  die "Maven not found. Install Maven 3.9+ or use deploy/scripts/build-images.sh (builds the jar in a container)."
fi
log "Built: datacraft-cli/target/datacraft-cli.jar"
