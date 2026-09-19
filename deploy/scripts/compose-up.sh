#!/usr/bin/env bash
# Starts the single-host stack (Postgres + Airflow + datacraft-api) via Docker Compose.
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
ensure_env_file
validate_compose_env
cd "${REPO_ROOT}/deploy/compose"

log "Starting datacraft-lab (single host)..."
docker compose up -d --wait --wait-timeout "${DATACRAFT_START_TIMEOUT:-300}"
docker compose ps

log "Startup checks passed. Use the loopback ports shown above via SSH forwarding or an authenticated tunnel."
