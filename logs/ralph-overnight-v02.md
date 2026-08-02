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
