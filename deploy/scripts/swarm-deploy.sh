#!/usr/bin/env bash
# Deploys the multi-host stack to Docker Swarm. Images must already be pushed to ${DATACRAFT_REGISTRY}
# with the exact ${DATACRAFT_TAG} (not latest); run `docker login` for a private registry first.
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

# localhost:5000 is valid: a registry:2 service published through the routing mesh serves every node.
: "${DATACRAFT_REGISTRY:?Set DATACRAFT_REGISTRY (e.g. registry.example.com:5000) in deploy/compose/.env}"
# A moving tag lets nodes resolve different images and makes rollbacks ambiguous.
: "${DATACRAFT_TAG:?Set DATACRAFT_TAG to the exact version pushed to ${DATACRAFT_REGISTRY} in deploy/compose/.env}"
[[ "${DATACRAFT_TAG}" != latest ]] || die "Set DATACRAFT_TAG to the exact version pushed to ${DATACRAFT_REGISTRY}, not latest."
validate_compose_env
# Redis demands this password, and the stack puts it into the broker URL. The Compose file has no
# Redis, so the check of its configuration above does not cover it.
[[ "${REDIS_PASSWORD:-}" =~ ^[A-Za-z0-9_-]{32,}$ ]] ||
  die "Set REDIS_PASSWORD in deploy/compose/.env to at least 32 URL-safe letters, digits, _ or - (generate one with: openssl rand -hex 32)."
for other in POSTGRES_PASSWORD AIRFLOW_ADMIN_PASSWORD AIRFLOW_API_SECRET_KEY AIRFLOW_JWT_SECRET \
  AIRFLOW_FERNET_KEY DATACRAFT_API_TOKEN; do
  [[ "${REDIS_PASSWORD}" != "${!other:-}" ]] || die "REDIS_PASSWORD must be independent of ${other}."
done

if ! docker node ls >/dev/null 2>&1; then
  die "This host is not a swarm manager. Initialise one with:  docker swarm init"
fi

log "Deploying datacraft stack to swarm..."
# Forwards this manager's registry login (docker login) so every node can pull private images.
docker stack deploy --with-registry-auth -c deploy/swarm/docker-stack.yml datacraft

log "Stack deployed. Wait for the one-shot DB migration + admin user:  bash deploy/scripts/airflow-init.sh"
