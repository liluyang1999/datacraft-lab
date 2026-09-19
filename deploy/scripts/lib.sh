#!/usr/bin/env bash
# Shared helpers for datacraft-lab deployment scripts.
set -euo pipefail

DEPLOY_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${DEPLOY_SCRIPTS_DIR}/../.." && pwd)"

log() { printf '\033[1;34m[datacraft]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[datacraft]\033[0m %s\n' "$*" >&2; }
die() {
  printf '\033[1;31m[datacraft]\033[0m %s\n' "$*" >&2
  exit 1
}

require_cmd() { command -v "$1" >/dev/null 2>&1; }

# Verifies Docker + Compose v2 are available, printing install guidance otherwise.
require_docker() {
  if ! require_cmd docker; then
    cat >&2 <<'EOF'
[datacraft] Docker is not installed or not on PATH. Install one of:
  - Docker Desktop (Windows/macOS): https://www.docker.com/products/docker-desktop
  - Docker Engine (Linux):          https://docs.docker.com/engine/install/
Then verify:  docker version && docker compose version
EOF
    exit 127
  fi
  if ! docker compose version >/dev/null 2>&1; then
    die "Docker Compose v2 plugin required (the 'docker compose' subcommand). Update Docker Desktop / install docker-compose-plugin."
  fi
}

# Never start with copied example passwords or silently rotate existing secrets.
ensure_env_file() {
  local env_file="${REPO_ROOT}/deploy/compose/.env"
  if [[ ! -f "${env_file}" ]]; then
    die "Run python3 deploy/scripts/deployment_env.py init to create private deployment secrets."
  fi
}

validate_compose_env() {
  require_cmd python3 || die "Python 3 is required to validate deployment secrets."
  docker compose -f "${REPO_ROOT}/deploy/compose/docker-compose.yml" config --format json |
    python3 -B "${DEPLOY_SCRIPTS_DIR}/deployment_env.py" check
}
