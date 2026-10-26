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

require_cmd python3 || die "Python 3 is required to compare the service's task specs."

# Only a task created from the current service spec counts: right after a redeploy the previous,
# already complete task is still the newest one for a moment. The container spec holds the image
# and the environment, so a redeploy that changes only .env (a new password, the same tag) is told
# apart as well as one that changes the tag.
if ! wanted="$(docker service inspect --format '{{json .Spec.TaskTemplate.ContainerSpec}}' "${service}")"; then
  die "Cannot inspect ${service}. Run this on a swarm manager after: bash deploy/scripts/swarm-deploy.sh"
fi

# Whether a task was created from the service's current container spec. The service carries the
# stop grace period the CLI fills in and empty fields (an empty DNSConfig after `docker stack
# deploy`), which the task's copy leaves out; both are ignored on both sides. Both specs hold the
# values of .env, so they reach Python on stdin, never as arguments.
current() { # <task id>
  local spec
  spec="$(docker inspect --type task --format '{{json .Spec.ContainerSpec}}' "$1")" || return 1
  printf '%s\n%s\n' "${wanted}" "${spec}" | python3 -c '
import json, sys

def bare(value):
    if isinstance(value, dict):
        value = {key: bare(item) for key, item in value.items() if key != "StopGracePeriod"}
        return {key: item for key, item in value.items() if item not in (None, {}, [], "", False, 0)}
    if isinstance(value, list):
        return [bare(item) for item in value]
    return value

service, task = (bare(json.loads(line)) for line in sys.stdin.read().splitlines()[:2])
sys.exit(service != task)'
}

log "Waiting up to ${timeout}s for ${service} (db migrate + admin user)..."
deadline=$((SECONDS + timeout))
while :; do
  # Newest task first (one slot): its ID, then its state, e.g. "Complete 3 seconds ago".
  tasks="$(docker service ps --no-trunc --format '{{.ID}}|{{.CurrentState}}' "${service}")"
  latest="${tasks%%$'\n'*}"
  if [[ "${latest#*|}" == Complete* ]] && current "${latest%%|*}"; then
    log "Airflow initialised."
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    die "${service} did not complete within ${timeout}s (newest task: ${latest#*|}). Inspect it with: docker service ps --no-trunc ${service} and docker service logs ${service}"
  fi
  sleep "${interval}"
done
