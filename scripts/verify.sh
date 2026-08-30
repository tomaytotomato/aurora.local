#!/usr/bin/env bash
# aurora.local / scripts/verify.sh
#
# The gate the improvement loop runs before every commit.
#   ./scripts/verify.sh            # everything
#   ./scripts/verify.sh front      # vue-tsc + vitest only
#   ./scripts/verify.sh back       # backend tests only
#   ./scripts/verify.sh shell      # shellcheck only
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WHAT="${1:-all}"
rc=0

run() { echo; echo "==> $1"; shift; "$@" || { echo "FAIL: $*"; rc=1; }; }

if [[ "$WHAT" == all || "$WHAT" == front ]]; then
  run "vue-tsc --noEmit" bash -c "cd '$REPO/packages/dashboard/frontend' && npx vue-tsc --noEmit"
  run "vitest"           bash -c "cd '$REPO/packages/dashboard/frontend' && npm run test:unit -- --run >/tmp/verify-vitest.log 2>&1; tail -4 /tmp/verify-vitest.log; grep -q 'Test Files.*failed' /tmp/verify-vitest.log && exit 1 || exit 0"
fi

if [[ "$WHAT" == all || "$WHAT" == back ]]; then
  run "backend tests" bash -c "docker run --rm -v '$REPO/packages/dashboard':/app -v maven-cache:/root/.m2 -w /app/backend maven:3.9-eclipse-temurin-25-alpine mvn -q -B test >/tmp/verify-backend.log 2>&1; tail -4 /tmp/verify-backend.log; grep -qE '^\[ERROR\] Tests run' /tmp/verify-backend.log && exit 1 || exit 0"
fi

if [[ "$WHAT" == all || "$WHAT" == shell ]]; then
  run "shellcheck" bash -c "cd '$REPO' && shopt -s nullglob && shellcheck -x -S style -e SC1091 bootstrap.sh scripts/*.sh scripts/lib/*.sh scripts/tests/*.sh"
  run "shell unit tests" bash -c "'$REPO'/scripts/tests/net.test.sh"
fi

echo
[[ $rc -eq 0 ]] && echo "ALL GREEN" || echo "SOMETHING FAILED"
exit $rc
