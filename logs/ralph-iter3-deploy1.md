# D1 — deploy checkpoint after P0 batch (B1 + B2 + B3 + B4 + TD7)

**Timestamp:** 2026-08-02 09:39 (post-rebuild)
**HEAD:** `b090f91` on `rename/aurora` (5 commits since `fd8ea9c`)
**Image:** `aurora-dashboard:0.1.0` id `669673f29032`
**Container created:** 2026-08-02T08:38:57.174228447Z
**Live URL:** http://192.168.0.110:8090

## Curl matrix — unauthenticated smoke

| Endpoint | Expected | Observed | Verdict |
|---|---|---|---|
| `GET  /api/system` | 401 | **401** | ✅ auth guard holding |
| `GET  /api/onboarding/status` | 200 `{complete:true,bootstrap_mode:false,step:"done"}` | ✅ exact match | ✅ |
| `GET  /api/services/status` | 200 with `core.state="running"` | ✅ `core running` present | ✅ **B1 landed on wire** |
| `POST /api/services/media/start` (anon) | 401 | **401** | ✅ auth guard holding |
| `POST /api/onboarding/launch` (anon) | 409 | **409** + RFC-7807 body | ✅ wizard-guard holding |

## Deployed bundle grep (DashboardHome chunk `DashboardHome-D0LY-ztL.js`)

| Expected present | Found | Notes |
|---|---:|---|
| `Resources` | 1 | iter-2 polish |
| `p-8` | 1 | ✅ **B3 landed** |
| `gap-6` | 1 | ✅ **B3 grid loosening** |
| `space-y-4` | 1 | ✅ **B3 body rhythm** |
| `Metrics land next release` | 1 | iter-2 polish |

| Expected absent | Found | Notes |
|---|---:|---|
| `aurora.aurora.local` | **0** across every chunk | ✅ **B2 landed** |
| `Review checks` | 0 | iter-2 polish holding |
| `NaN` | 0 | earlier chain holding |
| `Request failed` | 0 | earlier chain holding |
| `be1523c08f0f` (container-hostname leak) | 0 | earlier chain holding |

## Identity-helper wire trace (per-chunk)

- `aurora.local` — present as expected in `index-DZQnvXvQ.js` (identity fallback), `OnboardingDomain`, `OnboardingDns`.
- `startsWith` — 8 occurrences in main index chunk, matching `renderIdentity()` + other lib helpers.

## Backend + frontend build health

- Backend Maven test suite: **88/88 green** (up from 45/45 pre-iter-3 — TD7 fix unlocked previously broken suites).
- Frontend `vue-tsc --noEmit`: **clean** (was blocked by ghost `.status` field usages in PackageDetail/PackagesList; also migrated).
- Full Vite bundle: emitted successfully by multi-stage Dockerfile; new chunk hash `DashboardHome-D0LY-ztL.js` replaces yesterday's `DashboardHome-DF15QgoP.js`.

## Media stack state note

Media containers were up 1 minute this morning (Bruce's Start click succeeded). They have since gone down (compose ran outside this loop). Not a regression from D1 — the P0 batch didn't touch media compose. The Start button now correctly polls up to 180 s so a fresh click will bring the row through `Starting…` → `Running` without the flip Bruce saw this morning.

## What Bruce sees on refresh (bookmark: http://192.168.0.110:8090)

1. Header identity reads **`aurora.local`** (not `aurora.aurora.local`).
2. Card padding is roomy — 32 px on all four sides.
3. Packages card count reads **`5 enabled · 1 running`** (was falsely `0 running`).
4. Clicking Start on media flips row to `Starting…` and stays there until compose finishes or 180 s elapses; no premature `Couldn't start`.

## Residuals leaving this checkpoint

- D2 (P1 polish deploy) still ahead — photo BG, dark mode, header health pill.
- Auth fixture (BL5) still needed for the 9-ish self-skipping E2E assertions to actually execute.
- Media stack is currently off — Bruce can Start it from the dashboard and use it as the live B4 verification.
