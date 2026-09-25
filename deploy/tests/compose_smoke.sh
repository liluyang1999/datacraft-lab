#!/usr/bin/env bash
# Disposable CI only: exercise the real scheduler -> Execution API -> LocalExecutor path.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
[[ "${CI:-}" == true ]] || { echo "Run only on an isolated CI runner." >&2; exit 1; }
env_file="$PWD/deploy/compose/.env"
[[ ! -e "$env_file" && ! -L "$env_file" ]] || { echo "Preserving existing .env; refusing smoke test." >&2; exit 1; }
export COMPOSE_PROJECT_NAME="datacraft-smoke-${GITHUB_RUN_ID:-$$}"
compose=(docker compose -f deploy/compose/docker-compose.yml)
created_env=false
backup_file=""
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$created_env" == true ]]; then
    if ((status != 0)); then
      "${compose[@]}" ps -a || true
      "${compose[@]}" logs --tail 80 || true
    fi
    "${compose[@]}" down -v --remove-orphans || status=1
    rm -f -- "$env_file"
  fi
  [[ -z "$backup_file" ]] || rm -f -- "$backup_file"
  exit "$status"
}
trap cleanup EXIT
python3 -B deploy/scripts/deployment_env.py init
created_env=true

# Resolved configuration: locally built images are never pulled (the datacraft/* names resolve to a
# third-party Docker Hub namespace), and the DAGs come from the image rather than a bind mount.
"${compose[@]}" config --format json | python3 -B -c '
import json, sys
services = json.load(sys.stdin)["services"]
pulled = sorted(name for name, service in services.items()
                if "build" in service and service.get("pull_policy") != "never")
mounted = sorted(name for name, service in services.items()
                 for volume in service.get("volumes", []) if volume.get("target") == "/opt/airflow/dags")
if pulled or mounted:
    sys.exit(f"Buildable services without pull_policy never: {pulled}; DAG bind mounts: {mounted}")
'
bash deploy/scripts/compose-up.sh

# The admin password reached `airflow users create` on stdin and is the one that authenticates.
python3 -B - "$env_file" <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

with open(sys.argv[1], encoding="utf-8") as source:
    values = dict(line.split("=", 1) for line in source.read().splitlines()
                  if line.strip() and not line.startswith("#"))
port = os.environ.get("AIRFLOW_WEB_PORT") or values.get("AIRFLOW_WEB_PORT") or "8080"


def token_status(password):
    body = json.dumps({"username": values["AIRFLOW_ADMIN_USERNAME"], "password": password}).encode()
    request = urllib.request.Request(f"http://127.0.0.1:{port}/auth/token", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


if token_status(values["AIRFLOW_ADMIN_PASSWORD"]) != 201:
    sys.exit("The configured admin password does not authenticate.")
if token_status(values["AIRFLOW_ADMIN_PASSWORD"] + "-wrong") != 401:
    sys.exit("The token endpoint did not reject a wrong password.")
PY

# Airflow tasks can write the shared data volume even though the API may have mounted it first; the
# non-root API reads it through a read-only mount confined by DATACRAFT_DATA_ROOT.
"${compose[@]}" exec -T airflow-scheduler python -c \
  'import pathlib; pathlib.Path("/opt/datacraft/data/.compose-smoke").write_text("probe")'
"${compose[@]}" exec -T datacraft-api sh -c '
test "$(id -u)" != 0 &&
test "$DATACRAFT_DATA_ROOT" = /opt/datacraft/data &&
test "$(cat /opt/datacraft/data/.compose-smoke)" = probe &&
awk '\''$5 == "/opt/datacraft/data" { split($6, options, ","); ro = options[1] == "ro" } END { exit !ro }'\'' /proc/self/mountinfo' ||
  { echo "datacraft-api must run non-root with a read-only data mount at DATACRAFT_DATA_ROOT." >&2; exit 1; }

# The DAG processor serializes newly discovered DAGs asynchronously.
ready=false
for ((attempt=0; attempt<36; attempt++)); do
  if "${compose[@]}" exec -T airflow-scheduler airflow dags details --output json datacraft_engine_jobs >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 5
done
[[ "$ready" == true ]] || { echo "Engine DAG was not registered." >&2; exit 1; }
# The registered DAGs are the ones baked into the image next to the jar, not a host checkout.
if ! "${compose[@]}" exec -T airflow-scheduler python -c '
import sys
sys.exit(any(line.split()[4] == "/opt/airflow/dags" for line in open("/proc/self/mountinfo")))'; then
  echo "DAGs are mounted into the scheduler; expected the image-baked DAGs." >&2
  exit 1
fi
"${compose[@]}" exec -T airflow-scheduler airflow dags unpause datacraft_engine_jobs
"${compose[@]}" exec -T airflow-scheduler airflow dags trigger --run-id cloud-smoke datacraft_engine_jobs
success=false
for ((attempt=0; attempt<60; attempt++)); do
  state=$("${compose[@]}" exec -T airflow-scheduler airflow dags state datacraft_engine_jobs cloud-smoke)
  if grep -qx success <<< "$state"; then
    success=true
    break
  fi
  if grep -qx failed <<< "$state"; then
    echo "Scheduled engine DAG failed." >&2
    exit 1
  fi
  sleep 5
done
[[ "$success" == true ]] || { echo "Scheduled engine DAG timed out." >&2; exit 1; }

# Prove the documented metadata backup format is restorable into a separate database.
backup_file=$(mktemp)
"${compose[@]}" exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup_file"
"${compose[@]}" exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" datacraft_restore_check'
"${compose[@]}" exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d datacraft_restore_check --exit-on-error' < "$backup_file"
restored=$("${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d datacraft_restore_check -Atc "SELECT count(*) FROM dag_run WHERE dag_id = '\''datacraft_engine_jobs'\'' AND state = '\''success'\''"')
[[ "$restored" == 1 ]] || { echo "Restored metadata does not contain the successful run." >&2; exit 1; }
"${compose[@]}" exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" datacraft_restore_check'
echo "Verified real Compose scheduler, Execution API, LocalExecutor and metadata restore."
