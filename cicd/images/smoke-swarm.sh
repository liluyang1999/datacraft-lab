#!/usr/bin/env bash
# Rehearses the Swarm deployment of docs/guides/deployment.md (section 6) on a swarm of one node,
# with the images smoke-images.sh built: a registry on this host, both images pushed under an exact
# tag, then swarm-deploy.sh and airflow-init.sh exactly as an operator runs them. It requires every
# long-running service to run (a service with a health check counts once it is healthy), the engine
# API to answer on the overlay network only and its token only, the Airflow UI to sign the generated
# admin in through the routing mesh, Redis to refuse a client without its password, the overlay
# network to be an encrypted one, and one DAG run to pass through the scheduler, Redis and a
# Celery worker. Then the documented upgrade: the same images under a second tag, deployed again.
# The one-shot initializer has to run again, the idle worker has to stop for its replacement, and
# the run from before the upgrade has to be there afterwards.
#
# One node proves that the stack file, the two scripts and the images work together. It says
# nothing about placement, about failover or about traffic between nodes, encrypted or not.
#
# Needs Docker. Disposable CI runners only: it refuses to run unless CI=true, when
# deploy/compose/.env exists or when the Docker engine is already part of a swarm, and it leaves
# the swarm and removes the stack, its volumes and the registry.
#
# Env: DATACRAFT_REHEARSAL_TIMEOUT (seconds the initializer, and then the other services, may take
# after each deployment; default 420, so that a stack that never settles fails long before the job
# is cancelled, which would leave no diagnosis).
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

[[ "${CI:-}" == true ]] || fail "Run only on an isolated CI runner."
env_file="$PWD/deploy/compose/.env"
[[ ! -e "$env_file" && ! -L "$env_file" ]] || fail "Preserving existing .env; refusing the rehearsal."
[[ "$(docker info --format '{{.Swarm.LocalNodeState}}')" == inactive ]] ||
  fail "This Docker engine is already part of a swarm; refusing the rehearsal."
for image in airflow jvm; do
  docker image inspect "datacraft/$image:latest" >/dev/null 2>&1 ||
    fail "datacraft/$image:latest is not built; run cicd/images/smoke-images.sh first."
done

# swarm-deploy.sh and airflow-init.sh name the stack themselves.
stack=datacraft
registry=127.0.0.1:5000
registry_container="datacraft-rehearsal-registry-${GITHUB_RUN_ID:-$$}"
first="rehearsal-${GITHUB_RUN_ID:-$$}"
second="$first-upgrade"
timeout="${DATACRAFT_REHEARSAL_TIMEOUT:-420}"
[[ "$timeout" =~ ^[0-9]+$ ]] || fail "DATACRAFT_REHEARSAL_TIMEOUT must be whole seconds."
# airflow-init.sh waits this long for the one-shot service.
export DATACRAFT_INIT_TIMEOUT="$timeout"
# The services that keep running; airflow-init completes instead.
services=(postgres redis airflow-apiserver airflow-scheduler airflow-dag-processor airflow-triggerer
  airflow-worker datacraft-api)
dags=(datacraft_engine_jobs datacraft_spark_etl datacraft_sftp_ingest)
joined=false
created_env=false
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$joined" == true ]]; then
    if ((status != 0)); then
      # Each part becomes an annotation of the failed step (cicd/step.py keeps the last eight), so
      # the parts that most often explain a failure come last.
      local service
      for service in airflow-triggerer datacraft-api redis airflow-apiserver airflow-dag-processor \
        airflow-scheduler airflow-worker airflow-init; do
        diagnose "Swarm: log of $service" docker service logs --raw --tail 30 "${stack}_$service"
      done
      diagnose "Swarm: tasks" docker stack ps "$stack" --no-trunc \
        --format '{{.Name}}\t{{.DesiredState}}\t{{.CurrentState}}\t{{.Error}}'
      diagnose "Swarm: services" docker stack services "$stack" --format '{{.Name}}\t{{.Replicas}}\t{{.Image}}'
    fi
    # Quiet: the removal of each service would otherwise be the last lines of a failed step.
    docker stack rm "$stack" >/dev/null 2>&1 || true
    local attempt
    for ((attempt = 0; attempt < 30; attempt++)); do
      [[ -n "$(docker ps --quiet --filter "label=com.docker.stack.namespace=$stack")" ]] || break
      sleep 2
    done
    docker swarm leave --force >/dev/null 2>&1 || {
      echo "This Docker engine could not leave the swarm." >&2
      status=1
    }
    # The stack's named volumes; containers that are still stopping keep theirs.
    docker volume ls --quiet --filter "label=com.docker.stack.namespace=$stack" |
      xargs --no-run-if-empty docker volume rm >/dev/null 2>&1 || true
  fi
  docker rm --force --volumes "$registry_container" >/dev/null 2>&1 || true
  [[ "$created_env" != true ]] || rm -f -- "$env_file"
  exit "$status"
}
trap cleanup EXIT

publish() { # <tag>: both runtime images in the registry under that tag
  local image
  for image in airflow jvm; do
    docker tag "datacraft/$image:latest" "$registry/datacraft-$image:$1"
    docker push --quiet "$registry/datacraft-$image:$1" >/dev/null
  done
}

configure() { # <KEY=value>...: set those lines of .env, as an operator edits them (no | & or \)
  local setting
  for setting in "$@"; do
    sed -i "s|^${setting%%=*}=.*|$setting|" "$env_file"
    grep -qxF -- "$setting" "$env_file" || fail ".env has no ${setting%%=*} line to set."
  done
}

specified_image() { # <service>: the image of its current specification
  docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${stack}_$1"
}

unsettled() { # prints the long-running services that do not run only tasks of their current specification
  local service image tasks task settled
  for service in "${services[@]}"; do
    image=$(specified_image "$service" 2>/dev/null) || image=""
    tasks=$(docker service ps --no-trunc --filter desired-state=running \
      --format '{{.Image}}|{{.CurrentState}}' "${stack}_$service" 2>/dev/null) || tasks=""
    settled=true
    [[ -n "$image" && -n "$tasks" ]] || settled=false
    # A task with a health check is Running only once it is healthy.
    while IFS= read -r task; do
      [[ "$task" == "$image|Running "* ]] || settled=false
    done <<< "$tasks"
    [[ "$settled" == true ]] || printf '%s ' "$service"
  done
}

settle() { # <seconds> <what happened>: wait until every long-running service runs its current tasks
  local deadline=$((SECONDS + $1)) pending
  while pending=$(unsettled) && [[ -n "$pending" ]]; do
    ((SECONDS < deadline)) ||
      fail "$2: after $1 s these services do not run the tasks of their current specification: $pending"
    sleep 5
  done
}

container() { # <service>: the container of one running task
  local found
  mapfile -t found < <(docker ps --quiet --filter "label=com.docker.swarm.service.name=${stack}_$1" \
    --filter status=running)
  # Standard error and a status, not fail: the caller captures what this prints.
  if [[ -z "${found[0]:-}" ]]; then
    echo "No running container of ${stack}_$1." >&2
    return 1
  fi
  echo "${found[0]}"
}

airflow_cli() { # <arguments>: the Airflow CLI in the scheduler's container
  local scheduler
  scheduler=$(container airflow-scheduler) || return 1
  docker exec "$scheduler" airflow "$@"
}

registered() { # true once the DAG processor has serialized every DAG of the image
  local dag
  for dag in "${dags[@]}"; do
    airflow_cli dags details --output json "$dag" >/dev/null 2>&1 || return 1
  done
}

run_dag() { # <run id>: trigger datacraft_engine_jobs and wait until that run has succeeded
  local attempt state
  airflow_cli dags trigger --run-id "$1" datacraft_engine_jobs
  for ((attempt = 0; attempt < 60; attempt++)); do
    state=$(airflow_cli dags state datacraft_engine_jobs "$1")
    if grep -qx success <<< "$state"; then
      return 0
    fi
    if grep -qx failed <<< "$state"; then
      fail "Run $1 of datacraft_engine_jobs failed."
    fi
    sleep 5
  done
  fail "Run $1 of datacraft_engine_jobs did not finish within 300 s."
}

docker swarm init --advertise-addr 127.0.0.1 >/dev/null
joined=true
node=$(docker node inspect self --format '{{.Description.Hostname}}')

# Loopback addresses are the registries Docker pulls from without TLS.
docker run --detach --name "$registry_container" --publish "$registry:5000" registry:3 >/dev/null
answered=false
for ((attempt = 0; attempt < 30; attempt++)); do
  if curl --fail --silent --output /dev/null "http://$registry/v2/"; then
    answered=true
    break
  fi
  sleep 1
done
[[ "$answered" == true ]] || fail "The registry on $registry does not answer."
publish "$first"

python3 -B deploy/scripts/deployment_env.py init
created_env=true
configure "DATACRAFT_REGISTRY=$registry" "DATACRAFT_TAG=$first" "DATACRAFT_DATA_NODE=$node"

bash deploy/scripts/swarm-deploy.sh
bash deploy/scripts/airflow-init.sh
settle "$timeout" "First deployment"

# The engine API has no published port: it answers on the overlay network, and its token only.
[[ "$(docker service inspect --format '{{len .Endpoint.Ports}}' "${stack}_datacraft-api")" == 0 ]] ||
  fail "The stack publishes a port of datacraft-api."
docker run --rm --network "${stack}_datacraft-net" --env-file "$env_file" \
  --volume "$PWD/cicd/images/check_access.py:/check_access.py:ro" --entrypoint python \
  datacraft/airflow:latest -B /check_access.py --api http://datacraft-api:8080 ||
  fail "datacraft-api on the overlay network does not answer its token, or answers without it."
# The Airflow UI is published through the routing mesh and signs the generated admin in.
web_port=$(docker service inspect --format '{{(index .Endpoint.Ports 0).PublishedPort}}' "${stack}_airflow-apiserver")
python3 -B cicd/images/check_access.py --env-file "$env_file" --airflow "http://127.0.0.1:$web_port" ||
  fail "The Airflow UI on the routing mesh does not sign the generated admin in, or signs anyone in."

# Airflow tasks write the shared data volume; the non-root engine API reads it and cannot write it.
worker=$(container airflow-worker)
api=$(container datacraft-api)
docker exec "$worker" python -c \
  'import pathlib; pathlib.Path("/opt/datacraft/data/.swarm-rehearsal").write_text("probe")'
# shellcheck disable=SC2016
docker exec "$api" sh -c 'test "$(id -u)" != 0 &&
test "$(cat /opt/datacraft/data/.swarm-rehearsal)" = probe &&
! touch /opt/datacraft/data/.api-write 2>/dev/null' ||
  fail "datacraft-api must run non-root, read what Airflow wrote and not write the data volume."

# Redis refuses a client that has not authenticated and answers the password of .env, which is the
# one the scheduler and the worker present in their broker URL.
redis=$(container redis)
unauthenticated=$(docker exec "$redis" redis-cli ping 2>&1) || true
[[ "$unauthenticated" == *NOAUTH* ]] ||
  fail "Redis answers a client that has not presented the password: $unauthenticated"
authenticated=$(REDISCLI_AUTH=$(sed -n 's/^REDIS_PASSWORD=//p' "$env_file") \
  docker exec --env REDISCLI_AUTH "$redis" redis-cli ping 2>&1) || true
[[ "$authenticated" == PONG ]] || fail "Redis does not answer the REDIS_PASSWORD of .env."
# What crosses from one node to another on the overlay network would be encrypted.
[[ "$(docker network inspect --format '{{index .Options "encrypted"}}' "${stack}_datacraft-net")" == true ]] ||
  fail "The overlay network ${stack}_datacraft-net was not created encrypted."

grep -qx CeleryExecutor <<< "$(airflow_cli config get-value core executor)" ||
  fail "The scheduler of the Swarm stack does not use CeleryExecutor."
ready=false
for ((attempt = 0; attempt < 36; attempt++)); do
  if registered; then
    ready=true
    break
  fi
  sleep 5
done
[[ "$ready" == true ]] || fail "Not every DAG was registered: ${dags[*]}."
airflow_cli dags unpause datacraft_engine_jobs
run_dag swarm-rehearsal

# The documented upgrade: the same images under a second tag. Every Airflow task is replaced, so
# the idle worker has to stop on the first signal; it would otherwise hold the update for the
# grace period of a running task, which is an hour.
publish "$second"
configure "DATACRAFT_TAG=$second"
started=$SECONDS
bash deploy/scripts/swarm-deploy.sh
bash deploy/scripts/airflow-init.sh
settle "$timeout" "Upgrade"
upgrade_seconds=$((SECONDS - started))
for service in airflow-init "${services[@]:2}"; do
  [[ "$(specified_image "$service")" == "$registry/datacraft-"*":$second"* ]] ||
    fail "${stack}_$service was not moved to the tag $second."
done
grep -qx success <<< "$(airflow_cli dags state datacraft_engine_jobs swarm-rehearsal)" ||
  fail "The run from before the upgrade is not in the metadata database any more."
run_dag swarm-rehearsal-upgraded

notice "Swarm stack rehearsed on one node" "swarm-deploy.sh and airflow-init.sh deployed the stack from" \
  "a registry: ${#services[@]} services run, the Airflow ones healthy; datacraft-api answers on the" \
  "overlay network only and its token only; the routing mesh signs the generated admin in; Redis" \
  "refuses a client without its password; the overlay network was created encrypted;" \
  "datacraft_engine_jobs ran through Redis and a Celery worker. An upgrade to a second tag ran the" \
  "initializer again, replaced every Airflow task within $upgrade_seconds s, kept the earlier run" \
  "and ran the DAG again. Not covered: more than one node, so no traffic between nodes."
