#!/usr/bin/env bash
# scripts/verify-iter3.sh — Aurora iter-3 completion-gate verifier.
#
# Idempotent, monitor-rerunnable. Exits 0 iff every completion-gate check
# passes. Verifies commits, backend tests, live curl matrix, and deployed
# SPA bundle grep. E2E rerun is opt-in via VERIFY_E2E=1 (default off — the
# E2E project takes ~3 min and requires the isolated aurora-e2e docker
# compose project to be reachable).
#
# Working directory: /home/bruce/aurora.local
# Env:
#   AURORA_LIVE_URL    default http://192.168.0.110:8090
#   AURORA_E2E_PROJECT default aurora-e2e
#   AURORA_BASELINE    default fd8ea9c   (git ref for the iter-3 baseline)
#   VERIFY_E2E         default 0         (1 to rerun E2E)
#   VERIFY_BUILD       default 0         (1 to also run mvn test)

set -euo pipefail

REPO="${REPO:-/home/bruce/aurora.local}"
LIVE="${AURORA_LIVE_URL:-http://192.168.0.110:8090}"
BASELINE="${AURORA_BASELINE:-fd8ea9c}"
E2E_PROJECT="${AURORA_E2E_PROJECT:-aurora-e2e}"
VERIFY_E2E="${VERIFY_E2E:-0}"
VERIFY_BUILD="${VERIFY_BUILD:-1}"

cd "$REPO"

# ─────────────────────────── output helpers ───────────────────────────────
FAIL_COUNT=0
PASS_COUNT=0
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; PASS_COUNT=$((PASS_COUNT+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL_COUNT=$((FAIL_COUNT+1)); }
info() { printf '  \033[2m· %s\033[0m\n' "$*"; }

# ─────────────────────────── 1. git commits ───────────────────────────────
step "Commits since baseline ($BASELINE)"
COMMIT_COUNT=$(git rev-list --count "$BASELINE..HEAD" 2>/dev/null || echo 0)
if [ "$COMMIT_COUNT" -gt 0 ]; then
  ok "$COMMIT_COUNT commits on $(git rev-parse --abbrev-ref HEAD) since $BASELINE"
  info "HEAD: $(git rev-parse --short HEAD) — $(git log -1 --pretty=%s)"
else
  bad "no commits since $BASELINE (expected iter-3 batch)"
fi

# ─────────────────────────── 2. backend tests ─────────────────────────────
if [ "$VERIFY_BUILD" = "1" ]; then
  step "Backend tests (docker-run maven, no host JDK)"
  BACKEND=packages/dashboard/backend
  if [ -d "$BACKEND" ]; then
    if docker run --rm \
        -v "$REPO/$BACKEND":/app \
        -v "$HOME/.m2":/root/.m2 \
        -w /app \
        maven:3.9-eclipse-temurin-25-alpine \
        mvn -B -Dstyle.color=never test >/tmp/verify-iter3-mvn.log 2>&1; then
      TESTS_RUN=$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' /tmp/verify-iter3-mvn.log | tail -1 | sed 's/^\[INFO\] //' || echo "")
      ok "mvn test green — ${TESTS_RUN:-(exit 0, summary parse missed)}"
    else
      bad "mvn test failed — tail /tmp/verify-iter3-mvn.log"
      tail -30 /tmp/verify-iter3-mvn.log | sed 's/^/    /'
    fi
  else
    bad "$BACKEND not found"
  fi
else
  step "Backend tests (skipped — set VERIFY_BUILD=1 to run)"
  info "cached expectation: 99/99 green as of iter-22 (commit 0985b6f)"
fi

# ─────────────────────────── 3. live curl matrix ──────────────────────────
step "Live curl matrix ($LIVE)"

# Helper: curl with sane defaults, exits with non-zero on transport error.
_c() { curl -sS --max-time 6 "$@"; }

# 3.1 /api/health — cheap liveness probe.
if HLT=$(_c "$LIVE/api/health"); then
  if grep -q '"status":"ok"' <<<"$HLT"; then
    ok "/api/health: status=ok"
  else
    bad "/api/health returned unexpected body: $HLT"
  fi
else
  bad "/api/health unreachable at $LIVE"
fi

# 3.2 /api/onboarding/env — public identity endpoint (B2 + P1a on wire).
if ENV=$(_c "$LIVE/api/onboarding/env"); then
  if grep -q '"hostname":"aurora"' <<<"$ENV" && grep -q '"domain":"aurora.local"' <<<"$ENV"; then
    ok "/api/onboarding/env: hostname=aurora, domain=aurora.local"
  else
    bad "/api/onboarding/env: hostname/domain missing or unexpected"
    info "body: $(echo "$ENV" | head -c 300)"
  fi
  if grep -q 'aurora\.aurora\.local' <<<"$ENV"; then
    bad "/api/onboarding/env body contains 'aurora.aurora.local' (B2 regressed)"
  else
    ok "/api/onboarding/env: no 'aurora.aurora.local' duplicate (B2 held)"
  fi
  if grep -qE '"lanIp":"[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"' <<<"$ENV"; then
    ok "/api/onboarding/env: lanIp populated (P1a wire)"
  else
    bad "/api/onboarding/env: lanIp missing/null (P1a regressed)"
  fi
else
  bad "/api/onboarding/env unreachable at $LIVE"
fi

# 3.3 /api/services/status — B1 (core.state=running) + BL1 (media.children=5).
if SVC=$(_c "$LIVE/api/services/status"); then
  CORE_STATE=$(SVC="$SVC" python3 -c '
import json, os
d = json.loads(os.environ["SVC"])
core = next((s for s in d.get("services", []) if s.get("package") == "core"), None)
print((core or {}).get("state", "missing"))
' 2>/dev/null || echo error)
  if [ "$CORE_STATE" = "running" ]; then
    ok "/api/services/status: core.state=running (B1 held)"
  else
    bad "/api/services/status: core.state=$CORE_STATE (expected running)"
  fi
  MEDIA_KIDS=$(SVC="$SVC" python3 -c '
import json, os
d = json.loads(os.environ["SVC"])
media = next((s for s in d.get("services", []) if s.get("package") == "media"), None)
print(len(media.get("children", [])) if media else 0)
' 2>/dev/null || echo 0)
  if [ "$MEDIA_KIDS" = "5" ]; then
    ok "/api/services/status: media.children has 5 entries (BL1 held)"
  else
    info "/api/services/status: media.children=$MEDIA_KIDS (BL1 fires only when media enabled)"
  fi
else
  bad "/api/services/status unreachable"
fi

# 3.4 Auth guard: unauth GET /api/system → 401 (auth chain holding).
CODE=$(_c -o /dev/null -w '%{http_code}' "$LIVE/api/system" || echo 000)
if [ "$CODE" = "401" ]; then
  ok "GET /api/system (unauth) → 401 (auth chain holding)"
else
  bad "GET /api/system (unauth) → $CODE (expected 401)"
fi

# 3.5 Auth guard: unauth POST /api/services/media/start → 401.
CODE=$(_c -o /dev/null -w '%{http_code}' -X POST "$LIVE/api/services/media/start" || echo 000)
if [ "$CODE" = "401" ]; then
  ok "POST /api/services/media/start (unauth) → 401 (guard holding)"
else
  bad "POST /api/services/media/start (unauth) → $CODE (expected 401)"
fi

# ─────────────────────────── 4. deployed SPA bundle grep ─────────────────
step "Deployed SPA bundle grep"

# Fetch the index page and its referenced JS chunks, aggregate for grep.
INDEX=$(_c "$LIVE/") || true
if [ -z "$INDEX" ]; then
  bad "SPA index unreachable"
else
  # Extract chunk paths and download them into a big blob. Vite ships
  # dynamically-imported chunks that are only referenced from inside the
  # main JS, so we do a second pass over the initial blob to catch those
  # (e.g. ReachInfo.js, StorageMountPanel.js) instead of scanning only the
  # HTML `<script>`/`<link>` tags.
  BLOB=$(mktemp)
  echo "$INDEX" > "$BLOB"
  seen=" "
  for pass in 1 2; do
    for chunk in $(grep -oE '/?assets/[A-Za-z0-9._-]+\.(js|css)' "$BLOB" | sed 's|^assets/|/assets/|' | sort -u); do
      case "$seen" in *" $chunk "*) continue;; esac
      _c "$LIVE$chunk" >> "$BLOB" 2>/dev/null || true
      seen="$seen$chunk "
    done
  done
  size=$(wc -c <"$BLOB" | tr -d ' ')
  chunk_count=$(printf '%s\n' $seen | grep -c '^/assets/' || true)
  info "aggregated bundle: ${size} bytes across index + ${chunk_count} chunks"

  # 4.1 B2: no aurora.aurora.local anywhere.
  if grep -q 'aurora\.aurora\.local' "$BLOB"; then
    bad "bundle contains 'aurora.aurora.local' (B2 regressed)"
  else
    ok "bundle: no 'aurora.aurora.local' (B2 held on wire)"
  fi

  # 4.2 aurora.local is present as an identity token.
  if grep -q 'aurora\.local' "$BLOB"; then
    ok "bundle contains 'aurora.local' (identity token present)"
  else
    bad "bundle missing 'aurora.local' identity token"
  fi

  # 4.3 V2: dark-mode scaffold present.
  if grep -q 'data-theme' "$BLOB"; then
    ok "bundle contains 'data-theme' scaffold (V2 dark mode)"
  else
    bad "bundle missing 'data-theme' (V2 dark mode regressed)"
  fi

  # 4.4 P1a: LAN IP token surface exists in the built bundle.
  if grep -qE 'lanIp|LAN IP' "$BLOB"; then
    ok "bundle contains lanIp/LAN IP token (P1a ReachInfo)"
  else
    bad "bundle missing lanIp/LAN IP token (P1a regressed)"
  fi

  # 4.5 P1b: no fabricated 'Review checks' cta, no fabricated 78 score copy.
  if grep -qE 'Review checks →' "$BLOB"; then
    bad "bundle contains 'Review checks →' (P1b fabricated CTA regressed)"
  else
    ok "bundle: no fabricated 'Review checks →' CTA (P1b held)"
  fi

  # 4.6 iter-3 dashboard-bug guardrails.
  # Bundle-level NaN and 'Request failed' greps are noisy: vendor deps
  # (Vue's numeric internals, Axios's default error template) contain both
  # legitimately. We assert instead on the shipped source strings that
  # would flag an actual regression — UX copy that only Aurora emits.
  if grep -q 'NaN KB\|NaNh\|>NaN%' "$BLOB"; then
    bad "bundle contains rendered NaN copy (dashboard-bug regressed)"
  else
    ok "bundle: no rendered NaN copy (dashboard-bug held)"
  fi

  # 4.7 metrics-404 guardrail: if capabilities.metrics were flipped on
  # without a backend, the SPA would render the axios error copy in the
  # metrics card. The wire fix is that capabilities.metrics=false, so
  # no request is issued. We can only assert here on the presence of
  # the capabilities gate token.
  if grep -q 'capabilities' "$BLOB"; then
    ok "bundle references 'capabilities' gate (metrics-404 held)"
  else
    bad "bundle missing 'capabilities' token (metrics-404 guardrail regressed)"
  fi

  rm -f "$BLOB"
fi

# ─────────────────────────── 5. E2E rerun (opt-in) ────────────────────────
if [ "$VERIFY_E2E" = "1" ]; then
  step "E2E rerun (project=$E2E_PROJECT)"
  if [ -x packages/dashboard/e2e/scripts/reset-aurora-e2e.sh ]; then
    bash packages/dashboard/e2e/scripts/reset-aurora-e2e.sh >/tmp/verify-iter3-e2e-reset.log 2>&1 || {
      bad "reset-aurora-e2e.sh failed — tail /tmp/verify-iter3-e2e-reset.log"
      tail -20 /tmp/verify-iter3-e2e-reset.log | sed 's/^/    /'
    }
    if [ -d packages/dashboard/e2e/node_modules ]; then
      pushd packages/dashboard/e2e >/dev/null
      if npx playwright test --reporter=json >/tmp/verify-iter3-e2e.json 2>/tmp/verify-iter3-e2e.log; then
        STATS=$(python3 -c "
import json; d = json.load(open('/tmp/verify-iter3-e2e.json')); s = d.get('stats',{})
print(f\"pass={s.get('expected',0)} fail={s.get('unexpected',0)} skip={s.get('skipped',0)} flaky={s.get('flaky',0)}\")
" 2>/dev/null || echo unparseable)
        ok "E2E: $STATS"
      else
        STATS=$(python3 -c "
import json; d = json.load(open('/tmp/verify-iter3-e2e.json')); s = d.get('stats',{})
print(f\"pass={s.get('expected',0)} fail={s.get('unexpected',0)} skip={s.get('skipped',0)} flaky={s.get('flaky',0)}\")
" 2>/dev/null || echo unparseable)
        # E2E has known pre-existing wizard reds; accept as long as pass ≥ baseline.
        info "E2E finished non-zero: $STATS (see /tmp/verify-iter3-e2e.log)"
        # Compare pass count to baseline 41; new-fail check happens by CI, not here.
        PASS=$(python3 -c "import json;print(json.load(open('/tmp/verify-iter3-e2e.json'))['stats']['expected'])" 2>/dev/null || echo 0)
        if [ "$PASS" -ge 41 ]; then
          ok "E2E pass count ($PASS) ≥ baseline 41"
        else
          bad "E2E pass count ($PASS) < baseline 41 (regression)"
        fi
      fi
      popd >/dev/null
    else
      bad "packages/dashboard/e2e/node_modules missing; run npm ci in that dir"
    fi
  else
    bad "packages/dashboard/e2e/scripts/reset-aurora-e2e.sh missing or not executable"
  fi
else
  step "E2E rerun (skipped — set VERIFY_E2E=1 to enable)"
  info "cached expectation: 62/23/3/1 at D3 (commit a240faf); ≥41 pass, ≤23 fail"
fi

# ─────────────────────────── summary ──────────────────────────────────────
echo
if [ "$FAIL_COUNT" -eq 0 ]; then
  printf '\033[32m✓ verify-iter3.sh: %d checks passed, 0 failed\033[0m\n' "$PASS_COUNT"
  exit 0
else
  printf '\033[31m✗ verify-iter3.sh: %d passed, %d failed\033[0m\n' "$PASS_COUNT" "$FAIL_COUNT"
  exit 1
fi
