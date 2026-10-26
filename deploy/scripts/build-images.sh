#!/usr/bin/env bash
# Builds all datacraft container images. The jar is compiled once in the builder image and reused.
#
# Env toggles:
#   BUILD_SPARK_IMAGE=true   also build datacraft/spark (its base image is a 740 MB download)
#   MAVEN_IMAGE / JRE_IMAGE / AIRFLOW_IMAGE / SPARK_IMAGE   override a public base image
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
cd "${REPO_ROOT}"

JAR_IMAGE="datacraft/jar-builder:latest"
# Public bases; keep these defaults equal to the ARG defaults in deploy/docker/Dockerfile.*.
JRE_IMAGE="${JRE_IMAGE:-eclipse-temurin:25-jre}"
AIRFLOW_IMAGE="${AIRFLOW_IMAGE:-apache/airflow:3.3.2}"
SPARK_IMAGE="${SPARK_IMAGE:-apache/spark:4.2.0-scala2.13-java25-python3-ubuntu}"

# A registry drops a request now and then ("failed to fetch anonymous token ... EOF"); a second try
# usually succeeds, so one dropped connection during an explicit pull does not end the build. The
# builder's `docker build --pull` is not retried: a failure there would be retried along with a
# failed compilation, so running the script again is left to the operator.
pull() {
  local attempt
  for attempt in 1 2 3; do
    docker pull "$1" && return 0
    if ((attempt < 3)); then
      warn "docker pull $1 failed (attempt ${attempt} of 3); trying again in $((attempt * 10)) s."
      sleep $((attempt * 10))
    fi
  done
  die "docker pull $1 failed 3 times."
}

# Bases are refreshed so a host with stale cached images still gets current JDK/OS patches. Only the
# builder may use --pull: the other builds start FROM the local-only ${JAR_IMAGE}, which --pull would
# look up on docker.io (a third-party namespace) instead. Their public bases are pulled explicitly.
log "Building builder image (compiles the CLI jar)..."
if [[ -n "${MAVEN_IMAGE:-}" ]]; then
  docker build --pull -f deploy/docker/Dockerfile.build --build-arg MAVEN_IMAGE="${MAVEN_IMAGE}" -t "${JAR_IMAGE}" .
else
  # Dockerfile.build owns the Maven default.
  docker build --pull -f deploy/docker/Dockerfile.build -t "${JAR_IMAGE}" .
fi

log "Building datacraft/jvm (API + lightweight jobs)..."
pull "${JRE_IMAGE}"
docker build -f deploy/docker/Dockerfile.jvm --build-arg JAR_IMAGE="${JAR_IMAGE}" \
  --build-arg JRE_IMAGE="${JRE_IMAGE}" -t datacraft/jvm:latest .

log "Building datacraft/airflow (orchestrator)..."
pull "${AIRFLOW_IMAGE}"
docker build -f deploy/docker/Dockerfile.airflow --build-arg JAR_IMAGE="${JAR_IMAGE}" \
  --build-arg JRE_IMAGE="${JRE_IMAGE}" --build-arg AIRFLOW_IMAGE="${AIRFLOW_IMAGE}" -t datacraft/airflow:latest .

if [[ "${BUILD_SPARK_IMAGE:-false}" == "true" ]]; then
  log "Building datacraft/spark (Spark job runner)..."
  pull "${SPARK_IMAGE}"
  docker build -f deploy/docker/Dockerfile.spark --build-arg JAR_IMAGE="${JAR_IMAGE}" \
    --build-arg JRE_IMAGE="${JRE_IMAGE}" --build-arg SPARK_IMAGE="${SPARK_IMAGE}" -t datacraft/spark:latest .
fi

log "Done. Next: bash deploy/scripts/compose-up.sh"
