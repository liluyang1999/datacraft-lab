#!/usr/bin/env bash
# Starts the complete Compose stack from the images smoke-images.sh built and exercises the real
# scheduler -> Execution API -> LocalExecutor path: fresh generated secrets, the admin login, the
# API token, the data volume's permissions, the three image-baked DAGs, one triggered run, and a
# metadata backup
# restored into a separate database. Needs Docker. Disposable CI runners only: it refuses to run
# unless CI=true and when deploy/compose/.env exists, and it removes the stack and its volumes.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

[[ "${CI:-}" == true ]] || fail "Run only on an isolated CI runner."
env_file="$PWD/deploy/compose/.env"
[[ ! -e "$env_file" && ! -L "$env_file" ]] || fail "Preserving existing .env; refusing smoke test."
export COMPOSE_PROJECT_NAME="datacraft-smoke-${GITHUB_RUN_ID:-$$}"
compose=(docker compose -f deploy/compose/docker-compose.yml)
dags=(datacraft_engine_jobs datacraft_spark_etl datacraft_sftp_ingest)
created_env=false
backup_file=""
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$created_env" == true ]]; then
    if ((status != 0)); then
      # Each part becomes an annotation of the failed step (cicd/step.py keeps the last eight), so
      # the parts that most often explain a failure come last.
      local service
      for service in postgres airflow-triggerer datacraft-api airflow-init airflow-apiserver \
        airflow-dag-processor airflow-scheduler; do
        diagnose "Compose: log of $service" "${compose[@]}" logs --no-color --no-log-prefix --tail 30 "$service"
      done
      # Task output goes to the airflow-logs volume, which the teardown below removes.
      diagnose "Compose: Airflow task logs" "${compose[@]}" exec -T airflow-scheduler sh -c \
        'find /opt/airflow/logs -type f -name "*.log" -exec tail -n 25 {} +'
      diagnose "Compose: containers" "${compose[@]}" ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
    fi
    # Quiet: the removal of each container would otherwise be the last lines of a failed step.
    "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || {
      echo "The Compose stack could not be removed." >&2
      status=1
    }
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

# The generated API token is the one datacraft-api demands on its loopback port.
api_port = os.environ.get("DATACRAFT_API_PORT") or values.get("DATACRAFT_API_PORT") or "8088"


def api_status(path, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    request = urllib.request.Request(f"http://127.0.0.1:{api_port}{path}", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


if api_status("/health") != 200:
    sys.exit("datacraft-api does not answer /health on its loopback port.")
if api_status("/jobs") != 401:
    sys.exit("datacraft-api listed its jobs without a token.")
if api_status("/jobs", values["DATACRAFT_API_TOKEN"] + "0") != 401:
    sys.exit("datacraft-api accepted a wrong token.")
if api_status("/jobs", values["DATACRAFT_API_TOKEN"]) != 200:
    sys.exit("The DATACRAFT_API_TOKEN in .env does not open datacraft-api.")
PY

# Airflow tasks can write the shared data volume even though the API may have mounted it first; the
# non-root API reads it through a read-only mount confined by DATACRAFT_DATA_ROOT.
"${compose[@]}" exec -T airflow-scheduler python -c \
  'import pathlib; pathlib.Path("/opt/datacraft/data/.compose-smoke").write_text("probe")'
# shellcheck disable=SC2016
"${compose[@]}" exec -T datacraft-api sh -c '
test "$(id -u)" != 0 &&
test "$DATACRAFT_DATA_ROOT" = /opt/datacraft/data &&
test "$(cat /opt/datacraft/data/.compose-smoke)" = probe &&
awk '\''$5 == "/opt/datacraft/data" { split($6, options, ","); ro = options[1] == "ro" } END { exit !ro }'\'' /proc/self/mountinfo' ||
  fail "datacraft-api must run non-root with a read-only data mount at DATACRAFT_DATA_ROOT."

# The DAG processor serializes newly discovered DAGs asynchronously. All three must appear: a DAG
# that fails to import inside the image would otherwise go unnoticed, since only one is triggered.
registered() {
  local dag
  for dag in "${dags[@]}"; do
    "${compose[@]}" exec -T airflow-scheduler airflow dags details --output json "$dag" >/dev/null 2>&1 ||
      return 1
  done
}
ready=false
for ((attempt=0; attempt<36; attempt++)); do
  if registered; then
    ready=true
    break
  fi
  sleep 5
done
[[ "$ready" == true ]] || fail "Not every DAG was registered: ${dags[*]}."
# The registered DAGs are the ones baked into the image next to the jar, not a host checkout.
if ! "${compose[@]}" exec -T airflow-scheduler python -c '
import sys
sys.exit(any(line.split()[4] == "/opt/airflow/dags" for line in open("/proc/self/mountinfo")))'; then
  fail "DAGs are mounted into the scheduler; expected the image-baked DAGs."
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
    fail "Scheduled engine DAG failed."
  fi
  sleep 5
done
[[ "$success" == true ]] || fail "Scheduled engine DAG timed out."

# Prove the documented metadata backup format is restorable into a separate database.
backup_file=$(mktemp)
# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup_file"
# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" datacraft_restore_check'
# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d datacraft_restore_check --exit-on-error' < "$backup_file"
# shellcheck disable=SC2016
restored=$("${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d datacraft_restore_check -Atc "SELECT count(*) FROM dag_run WHERE dag_id = '\''datacraft_engine_jobs'\'' AND state = '\''success'\''"')
[[ "$restored" == 1 ]] || fail "Restored metadata does not contain the successful run."
# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" datacraft_restore_check'
notice "Compose stack verified" "Generated secrets, the admin login and the API token work;" \
  "${#dags[@]} DAGs are registered from the image; datacraft_engine_jobs ran through the scheduler," \
  "the Execution API and LocalExecutor; the metadata backup restores into a separate database."
