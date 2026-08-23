#!/usr/bin/env bash
# Starts the single-host stack (Postgres + Airflow + datacraft-api) via Docker Compose.
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
ensure_env_file
cd "${REPO_ROOT}/deploy/compose"

log "Starting datacraft-lab (single host)..."
docker compose up -d
docker compose ps

# shellcheck disable=SC1091
source .env 2>/dev/null || true
log "Airflow UI:   http://localhost:${AIRFLOW_WEB_PORT:-8080}"
log "datacraft-api: http://localhost:${DATACRAFT_API_PORT:-8088}/health"
