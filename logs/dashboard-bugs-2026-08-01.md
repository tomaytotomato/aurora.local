# Dashboard bugs + polish brief — 2026-08-01 evening

Handed off from Bruce at ~23:12 local. Bruce is asleep; chain runs overnight.
Live aurora on `:8090` post-commit `9d5fd8e` (compose-in-container path fix).
Onboarding is `complete=true`; Bruce is authenticated as `bruce` and is on
`/dashboard/home` (or similar authenticated landing). Caddy is up on 80/443.

## Verbatim from Bruce's message

Header on dashboard reads: `be1523c08f0f.undefined idle Back to Homepage · bruce`
Overview card: `be1523c08f0f. · vCPU · Docker`
System card: `uptime NaNh`
Memory row: `NaN KB / NaN KB (NaN%)`
Disk row: `NaN KB / NaN KB (NaN%)`
Containers card: `Recent events — No events yet — waiting on Docker stream.`
Packages card: `5 enabled — running 0`
Metrics card: `no data — Request failed with status code 404`
Clicking any package's **Start** button: `HTTP 409`

## Live evidence captured just now (unauthenticated curl)

- `POST /api/onboarding/launch` → `409 {"detail":"onboarding already complete; use authenticated endpoints"}`
  → `guardMidOnboarding` blocks it. Dashboard-home checklist **must not** hit
  `/api/onboarding/launch`. It needs an authenticated post-onboarding sibling.
- `GET /api/onboarding/status` → `{"bootstrap_mode":false,"complete":true,"step":"done"}` ✅
- `GET /api/services/status` → healthy JSON. `core` is `running` (Caddy up). Four packages
  (`media/sonarr`, `notes/notes`, `privacy/adguard`, `storage/samba`) are `not-started`.
- `GET /api/metrics/*` (all variants tried) → **401** anonymous. Frontend gets 404 with
  cookie — so the endpoint the frontend hits doesn't exist in the backend router.
- `GET /api/system/info` → empty response body anonymous (probably 401 in disguise).
- `GET /api/system/status` → empty response body anonymous.

## Diagnoses (grounded, but chain must verify)

### Bug 1 — `be1523c08f0f.undefined` in header + Overview
- `be1523c08f0f` is the aurora container's **docker hostname** (12-hex short id).
  On the host, `hostname` is `aurora`. Somewhere the backend or frontend is
  reading `/etc/hostname` from inside the container instead of `.state.yml`
  → domain (`aurora.local`), or reading `os.hostname()` from Java's JVM.
- `.undefined` is JS reading a missing field, likely `${info.hostname}.${info.domain}`
  where `info.domain` is undefined because the response uses `domain_name` or
  the field lives on a different endpoint.
- Correct source of truth: `.state.yml` — `hostname: aurora`, `domain: aurora.local`.
  These should surface via `/api/system/info` (or similar) and the header should
  read `aurora.aurora.local`.
- File suspects: `SystemService.info()` (returns hostname), the Vue store /
  composable that fetches it, and the header component.

### Bug 2 — `NaN` uptime, memory, disk
- `NaN` in JS = arithmetic on `undefined` or `null`. Almost certainly the frontend
  is dividing (or `.toFixed`-ing) fields the backend either omits or names
  differently (e.g. backend `mem_total_kb`, frontend expects `memoryTotal`).
- `NaN KB / NaN KB (NaN%)` — the units string suggests the frontend already knows
  it's KB; the values themselves are `undefined`. Field-name drift.
- `MetricSampler` logs "aurora dashboard v0.1 ready — metric sampler online" so
  a sampler is running. Check its output vs the endpoint the frontend reads.
- Suspects: `SystemService.status()` or `MetricSampler.snapshot()` return shape;
  the corresponding Vue composable.

### Bug 3 — Metrics 404 "Request failed with status code 404"
- Frontend Axios call → 404. Anonymous curl hit 401 on 5 endpoint guesses; that
  means all five exist in security config but none 200 the frontend's actual URL.
  So the frontend is asking for something like `/api/metrics/history?window=24h`
  that has no route.
- Suspects: `MetricsController` (or absence thereof) + the dashboard-home Vue
  component that owns the "Metrics — last 24h" card.

### Bug 4 — `Start` button → HTTP 409
- Root cause is confirmed: dashboard-home is POSTing to `/api/onboarding/launch`
  which is guarded by `guardMidOnboarding` (returns 409 when `complete=true`).
- Fix path A: **new `POST /api/services/{package}/start`** (authenticated,
  post-onboarding), reuse `LaunchService` internals, keep `/api/onboarding/launch`
  guarded to onboarding phase only.
- Fix path B: relax `guardMidOnboarding` when the requested package is enabled
  but its containers are not-running.
- Preferred: **Path A**. The onboarding launch is a bulk "start everything in
  .state.yml enabled_packages"; the dashboard start button is a per-package
  action. Different lifecycle, different endpoint.

### Extra polish targets Bruce should not have to name

- "Back to Homepage" link → homepage was retired in v0.1 (see
  `packages/core/compose.yml` comment). This link is a copy-paste stale.
- "Health" label with no value, System card with no content, Security "Full
  posture scan lands with the security module" placeholder — all fine as
  "coming later" but they need visual polish so they don't read as broken.
- "Packages 5 enabled / running 0" — misleading: `core` is actually running.
  Probably counts `enabled and probe==running`; but `core=running` is in the
  probe response, so the count is buggy.
- Header hostname line jams `idle` and `Back to Homepage` and `· bruce`
  together with no visual separation. Needs proper anatomy.

## What the chain must NOT touch

- The onboarding flow (green, signed off, deployed 2h ago).
- The compose-in-container path fix (9d5fd8e).
- The BBQ-chain E2E infra (`packages/dashboard/e2e/*`, `aurora-e2e:8091`).
- Live aurora container on `:8090` — keep it healthy for morning walk-through.

## What "morning briefing" needs to include

- Delta table for each of the four bugs (before/after with evidence).
- Screenshot-equivalent copy of the new dashboard-home (text description).
- Ranked list of remaining polish items.
- A short list of what Bruce should click and verify.
