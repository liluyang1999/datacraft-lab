#!/usr/bin/env bash
# Deploys the multi-host stack to Docker Swarm. Images must already be pushed to ${DATACRAFT_REGISTRY}.
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
ensure_env_file
cd "${REPO_ROOT}"

# Export .env so the stack file's ${VARS} interpolate (stack deploy does not read .env).
set -a
# shellcheck disable=SC1091
source deploy/compose/.env
set +a
validate_compose_env

: "${DATACRAFT_REGISTRY:?Set DATACRAFT_REGISTRY (e.g. registry.example.com:5000) in deploy/compose/.env}"

if ! docker node ls >/dev/null 2>&1; then
  die "This host is not a swarm manager. Initialise one with:  docker swarm init"
fi

log "Deploying datacraft stack to swarm..."
docker stack deploy -c deploy/swarm/docker-stack.yml datacraft

log "Stack deployed. Initialise the DB + admin user once:  bash deploy/scripts/airflow-init.sh"
