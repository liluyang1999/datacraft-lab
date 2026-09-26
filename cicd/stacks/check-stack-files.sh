#!/usr/bin/env bash
# Validates the deployment definitions without building or deploying anything: the Compose file and
# the Swarm stack interpolate with a complete environment, and each refuses to interpolate when a
# required secret is missing instead of falling back to a public default. Needs Docker with the
# Compose plugin. The Compose file reads deploy/compose/.env, so the check writes a copy of
# .env.example there and removes it afterwards; it refuses to run when that file already exists,
# because it may hold real secrets.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

# Public test-only values: the stack files require every secret, and nothing here is deployed.
export POSTGRES_PASSWORD=test-only-not-for-deployment
export AIRFLOW_API_SECRET_KEY=test-only-not-for-deployment
export AIRFLOW_JWT_SECRET=test-only-not-for-deployment
# base64url of the 32 ASCII bytes "datacraft-ci-test-only-fernetkey": a well-formed Fernet key.
export AIRFLOW_FERNET_KEY=ZGF0YWNyYWZ0LWNpLXRlc3Qtb25seS1mZXJuZXRrZXk=
export DATACRAFT_DATA_NODE=ci-test-node

env_file=deploy/compose/.env
[ ! -e "$env_file" ] || fail "$env_file exists; move it aside first, this check never overwrites it"
work=$(scratch_dir stack-files)
created=
cleanup() {
  if [ -n "$created" ]; then rm -f -- "$env_file"; fi
  rm -rf -- "$work"
}
trap cleanup EXIT
# The copied .env leaves the Airflow secrets empty, and its POSTGRES_PASSWORD is a placeholder that
# only deployment_env.py rejects; docker stack config reads no .env file at all.
cp deploy/compose/.env.example "$env_file"
created=1

compose=(docker compose -f deploy/compose/docker-compose.yml config --quiet)
stack=(docker stack config -c deploy/swarm/docker-stack.yml)
"${compose[@]}"
echo "deploy/compose/docker-compose.yml interpolates"
"${stack[@]}" > /dev/null
echo "deploy/swarm/docker-stack.yml interpolates"

expect_rejected() {
  local variable=$1 rc=0
  shift
  env -u "$variable" "$@" > "$work/rejected.txt" 2>&1 || rc=$?
  [ "$rc" -ne 0 ] || fail "'$*' accepted a missing $variable"
  if ! grep -q "$variable" "$work/rejected.txt"; then
    cat "$work/rejected.txt"
    fail "'$*' failed, but not because $variable is missing"
  fi
  echo "without $variable: $(head -n 1 "$work/rejected.txt")"
}
for variable in AIRFLOW_API_SECRET_KEY AIRFLOW_FERNET_KEY AIRFLOW_JWT_SECRET; do
  expect_rejected "$variable" "${compose[@]}"
done
for variable in POSTGRES_PASSWORD AIRFLOW_API_SECRET_KEY AIRFLOW_FERNET_KEY AIRFLOW_JWT_SECRET; do
  expect_rejected "$variable" "${stack[@]}"
done
