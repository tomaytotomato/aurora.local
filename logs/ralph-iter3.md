# Ralph iter-3 per-commit evidence

## Iter 1 · 2026-08-02 09:14 · commit debfe1a
**B1 + TD7** — Core probe.kind:self marks running; test bean-override.
- `PackagesService.parseManifest`: honour `probe.kind: self` → `running=true`.
- `PackagesServiceTests`: +2 tests (self-probe positive, non-self negative).
- `application.yml` (test scope): `spring.main.allow-bean-definition-overriding: true`.
- Fake core manifest gains `probe: {kind: self, container: aurora}`.
- Backend: **88/88 green** (up from 45/45 as of MORNING_BRIEFING_2 — TD7 fix unlocked previously-broken suites).
- Live smoke deferred to D1 (batch rebuild after B2-B4).

## Iter 2 · 2026-08-02 09:22 · commit 67bd37d
**B2** — kill `aurora.aurora.local` dupe in header identity.
- New: `frontend/src/lib/identity.ts` exports `renderIdentity(hostname, domain)` with 4-rule spec.
- Rule 3 (the fix): if `domain.toLowerCase().startsWith(hostname.toLowerCase() + '.')` → render `domain`. Rules 1/2/4 unchanged.
- Applied at both call sites (`TopBar.vue`, `DashboardHome.vue`) via the shared helper.
- E2E: `dashboard-home-polish.spec.ts` +2 assertions (bundle-grep + API-driven).
- Frontend: `vue-tsc --noEmit` clean pass (typecheck-only, no rollup build needed).
- Live smoke deferred to D1.

## Iter 3 · 2026-08-02 09:29 · commit 140013f
**B3** — dashboard card padding p-6 → p-8, gap-4 → gap-6.
- Four `<Card>` instances on DashboardHome.vue get explicit `p-8` via class prop (twMerge overrides Card default `p-6`).
- Bento grid gap tightened → loosened (`gap-4` → `gap-6`).
- System hydrated body: `space-y-3` → `space-y-4` for row rhythm.
- E2E: dashboard-home-polish.spec.ts +1 assertion (computed padding ≥ 24 px per side).
- vue-tsc --noEmit: clean.
- Live smoke deferred to D1.
- Investigation: confirmed deployed live CSS emits `p-6` correctly; issue is quantity not correctness. Card component untouched (would affect wizard).

## Iter 4 · 2026-08-02 09:41 · commit b090f91
**B4** — Start poll-until-running + kill ghost `.status` field (dual fix).
- **Silent P0 uncovered:** frontend `PackageSummary.status` was a lie — wire emits `.running: boolean`. Every `.status === 'running'` filter was returning 0, which is why the count said "0 running" even after B1's core fix landed.
- Rewrote `PackageSummary` to match the wire; added `packageStatus()` helper + `startBudgetMs()`.
- Migrated all `.status` call sites (DashboardHome runningCount/healthState/enabledSorted; PackagesList Badge; PackageDetail Badge/vhosts/dependsOn).
- `onStart()` now polls every 2 s until `.running` flips or manifest budget elapses. Media `start_budget_seconds: 180`, privacy 60, default 30.
- Axios timeout bumped 15 s → 30 s for `POST /services/{name}/start` only.
- New E2E `services-start-race.spec.ts` (self-skips without auth fixture).
- Backend: **88/88 green.** vue-tsc: clean.
- **P0 batch complete — D1 rebuild next.**
