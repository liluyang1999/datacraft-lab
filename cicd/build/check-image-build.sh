#!/usr/bin/env bash
# The builder image (deploy/docker/Dockerfile.build) compiles the CLI jar from the Docker build
# context, which .dockerignore strips of tests/, docs/, design/ and cicd/. This runs the Dockerfile's
# own Maven command in a copy of that context, so a build that only works with the full checkout
# fails here, without Docker, instead of minutes into the image build. Run after `./mvnw verify`: it
# builds offline from the local Maven repository, with this checkout's wrapper (the image has its
# own Maven, so the wrapper need not be in the build context). PYTHON selects the interpreter.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

python=$(python_command) || fail "no working Python interpreter found (set PYTHON)"
context=$(scratch_dir image-build-context)
trap 'rm -rf -- "$context"' EXIT

"$python" -B cicd/build/docker_context.py copy "$context"
arguments=$("$python" -B cicd/build/docker_context.py maven-args deploy/docker/Dockerfile.build) || {
  echo "$arguments" # the script's own ::error:: line
  exit 1
}
mapfile -t args < <(tr -d '\r' <<<"$arguments")
echo "in the build context: mvn ${args[*]}"
(cd "$context" && "$REPO_ROOT/mvnw" -o "${args[@]}")
jar=modules/interfaces/datacraft-cli/target/datacraft-cli.jar
[ -f "$context/$jar" ] || fail "the build in the Docker build context produced no $jar"
echo "the builder image's Maven command works in the Docker build context"
