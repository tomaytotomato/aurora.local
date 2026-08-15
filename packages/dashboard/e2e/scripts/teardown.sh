#!/usr/bin/env bash
# packages/dashboard/e2e/scripts/teardown.sh
#
# Remove the aurora-e2e compose project + its volumes + scratch state.
# The live aurora container is untouched.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DASHBOARD_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

PROJECT=aurora-e2e
STATE_DIR=/tmp/aurora-e2e-state
REPO_SCRATCH="$STATE_DIR/repo"

log() { printf '\033[36m[e2e]\033[0m %s\n' "$*"; }

# Same reason as reset-aurora-e2e.sh: compose resolves its built-in .env
# loading against the real packages/dashboard/ (the first -f file's
# directory), never $REPO_SCRATCH, so without this `down` can't even
# parse compose.yml — required var AURORA_SESSION_SECRET has no value
# and the whole invocation errors before touching a single container.
if [[ -f "$REPO_SCRATCH/packages/dashboard/.env" ]]; then
  # shellcheck disable=SC1091
  set -a
  . "$REPO_SCRATCH/packages/dashboard/.env"
  set +a
fi

log "docker compose -p $PROJECT down -v --remove-orphans"
docker compose -p "$PROJECT" \
  -f "$DASHBOARD_DIR/compose.yml" \
  -f "$SCRIPT_DIR/compose.e2e.yml" \
  down -v --remove-orphans || true

log "removing $STATE_DIR"
rm -rf "$STATE_DIR"

log "done."
