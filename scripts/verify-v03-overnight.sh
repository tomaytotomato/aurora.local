#!/usr/bin/env bash
# scripts/verify-v03-overnight.sh — Aurora feat/v0.2-overnight verification.
#
# Idempotent, monitor-rerunnable. Runs the three checks that constitute
# the completion gate for the overnight Ralph loop:
#   1. Commit count since baseline f9c4406.
#   2. Backend test suite (docker-run maven, no host JDK).
#   3. Frontend typecheck (docker-run vue-tsc, no host node).
#   4. Dockerfile static check (docker build --check).
#
# Exits 0 iff all four pass with the recorded expectations
# (257/257 backend, vue-tsc exit 0, no Dockerfile warnings).
#
# Working directory: repository root (or WORKTREE env var).
# Environment:
#   WORKTREE           default $PWD    (must be the feat/v0.2-overnight tree)
#   AURORA_BASELINE    default f9c4406 (branch-point on rename/aurora)
#   SKIP_BACKEND       default 0       (1 to skip mvn — cached expectation printed)
#   SKIP_FRONTEND      default 0       (1 to skip vue-tsc)
#   SKIP_DOCKER_CHECK  default 0       (1 to skip docker build --check)
#
# Safe by construction: never hits the live aurora on http://192.168.0.110:8090.
# Bruce owns the live rebuild + smoke test after merge.

set -euo pipefail

WORKTREE="${WORKTREE:-$PWD}"
BASELINE="${AURORA_BASELINE:-f9c4406}"
SKIP_BACKEND="${SKIP_BACKEND:-0}"
SKIP_FRONTEND="${SKIP_FRONTEND:-0}"
SKIP_DOCKER_CHECK="${SKIP_DOCKER_CHECK:-0}"

cd "$WORKTREE"

# ─────────────────────────── output helpers ───────────────────────────────
FAIL_COUNT=0
PASS_COUNT=0
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; PASS_COUNT=$((PASS_COUNT+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL_COUNT=$((FAIL_COUNT+1)); }
info() { printf '  \033[2m· %s\033[0m\n' "$*"; }

# ─────────────────────────── 1. git commits ───────────────────────────────
step "Commits since baseline ($BASELINE)"
if git rev-parse --verify "$BASELINE" >/dev/null 2>&1; then
  COMMIT_COUNT=$(git rev-list --count "$BASELINE..HEAD")
  BRANCH=$(git rev-parse --abbrev-ref HEAD)
  HEAD_SHORT=$(git rev-parse --short HEAD)
  HEAD_SUBJECT=$(git log -1 --pretty=%s)
  if [ "$COMMIT_COUNT" -gt 0 ]; then
    ok "$COMMIT_COUNT commits on $BRANCH since $BASELINE"
    info "HEAD: $HEAD_SHORT — $HEAD_SUBJECT"
  else
    bad "no commits since $BASELINE"
  fi
else
  bad "baseline $BASELINE not reachable from this worktree"
fi

# ─────────────────────────── 2. backend tests ─────────────────────────────
BACKEND=packages/dashboard/backend
if [ "$SKIP_BACKEND" = "1" ]; then
  step "Backend tests (skipped — set SKIP_BACKEND=0 to run)"
  info "cached expectation: 257 tests, 0 failures, 0 errors (as of iter-17 commit 4d1a5cb)"
elif [ ! -d "$BACKEND" ]; then
  step "Backend tests"; bad "$BACKEND not found"
else
  step "Backend tests (docker-run maven, no host JDK)"
  LOG=/tmp/verify-v03-mvn.log
  if docker run --rm \
      -v "$WORKTREE/$BACKEND":/app \
      -v "$HOME/.m2":/root/.m2 \
      -w /app \
      maven:3.9-eclipse-temurin-25-alpine \
      mvn -B -Dstyle.color=never test >"$LOG" 2>&1; then
    SUMMARY=$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$LOG" | tail -1 | sed 's/^\[INFO\] //' || echo "")
    ok "mvn test green — ${SUMMARY:-(exit 0, summary parse missed)}"
    # Belt-and-braces: expected floor is 257.
    RUN=$(printf '%s' "$SUMMARY" | sed -nE 's/^Tests run: ([0-9]+),.*/\1/p')
    if [ -n "$RUN" ] && [ "$RUN" -lt 257 ]; then
      bad "test count $RUN below iter-17 baseline 257 — check for silently-removed tests"
    fi
  else
    bad "mvn test failed — tail $LOG"
    tail -30 "$LOG" | sed 's/^/    /'
  fi
fi

# ─────────────────────────── 3. frontend typecheck ────────────────────────
FRONTEND=packages/dashboard/frontend
if [ "$SKIP_FRONTEND" = "1" ]; then
  step "Frontend typecheck (skipped — set SKIP_FRONTEND=0 to run)"
  info "cached expectation: vue-tsc --noEmit exit 0 (as of iter-17)"
elif [ ! -d "$FRONTEND" ]; then
  step "Frontend typecheck"; bad "$FRONTEND not found"
else
  step "Frontend typecheck (docker-run vue-tsc, no host node)"
  LOG=/tmp/verify-v03-vue-tsc.log
  if docker run --rm \
      -v "$WORKTREE/$FRONTEND":/app -w /app \
      node:22-alpine \
      sh -c "npx vue-tsc --noEmit" >"$LOG" 2>&1; then
    ok "vue-tsc --noEmit exit 0"
  else
    bad "vue-tsc failed — tail $LOG"
    tail -30 "$LOG" | sed 's/^/    /'
  fi
fi

# ─────────────────────────── 4. Dockerfile check ──────────────────────────
if [ "$SKIP_DOCKER_CHECK" = "1" ]; then
  step "Dockerfile static check (skipped — set SKIP_DOCKER_CHECK=0 to run)"
  info "cached expectation: 'Check complete, no warnings found.' (as of iter-6 commit b9b0085)"
elif [ ! -f "$BACKEND/../Dockerfile" ]; then
  step "Dockerfile static check"; bad "packages/dashboard/Dockerfile not found"
else
  step "Dockerfile static check (docker build --check)"
  LOG=/tmp/verify-v03-docker-check.log
  if docker build --check -f packages/dashboard/Dockerfile packages/dashboard/ >"$LOG" 2>&1; then
    if grep -q 'Check complete, no warnings found' "$LOG"; then
      ok "docker build --check: no warnings"
    else
      bad "docker build --check exited 0 but expected copy missing — tail $LOG"
      tail -20 "$LOG" | sed 's/^/    /'
    fi
  else
    bad "docker build --check failed — tail $LOG"
    tail -30 "$LOG" | sed 's/^/    /'
  fi
fi

# ─────────────────────────── summary ──────────────────────────────────────
echo
if [ "$FAIL_COUNT" -eq 0 ]; then
  printf '\033[32m✓ verify-v03-overnight.sh: %d checks passed, 0 failed\033[0m\n' "$PASS_COUNT"
  exit 0
else
  printf '\033[31m✗ verify-v03-overnight.sh: %d passed, %d failed\033[0m\n' "$PASS_COUNT" "$FAIL_COUNT"
  exit 1
fi
