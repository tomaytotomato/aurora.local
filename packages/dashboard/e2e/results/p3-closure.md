# P3 closure — error-recovery test 3 no longer self-skips

**Date:** 2026-08-01
**Baseline (iter-3 full):** 40 pass / 18 fail / 4 skip / 62 total
**After P3 closure:** 41 pass / 18 fail / 3 skip / 62 total
**Delta:** +1 pass, 0 new fail, −1 skip (exactly the unstuck self-skip)

## error-recovery.spec.ts

3 tests / 3 passed / 0 failed / 0 skipped (was 2 pass / 0 fail / 1 skip)

- ✅ install failure shows retry + plain-English reason (no stack trace)
- ✅ install log emits progress within 3s of clicking Install
- ✅ **done page: any failed package exposes a Retry action** — previously
  `test.skip('no failed packages on the fresh e2e box')`; now uses a
  spec-scoped `page.route` interceptor on `/api/services/status` to
  inject one `failed` service (`media` / container_crashed) plus one
  `running` (`privacy`). Also stubs `GET /api/onboarding` so the store
  has an `enabled_packages` list, and stubs `POST /api/onboarding/launch`
  to verify Retry click actually POSTs the launch endpoint.

## Full-suite failures (all pre-existing, unchanged)

Wizard-happy-path selector contracts (14), adguard-password-check needs a
live AdGuard sidecar (1), package-status-probing on dashboard-home (2),
review-step Install label (1). All owned by iter-4 backlog.

## No application code changed

Test hygiene only. The frontend Retry wiring (ChecklistItem `@retry` →
DoneChecklist `onRetry` → `OnboardingApi.startLaunch()`) was already in
place from iter-2; this closure is what proves it under E2E.
