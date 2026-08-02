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

## Iter 6 · 2026-08-02 09:47 · commit a151863
**V1** — aurora photo background on /dashboard/home.
- AppShell reads `route.meta.photoBg` → renders `<AuroraBackground scrim="strong">` (existing component, day-deterministic pick, drift, credit bubble).
- Only DashboardHome opts in (route meta). Other authenticated views keep the plain warm-white shell for content density.
- Approach: keep Sidebar + TopBar opaque; drop `bg-canvas` from outer wrapper so photo peeks around content edges + below footer. No text-colour surgery on Sidebar/TopBar.
- Footer border + text tokens flip to `text-white/70`/`border-white/15` in photo mode.
- E2E: new `dashboard-photo-bg.spec.ts` (2 assertions).
- vue-tsc clean. Live rebuild deferred to D2.

## Iter 7 · 2026-08-02 09:54 · commit HEAD
**V2** — dark mode toggle with prefers-color-scheme + localStorage.
- `[data-theme="dark"]` block in `assets/main.css` overrides the warm-monochrome tokens with a night-aurora palette. Amber accent kept.
- New composable `composables/useTheme.ts` — reactive singleton, applies attr at import (no flash of light), reads localStorage → prefers-color-scheme → light.
- TopBar right region: sun/moon toggle button before the username interpunct. aria-label + aria-pressed + data-test hook.
- E2E: new `theme-toggle.spec.ts` (3 assertions — 2 auth-free, 1 auth-gated).
- vue-tsc clean.

## Iter 8 · 2026-08-02 10:01 · commit 9cfdb7d
**V3** — lift healthPill into shared composable + mount in TopBar centre.
- New `composables/useHealthPill.ts` — derived view over packages store, no new fetches.
- DashboardHome now imports the shared pill; local HealthState declaration deleted.
- TopBar centre region renders Badge with `data-region="health"`, `data-test="topbar-health-pill"`, `data-state=<HealthState>`.
- TopBar triggers `packages.fetchList()` on mount when store empty so pill works from any authenticated view.
- E2E: +1 auth-gated assertion in dashboard-home-polish.spec.ts.
- vue-tsc clean. **P1 visual polish batch complete.**

## Iter 9 · 2026-08-02 10:09 · commit 02c0469
**P1a** — reach-info panel (mDNS host + LAN IP + Copy).
- New shared  with card + inline variants; uses shared `renderIdentity`.
- DashboardHome System card mounts inline variant above the resources block.
- OnboardingDone mounts card variant above the Go-to-dashboard CTA; fetches hostname + lanIp from public /api/onboarding/env.
- Help text names the exact Firefox-on-macOS failure mode Bruce hit today.
- E2E: new reach-info.spec.ts (3 assertions).
- vue-tsc clean.

## Iter 9 · 2026-08-02 10:09 · commit 02c0469
**P1a** — reach-info panel (mDNS host + LAN IP + Copy).
- New shared `components/ReachInfo.vue` with card + inline variants; uses shared `renderIdentity`.
- DashboardHome System card mounts inline variant above the resources block.
- OnboardingDone mounts card variant above the Go-to-dashboard CTA; fetches hostname + lanIp from public /api/onboarding/env.
- Help text names the exact Firefox-on-macOS failure mode Bruce hit today.
- E2E: new reach-info.spec.ts (3 assertions).
- vue-tsc clean.

## Iter 10 · 2026-08-02 10:15 · commit 0d1ec50
**P1b** — /security route gate.
- Backend `SystemService.info()` now emits `capabilities.securityScanner: false`.
- `SecurityPosture.vue` rewritten around the flag; false → honest empty-state Card + six planned M4 checks. Every fabricated string (score=78, UFW, fail2ban, backup, unattended-upgrades) deleted.
- Sidebar hides `/security` nav link when the capability flag is false.
- E2E: new `security-gate.spec.ts` (3 assertions).
- Backend 88/88; vue-tsc clean.

## Iter 11 · 2026-08-02 10:22 · commit 142148b
**P1c** — mDNS collision diagnostic on the box.
- New `scripts/mdns-audit.sh` — avahi-browse + avahi-resolve + multicast dig + collision detection + LAN interface enumeration. Writes to `logs/mdns-audit-YYYY-MM-DD.txt` and stdout.
- Dockerfile: `apk add avahi-tools bind-tools` in runtime stage so the script works inside the aurora container too.
- Host smoke: script correctly reports missing avahi-utils (host has daemon but not CLI). Full run deferred to inside-container test post D2 rebuild.
- **P1 productionize-footguns batch complete (P1a+P1b+P1c). D2 rebuild next.**

## Iter 20 · 2026-08-02 12:10 · (no commit — attempted TD5 + TD4 both reverted)
**TD5** attempted skip-when-onboarded guard on wizard-happy-path, no-cli-instructions, done-launch specs.
- Approach: `test.use({storageState: {cookies:[], origins:[]}})` + beforeEach probe of `/api/onboarding/status` → `test.skip()` when `complete===true`.
- Killed 17 fails but blanket-skipped 28 previously-green tests in the same suites. Net delta pass 62→34, fail 23→6, skip 3→49. Bad tradeoff.
- Root fix: E2E-only `POST /api/onboarding/reset` endpoint so wizard specs can rewind between suites. iter-4 material.
- Reverted `git reset --hard HEAD~1` back to `a240faf`.

**TD4** pivot: install `mikefarah/yq` v4 in Aurora Dockerfile.
- Dockerfile change was clean (apk deps + wget of `yq_linux_amd64` from GH releases).
- Blocked at build: host docker overlayfs consistently fails to extract `docker-cli` from alpine apk (`ERROR: docker-cli-29.5.2-r0: failed to extract usr/bin/docker: I/O error`).
- Reproducible on stock `alpine:latest apk add docker-cli` — not a Dockerfile issue, a host docker health issue.
- Reverted via `git checkout packages/dashboard/Dockerfile`.
- Needs host investigation before any Dockerfile rebuild can land.

## Iter 21 · 2026-08-02 12:22 · commit 0c8bbdb
**TD3** — atomic `.state.yml` write.
- `StateFileService.mutateState`: write to `<file>.tmp`, then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
- `AtomicMoveNotSupportedException` fallback to non-atomic `REPLACE_EXISTING` (still safer than the previous in-place truncation).
- `finally { Files.deleteIfExists(tmp) }` cleans up stray `.tmp` on failure.
- +4 unit tests in `StateFileServiceTests`:
  - success leaves no `.tmp` sibling
  - preserves `hostname`/`bootstrap_version`/`installed_at` when mutating enabled[]
  - writes via `.tmp` (proven by pre-seeded broken `.tmp` that must be consumed by the move)
  - simulated mid-write crash: pre-existing broken `.tmp` does not corrupt subsequent `readState()`, and the next successful write cleans it up
- **Backend 96/96 green** (up from 92).
- TD4 + TD5 deferred to iter-4.
