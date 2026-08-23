#!/usr/bin/env bash
# Builds all datacraft container images. The jar is compiled once in the builder image and reused.
#
# Env toggles:
#   BUILD_SPARK_IMAGE=true   also build datacraft/spark (large; needs a valid apache/spark tag)
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
cd "${REPO_ROOT}"

JAR_IMAGE="datacraft/jar-builder:latest"

log "Building builder image (compiles the CLI jar)..."
docker build -f deploy/docker/Dockerfile.build -t "${JAR_IMAGE}" .

log "Building datacraft/jvm (API + lightweight jobs)..."
docker build -f deploy/docker/Dockerfile.jvm --build-arg JAR_IMAGE="${JAR_IMAGE}" -t datacraft/jvm:latest .

log "Building datacraft/airflow (orchestrator)..."
docker build -f deploy/docker/Dockerfile.airflow --build-arg JAR_IMAGE="${JAR_IMAGE}" -t datacraft/airflow:latest .

if [[ "${BUILD_SPARK_IMAGE:-false}" == "true" ]]; then
  log "Building datacraft/spark (Spark job runner)..."
  docker build -f deploy/docker/Dockerfile.spark --build-arg JAR_IMAGE="${JAR_IMAGE}" -t datacraft/spark:latest .
fi

log "Done. Next: bash deploy/scripts/compose-up.sh"
