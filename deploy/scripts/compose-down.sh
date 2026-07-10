#!/usr/bin/env bash
# Stops the single-host stack. Pass -v to also remove named volumes (DESTROYS the metadata DB).
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
cd "${REPO_ROOT}/deploy/compose"
log "Stopping datacraft-lab..."
docker compose down "$@"
