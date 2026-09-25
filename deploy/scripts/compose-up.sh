#!/usr/bin/env bash
# Starts the single-host stack (Postgres + Airflow + datacraft-api) via Docker Compose.
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
ensure_env_file
validate_compose_env
# The stack never pulls these (pull_policy: never), and a Compose-triggered build fails closed.
docker image inspect datacraft/airflow:latest datacraft/jvm:latest >/dev/null 2>&1 ||
  die "Images datacraft/airflow:latest and datacraft/jvm:latest not found; run bash deploy/scripts/build-images.sh first."
cd "${REPO_ROOT}/deploy/compose"

log "Starting datacraft-lab (single host)..."
docker compose up -d --wait --wait-timeout "${DATACRAFT_START_TIMEOUT:-300}"
docker compose ps

log "Startup checks passed. Use the loopback ports shown above via SSH forwarding or an authenticated tunnel."
