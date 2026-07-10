#!/usr/bin/env bash
# One-time Airflow DB migration + admin user creation for the SWARM deployment.
# (The Compose deployment runs this automatically via the airflow-init service.)
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
set -a
# shellcheck disable=SC1091
source "${REPO_ROOT}/deploy/compose/.env"
set +a

CID="$(docker ps --filter name=datacraft_airflow-scheduler -q | head -n1)"
[[ -n "${CID}" ]] || die "No running airflow-scheduler task found. Deploy the stack first (swarm-deploy.sh)."

log "Running db migrate + admin user creation inside ${CID}..."
docker exec "${CID}" bash -lc "airflow db migrate && airflow users create \
  --username '${AIRFLOW_ADMIN_USERNAME:-admin}' \
  --password '${AIRFLOW_ADMIN_PASSWORD:-admin}' \
  --firstname Data --lastname Craft --role Admin \
  --email '${AIRFLOW_ADMIN_EMAIL:-admin@example.com}' || true"
log "Airflow initialised."
