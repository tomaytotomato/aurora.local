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

log() { printf '\033[36m[e2e]\033[0m %s\n' "$*"; }

log "docker compose -p $PROJECT down -v --remove-orphans"
docker compose -p "$PROJECT" \
  -f "$DASHBOARD_DIR/compose.yml" \
  -f "$SCRIPT_DIR/compose.e2e.yml" \
  down -v --remove-orphans || true

log "removing $STATE_DIR"
rm -rf "$STATE_DIR"

log "done."
