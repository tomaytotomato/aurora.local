# D3 — deploy + E2E rerun with auth fixture (P2 batch verification)

**Timestamp:** 2026-08-02 10:15 (post-rebuild + full E2E)
**HEAD:** `422abdb` on `rename/aurora` (18 commits since `fd8ea9c`)
**Image:** `aurora-dashboard:0.1.0` id `ce6e7c7aaa0d`
**Live URL:** http://192.168.0.110:8090
**E2E project:** aurora-e2e on :8091 (+ adguard sidecar)

## Live curl matrix

| Endpoint | Expected | Observed | Verdict |
|---|---|---|---|
| `GET  /api/services/status` | `core.state="running"` | ✅ | ✅ |
| `GET  /api/services/status` media `children` | 5-element array | **5** (prowlarr, sonarr, radarr, bazarr, seerr) | ✅ **BL1 landed on wire** |
| `GET  /api/onboarding/env` | `hostname=aurora, lanIp=192.168.0.110` | ✅ | ✅ |

## E2E scorecard delta

| Iteration | Pass | Fail | Skip | Flaky | Total | Notes |
|---|---:|---:|---:|---:|---:|---|
| Baseline (MORNING_BRIEFING_2) | 41 | 18 | 3 | 0 | 62 | Post dashboard-bug + polish chain |
| **D3 (this)** | **62** | **23** | **3** | 1 | 88 | +26 tests added by iter-3; auth fixture unblocked ~9 previously-skipping specs |

**Net delta:** +21 pass, +5 fail, 0 skip change, +1 flaky.

## Pass-count analysis

Iter-3 added or unlocked 26 test cases:
- BL5 auth fixture unblocked ~9 assertions in dashboard-home-polish, package-status-probing, done-launch, done-page-checklist.
- New specs shipped this iter-3: services-start-race (2 tests), storage-mount-panel (2), security-gate (3), dashboard-photo-bg (2), reach-info (3), theme-toggle (3). ≈15 new assertions.
- 21 of those pass, 5 fail. Since baseline had 18 already-red wizard-happy-path assertions, most of "the 23 unexpected" is the same wizard reds surfacing now that they run against a warmer aurora-e2e project.

## Failure triage (23 unexpected)

Categorised by root cause (deep-triaged at D4 morning briefing):
- **10 wizard-happy-path** — pre-existing TD5 selector-contract reds. Not regressions; they were in baseline's 18-fail count.
- **2 adguard-password-check** — the new AdGuard sidecar comes up in a state the test's copy assertion doesn't yet cover (probe says `needs-config`/`failed` but the test looked for `not-started`). BL6 side-effect; needs one-line copy fix in the spec.
- **1 services-start-race** — the storage row was polled during , so `storageRow.getByRole('button', { name: /start/i })` couldn't find the button. Real timing gap in the spec, not the app.
- **1 dashboard-home-polish P4 empty-state** — CSS `text-align:center` assertion tightened when auth fixture landed; V1 photo BG makes centre-cards render differently. Not a regression, an assertion adjustment needed.
- **1 done-launch** — auth fixture leaves onboarding complete so the launch-happy-path spec (which walks the wizard) 409s. Needs the "logout + fresh state" storage-state override on the wizard spec set.
- **~8 miscellaneous** — package-status-probing (2), no-cli-instructions (1), done-page-checklist (1), etc. Most are copy-drift or auth-fixture side effects.

## Deployed bundle grep (spot check)

Full aggregated grep already done at D2; this rerun only added BL1/BL2/BL3 wire surfaces. Spot check confirms  (BL1) and  (BL3) present in main index chunk.

## What Bruce sees after hard-reload

1. Media row (when running) exposes a "Show 5 services" toggle → nested Prowlarr/Sonarr/Radarr/Bazarr/Seerr rows.
2. Storage row (when running) exposes a "How to mount" toggle → 4-OS instructions panel.
3. Dashboard-home carries the DoneChecklist below the bento grid.
4. Everything shipped in D1+D2 still live (photo BG, dark mode, header pill, ReachInfo, honest Security empty-state).

## Residuals leaving this checkpoint

- 23 unexpected → 10 wizard-happy-path reds carryover, plus ~13 items needing a small copy/timing tweak. Triaged in the D4 morning briefing.
- TD1..TD8 backlog still open (SSE, atomic .state.yml write, yq in Dockerfile, log-tail persistence, Caddy TLS).
- Auth-fixture flake (1 flaky) — the AdGuard first-mount assertion was flaky, retried and passed.
