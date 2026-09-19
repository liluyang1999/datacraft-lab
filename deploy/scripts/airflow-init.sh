#!/usr/bin/env bash
# One-time Airflow DB migration + admin user creation for the SWARM deployment.
# (The Compose deployment runs this automatically via the airflow-init service.)
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
CID="$(docker ps --filter label=com.docker.swarm.service.name=datacraft_airflow-scheduler -q | head -n1)"
[[ -n "${CID}" ]] || die "No running airflow-scheduler task found. Deploy the stack first (swarm-deploy.sh)."

log "Running db migrate + admin user creation inside ${CID}..."
docker exec "${CID}" /opt/datacraft/airflow-bootstrap.sh
log "Airflow initialised."
