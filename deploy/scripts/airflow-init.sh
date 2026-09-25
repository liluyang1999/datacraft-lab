#!/usr/bin/env bash
# Waits on a swarm manager until the SWARM stack's one-shot airflow-init service (db migrate + admin
# user) has completed. The Compose deployment gates its services on its own airflow-init container.
#
# Env: DATACRAFT_INIT_TIMEOUT (seconds, default 600), DATACRAFT_INIT_POLL_SECONDS (default 5).
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
service="datacraft_airflow-init"
timeout="${DATACRAFT_INIT_TIMEOUT:-600}"
interval="${DATACRAFT_INIT_POLL_SECONDS:-5}"
[[ "${timeout}" =~ ^[0-9]+$ && "${interval}" =~ ^[0-9]+$ ]] ||
  die "DATACRAFT_INIT_TIMEOUT and DATACRAFT_INIT_POLL_SECONDS must be whole seconds."

# Only a task created from the current service spec counts: right after a redeploy that changes the
# image, the previous (already complete) task is still the newest one for a moment.
if ! image="$(docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${service}")"; then
  die "Cannot inspect ${service}. Run this on a swarm manager after: bash deploy/scripts/swarm-deploy.sh"
fi

log "Waiting up to ${timeout}s for ${service} (db migrate + admin user)..."
deadline=$((SECONDS + timeout))
while :; do
  # Newest task first (one slot); full image reference, then its state, e.g. "Complete 3 seconds ago".
  tasks="$(docker service ps --no-trunc --format '{{.Image}}|{{.CurrentState}}' "${service}")"
  latest="${tasks%%$'\n'*}"
  if [[ "${latest%%|*}" == "${image}" && "${latest#*|}" == Complete* ]]; then
    log "Airflow initialised."
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    die "${service} did not complete within ${timeout}s (newest task: ${latest#*|}). Inspect it with: docker service ps --no-trunc ${service} and docker service logs ${service}"
  fi
  sleep "${interval}"
done
