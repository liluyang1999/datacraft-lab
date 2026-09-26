#!/usr/bin/env bash
# Ephemeral CI only: build the actual images and verify both runtime surfaces.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

container="datacraft-ci-api-${GITHUB_RUN_ID:-$$}"
probe_volume="datacraft-ci-data-${GITHUB_RUN_ID:-$$}"
# Build-context canaries: .dockerignore must keep database dumps and env files out of every image.
canaries=(.env backups/ci-probe.dump)
created=()
created_backups=false
cleanup() {
  local status=$?
  trap - EXIT
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker volume rm -f "$probe_volume" >/dev/null 2>&1 || true
  if ((${#created[@]})); then rm -f -- "${created[@]}"; fi
  if [[ "$created_backups" == true ]]; then rmdir -- backups 2>/dev/null || true; fi
  exit "$status"
}
trap cleanup EXIT
fail() {
  echo "$*" >&2
  exit 1
}

for canary in "${canaries[@]}"; do
  [[ ! -e "$canary" && ! -L "$canary" ]] || fail "Refusing to overwrite existing $canary."
done
if [[ ! -d backups ]]; then
  created_backups=true
  mkdir backups
fi
for canary in "${canaries[@]}"; do
  created+=("$canary")
  : > "$canary"
done

bash deploy/scripts/build-images.sh

docker run --rm --entrypoint sh datacraft/jar-builder:latest -c \
  'test ! -e /workspace/backups/ci-probe.dump && test ! -e /workspace/.env' ||
  fail "The build context leaked backups/ or .env into datacraft/jar-builder."

# Both runtime images carry the freshly pulled Temurin JRE, at or above the documented floor.
jre_image="${JRE_IMAGE:-eclipse-temurin:25-jre}"
jre_release() { # <image> <key>: one value from the JRE's release file
  docker run --rm --entrypoint sed "$1" -n "s/^$2=\"\\(.*\\)\"\$/\\1/p" /opt/java/openjdk/release
}
base_version=$(jre_release "$jre_image" JAVA_VERSION)
base_runtime=$(jre_release "$jre_image" JAVA_RUNTIME_VERSION)
[[ -n "$base_version" && -n "$base_runtime" ]] || fail "Cannot read the Java version of $jre_image."
printf '%s\n' 25.0.4.1 "$base_version" | sort -V -C || fail "$jre_image ships Java $base_version, below 25.0.4.1."
for image in datacraft/jvm:latest datacraft/airflow:latest; do
  image_runtime=$(jre_release "$image" JAVA_RUNTIME_VERSION)
  [[ "$image_runtime" == "$base_runtime" ]] ||
    fail "$image runs Java '$image_runtime', but the freshly pulled $jre_image has '$base_runtime'."
done

docker run -d --name "$container" datacraft/jvm:latest >/dev/null
healthy=false
for ((attempt=0; attempt<30; attempt++)); do
  if docker exec "$container" curl --fail --silent http://127.0.0.1:8080/health; then
    healthy=true
    break
  fi
  sleep 2
done
if [[ "$healthy" != true ]]; then
  docker logs "$container"
  exit 1
fi
[[ "$(docker exec "$container" id -u)" != 0 ]] || fail "datacraft/jvm must not run as root."
if docker exec "$container" test -w /opt/datacraft/datacraft-cli.jar; then
  fail "The datacraft/jvm runtime user can modify the CLI jar."
fi
# Docker seeds an empty named volume from the first image that mounts it. Compose may start the API
# first, so its image must not ship the data directory (see the next probe).
if docker exec "$container" test -e /opt/datacraft/data; then
  fail "datacraft/jvm must not contain /opt/datacraft/data."
fi
docker exec "$container" java -jar /opt/datacraft/datacraft-cli.jar --command noop --json
# A Spark job on the Spark-less image answers with a FAILED result instead of hanging the request.
spark_response=$(docker exec "$container" curl -sS -m 10 -w '\n%{http_code}' -X POST \
  http://127.0.0.1:8080/jobs/spark-version/runs) || true
[[ "${spark_response##*$'\n'}" == 500 && "$spark_response" == *'"status":"FAILED"'* ]] ||
  fail "POST /jobs/spark-version/runs on datacraft/jvm: expected HTTP 500 FAILED, got: $spark_response"

docker run --rm --entrypoint bash datacraft/airflow:latest -c '
set -euo pipefail
python -m pip check
python - <<"PY"
import importlib.metadata as metadata
from packaging.version import Version
for name, floor in (("apache-airflow", "3.3.2"), ("apache-airflow-providers-fab", "3.9.0")):
    installed = metadata.version(name)
    assert Version(installed) >= Version(floor), f"{name} {installed} is below {floor}"
PY
airflow version
java -version
spark-submit --version'

# Fresh named volume, mounted in the order Compose may use: API (read-only) first, then an Airflow
# task under a non-default AIRFLOW_UID through the real entrypoint. The task must be able to write,
# and the non-root API must read the result but never write.
docker volume create "$probe_volume" >/dev/null
docker run --rm --entrypoint sh -v "$probe_volume:/opt/datacraft/data:ro" datacraft/jvm:latest -c \
  'test -d /opt/datacraft/data'
docker run --rm --user 1000:0 -v "$probe_volume:/opt/datacraft/data" datacraft/airflow:latest bash -c \
  'mkdir -p /opt/datacraft/data/ingest && echo probe > /opt/datacraft/data/ingest/probe && touch /opt/datacraft/data/probe' ||
  fail "An Airflow task with AIRFLOW_UID=1000 cannot write the fresh datacraft-data volume."
docker run --rm --entrypoint sh -v "$probe_volume:/opt/datacraft/data:ro" datacraft/jvm:latest -c \
  'test "$(cat /opt/datacraft/data/ingest/probe)" = probe && ! touch /opt/datacraft/data/api-write 2>/dev/null' ||
  fail "datacraft/jvm cannot read Airflow output, or can write the read-only data volume."

docker run --rm --entrypoint python \
  -v "$PWD:/workspace:ro" datacraft/airflow:latest \
  -B /workspace/tests/smoke/airflow_runtime_smoke.py --jar /opt/datacraft/datacraft-cli.jar
