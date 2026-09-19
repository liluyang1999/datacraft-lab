#!/usr/bin/env bash
# Ephemeral CI only: build the actual images and verify both runtime surfaces.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

bash deploy/scripts/build-images.sh
container="datacraft-ci-api-${GITHUB_RUN_ID:-$$}"
trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
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
docker exec "$container" java -jar /opt/datacraft/datacraft-cli.jar --command noop --json
docker run --rm --entrypoint bash datacraft/airflow:latest -c \
  'python -m pip check && airflow version && java -version && spark-submit --version'
docker run --rm --entrypoint python \
  -v "$PWD:/workspace:ro" datacraft/airflow:latest \
  -B /workspace/orchestration/airflow/tests/runtime_smoke.py --jar /opt/datacraft/datacraft-cli.jar
