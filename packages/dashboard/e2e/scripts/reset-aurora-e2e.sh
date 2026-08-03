#!/usr/bin/env bash
# packages/dashboard/e2e/scripts/reset-aurora-e2e.sh
#
# Spin up an isolated aurora-e2e docker compose project on :8091 with a
# scratch repo copy so tests get a "fresh box" every run. Never touches
# the live aurora container on :8090.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DASHBOARD_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$DASHBOARD_DIR/../.." && pwd)"

PROJECT=aurora-e2e
STATE_DIR=/tmp/aurora-e2e-state
REPO_SCRATCH="$STATE_DIR/repo"
HOST_PORT=8091

log()  { printf '\033[36m[e2e]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[e2e]\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die "docker not on PATH"
docker compose version >/dev/null 2>&1 || die "docker compose v2 not available"

log "tearing down any previous $PROJECT project..."
docker compose -p "$PROJECT" \
  -f "$DASHBOARD_DIR/compose.yml" \
  -f "$SCRIPT_DIR/compose.e2e.yml" \
  down -v --remove-orphans >/dev/null 2>&1 || true

log "wiping $STATE_DIR..."
rm -rf "$STATE_DIR"
mkdir -p "$REPO_SCRATCH"

log "copying repo → $REPO_SCRATCH (excluding .git, node_modules, dashboard build output)..."
# rsync keeps this cheap and gives us a writable working copy.
rsync -a \
  --exclude='.git/' \
  --exclude='data/' \
  --exclude='node_modules/' \
  --exclude='packages/dashboard/frontend/dist/' \
  --exclude='packages/dashboard/backend/build/' \
  --exclude='packages/dashboard/backend/target/' \
  --exclude='packages/dashboard/e2e/node_modules/' \
  --exclude='packages/dashboard/e2e/test-results/' \
  --exclude='packages/dashboard/e2e/playwright-report/' \
  "$REPO_ROOT"/ "$REPO_SCRATCH"/

log "seeding fresh .state.yml..."
cp "$SCRIPT_DIR/../fixtures/fresh-state.yml" "$REPO_SCRATCH/.state.yml"

# Session secret needs to exist for the container to boot (compose ?: check).
mkdir -p "$REPO_SCRATCH/packages/dashboard"
if ! grep -q AURORA_SESSION_SECRET "$REPO_SCRATCH/packages/dashboard/.env" 2>/dev/null; then
  echo "AURORA_SESSION_SECRET=e2e-not-a-real-secret-do-not-ship" \
    >> "$REPO_SCRATCH/packages/dashboard/.env"
fi

log "starting isolated aurora on :$HOST_PORT (project=$PROJECT)..."
AURORA_UID="$(id -u)" \
AURORA_HOST_PORT="$HOST_PORT" \
AURORA_REPO_PATH_HOST="$REPO_SCRATCH" \
DOCKER_GID="$(getent group docker | cut -d: -f3 || echo 999)" \
docker compose -p "$PROJECT" \
  -f "$DASHBOARD_DIR/compose.yml" \
  -f "$SCRIPT_DIR/compose.e2e.yml" \
  up -d aurora adguard

log "waiting for http://localhost:$HOST_PORT/api/health ..."
for i in $(seq 1 60); do
  if curl -sf "http://localhost:$HOST_PORT/api/health" >/dev/null; then
    log "aurora-e2e healthy (took ${i}s)"
    exit 0
  fi
  sleep 1
done

log "aurora-e2e failed to come up within 60s. Container logs:"
docker logs aurora-e2e --tail=80 || true
die "health check failed"
