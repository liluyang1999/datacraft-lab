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
# The official bases with the pinned Temurin release of deploy/docker/Dockerfile.temurin on top:
# every image runs that release, not whatever Java the official images carry at the moment.
TEMURIN_JDK_IMAGE="datacraft/maven:latest"
TEMURIN_JRE_IMAGE="datacraft/jre:latest"
# Public bases; keep the Airflow and Spark defaults equal to the ARG defaults in deploy/docker.
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.10.0-eclipse-temurin-25}"
JRE_IMAGE="${JRE_IMAGE:-eclipse-temurin:25-jre}"
AIRFLOW_IMAGE="${AIRFLOW_IMAGE:-apache/airflow:3.3.2}"
SPARK_IMAGE="${SPARK_IMAGE:-apache/spark:4.2.0-scala2.13-java25-python3-ubuntu}"

# A registry drops a request now and then ("failed to fetch anonymous token ... EOF"); a second try
# usually succeeds, so one dropped connection during a pull does not end the build.
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

# Every public base is pulled first, so a host with stale cached images still gets current OS
# patches. No build uses --pull: each one starts FROM a local datacraft/* image, which --pull would
# look up on docker.io (a third-party namespace) instead.
log "Building datacraft/maven and datacraft/jre (the pinned Temurin release)..."
pull "${MAVEN_IMAGE}"
docker build -f deploy/docker/Dockerfile.temurin --target jdk --build-arg BASE_IMAGE="${MAVEN_IMAGE}" \
  -t "${TEMURIN_JDK_IMAGE}" deploy/docker
pull "${JRE_IMAGE}"
docker build -f deploy/docker/Dockerfile.temurin --target jre --build-arg BASE_IMAGE="${JRE_IMAGE}" \
  -t "${TEMURIN_JRE_IMAGE}" deploy/docker

log "Building builder image (compiles the CLI jar)..."
docker build -f deploy/docker/Dockerfile.build --build-arg MAVEN_IMAGE="${TEMURIN_JDK_IMAGE}" -t "${JAR_IMAGE}" .

log "Building datacraft/jvm (API + lightweight jobs)..."
docker build -f deploy/docker/Dockerfile.jvm --build-arg JAR_IMAGE="${JAR_IMAGE}" \
  --build-arg JRE_IMAGE="${TEMURIN_JRE_IMAGE}" -t datacraft/jvm:latest .

log "Building datacraft/airflow (orchestrator)..."
pull "${AIRFLOW_IMAGE}"
docker build -f deploy/docker/Dockerfile.airflow --build-arg JAR_IMAGE="${JAR_IMAGE}" \
  --build-arg JRE_IMAGE="${TEMURIN_JRE_IMAGE}" --build-arg AIRFLOW_IMAGE="${AIRFLOW_IMAGE}" -t datacraft/airflow:latest .

if [[ "${BUILD_SPARK_IMAGE:-false}" == "true" ]]; then
  log "Building datacraft/spark (Spark job runner)..."
  pull "${SPARK_IMAGE}"
  docker build -f deploy/docker/Dockerfile.spark --build-arg JAR_IMAGE="${JAR_IMAGE}" \
    --build-arg JRE_IMAGE="${TEMURIN_JRE_IMAGE}" --build-arg SPARK_IMAGE="${SPARK_IMAGE}" -t datacraft/spark:latest .
fi

log "Done. Next: bash deploy/scripts/compose-up.sh"
