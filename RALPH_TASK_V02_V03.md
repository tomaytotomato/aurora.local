# Aurora v0.2 close-out + v0.3 groundwork — overnight Ralph task

**Working directory:** `/home/bruce/aurora-v02-wt` (isolated worktree)
**Branch:** `feat/v0.2-overnight` (baseline `f9c4406`)
**Sibling activity:** the `rename/aurora` branch is being edited by an async worker (`3707d3d3`) on login-flow polish. DO NOT `git checkout` anywhere else. Push commits to `origin/feat/v0.2-overnight` only.
**Deploy target:** live aurora runs from the `/home/bruce/aurora.local` checkout on `rename/aurora`. **DO NOT REBUILD / RESTART the live container from this worktree.** Only build+test locally here. Bruce will review + merge in the morning.

---

## Ground rules

- One item per commit. Commit prefix `aurora:` (matches repo convention).
- Every commit: `cd packages/dashboard && ./gradlew test` must stay green (or explain the failure in the log entry). Frontend: `cd packages/dashboard/frontend && npx vue-tsc --noEmit` clean.
- Append a dated entry to `logs/ralph-overnight-v02.md` after every item — commit SHA, files touched, tests green/red, deferred sub-items.
- Push after every commit: `git push origin feat/v0.2-overnight`.
- If backend tests break and you can't fix within the same iteration, revert the commit and mark the item as deferred with a reason in the log.
- If an item hits an ambiguity where the right answer requires product judgement, DO NOT guess — write a `DECISION_NEEDED.md` entry describing the fork and move on to the next item.
- Read `docs/DASHBOARD_BRIEF.md`, `docs/PACKAGE_CONTRACT.md`, `docs/UX_SPEC_DASHBOARD.md`, and `docs/ONBOARDING_V0.2.md` **before writing code** the first time each area is touched.
- Do not edit files under `.state.yml`, `packages/*/.env`, or `~/.aurora/` — those are live-machine state.

---

## Phase A — v0.2 close-out (do these in order, they're ranked by pain)

### A1. DoneChecklist / PackagesCard drift on `notes`

Bruce reported the dashboard shows **Notes → Running** in the Packages card but **Notes → Not started yet** in the DoneChecklist below. Same `/api/services/status` wire, two verdicts. Also the System card shows `Containers 1` while `docker ps` has 3 (aurora + silverbullet + caddy).

- Read `components/onboarding/DoneChecklist.vue`, `components/dashboard/PackagesCard.vue`, `stores/services.ts` (or wherever the wire lands).
- Find the second predicate — likely `state === 'running'` on a legacy path when the wire actually emits `running: boolean` (this was B4's fix; a caller was missed).
- Fix the drift + add a unit or component test asserting `PackageSummary.running === true` implies checklist row renders as running.
- Container count in System card: audit `SystemService.system()` or the equivalent; if it counts only aurora-labelled containers, fix it to count total `docker ps` OR to count package containers only and rename the label to match. Whichever is honest.
- New E2E assertion in `services-start-race.spec.ts` or a new spec: mock `/api/services/status` with `{package: 'notes', running: true}` and assert the DoneChecklist row reads `Running`.

**Acceptance:** Both cards agree. Container count matches semantic label. Backend + vue-tsc clean.

### A2. Validate `enabled_packages` on `PATCH /api/onboarding` ingress

Reviewer P1 from BBQ chain. `enabled_packages` array is currently trusted; a malicious name like `../../etc/passwd` could break manifest lookup or path resolution.

- Read `OnboardingController.patch()` and `OnboardingService`.
- Introduce an allowlist derived from `packages/*/manifest.yml` filenames. Names must match `^[a-z][a-z0-9-]{1,31}$` AND exist as a directory under `packages/`.
- 400 with `{error: "unknown_package", name: "…"}` on violation.
- Unit test in `OnboardingServiceTests` for both regex-fail and unknown-package.

**Acceptance:** New tests green. Existing 99/99 backend suite stays green.

### A3. Scrub SSH copy from OnboardingAdmin.vue

Reviewer P1. Lines 120/141 (per scratchpad) reveal a copy-button that puts an SSH command on the clipboard — a footgun for a browser-first product and a small credential-echo risk.

- Read `views/onboarding/OnboardingAdmin.vue` around lines 120/141.
- Replace the SSH copy affordance with a plain-text status message that says how the admin is being created via the wizard. Preserve any recovery-copy that's genuinely useful (username, one-time password) with the existing copy pattern, but nothing that embeds an SSH command line.
- Update the existing E2E `onboarding-admin.spec.ts` if it asserts the SSH copy affordance; replace with the new expectation.

**Acceptance:** No SSH command string anywhere in OnboardingAdmin.vue. Existing wizard flow still passes.

### A4. TD5 — E2E-only wizard-reset endpoint

New guarded route `POST /api/onboarding/reset` gated on `System.getenv("AURORA_E2E")` == `"1"`. Wipes `.state.yml` + admin credentials to bootstrap state. Idempotent.

- New controller method in `OnboardingController` + `OnboardingService.reset()`.
- Return 404 (not 401) when env var is unset — hides the endpoint's existence in prod.
- Backend test (integration-style with a `@TestPropertySource` toggling the env).
- Add `beforeEach(async ({request}) => { await request.post('/api/onboarding/reset') })` to `wizard-happy-path.spec.ts`, `no-cli-instructions.spec.ts`, `done-launch.spec.ts` in `packages/dashboard/e2e/tests/`.
- Update `packages/dashboard/e2e/aurora-e2e/docker-compose.yml` (or wherever `AURORA_E2E=1` needs to be) so the aurora-e2e project runs with the env set.

**Acceptance:** Backend green. E2E baseline pass count grows (target ≥ 72, was 62 at iter-3 end). If the aurora-e2e infra isn't runnable from this worktree, log that as a deferred verification item — the code should still be complete + typecheck.

### A5. TD1 — SSE for `/api/services/status`

Drop the 5s poll cliff. New `GET /api/services/status/stream` using Spring's `SseEmitter` (same pattern as `EventsController`).

- Read `EventsController` for the exact pattern (heartbeat interval, error handling, backpressure).
- New endpoint emits `service-status` events every 2s (or on docker event trigger if the plumbing is cheap; heartbeat every 15s otherwise). Emit initial snapshot immediately on subscribe.
- Frontend: new composable `composables/useServiceStatusStream.ts` that opens EventSource, falls back to 5s poll if the server returns 501 or the connection errors 3× within 30s. Deprecate — do not remove — the poll path used by `PackagesCard` and `DoneChecklist`. Both consume the composable and get `computed refs`.
- Update or add E2E assertion that `EventSource` is opened on `/dashboard/home` mount.

**Acceptance:** Backend green with the new endpoint + a unit test using MockMvc's async support. Poll fallback path unchanged. vue-tsc clean.

### A6. Container-count honesty in System card

If A1 didn't already fix it: the header claims `Containers N` but the number doesn't match `docker ps`. Decide:
- If the count is "aurora-managed packages that are up", relabel to `Packages running` and match `packagesRunning`.
- If the count is "total containers on the host", surface the real number.

Fix the label OR fix the number. Pick one, log the choice. Frontend + backend test parity.

### A7. up.sh yq missing from Aurora Dockerfile

Scratchpad: `up.sh` from inside the aurora container fails on richer flows because `yq` isn't in the alpine image.

- Add `yq` (mikefarah v4) to `packages/dashboard/Dockerfile`. Use the pinned binary via `curl -L … > /usr/local/bin/yq && chmod +x`, not `apk add yq` (alpine's yq is Go's yq-python fork historically flaky).
- Verify inside the built image: `docker run --rm aurora-dashboard:local yq --version`.
- No live rebuild — Bruce will rebuild after merge.

### A8. Media Start-poll for multi-container stacks

Backend `LaunchService.classify()` returns "failed" before `docker compose up -d` finishes on the 7-container media stack because the poll window is package-agnostic.

- Add `start_budget_seconds` to `packages/media/manifest.yml` (180s already precedented in the media plan) and any other multi-container stacks.
- `LaunchService` reads `start_budget_seconds` from the manifest; caps at 600s absolute.
- Frontend `packageStatus()`/`startBudgetMs()` already reads this; verify wire.
- New E2E `services-start-media.spec.ts` (self-skipping if no docker) that walks Start → Running against real docker on `:8091` and asserts no false-negative.

---

## Phase B — v0.3 groundwork (only after Phase A commits are all in, or the current item is blocked)

### B1. Docker event stream → Containers card

Right now the Containers card is a fabricated integer. Wire real docker events using `docker-java`'s `EventsCmd`.

- New `DockerEventService` subscribes to `dockerClient.eventsCmd()` and maintains a rolling `ContainerEvent` in-memory buffer (size 200).
- Expose `GET /api/containers/events/stream` (SseEmitter) that pushes new events + flushes buffer on subscribe.
- Frontend: `RecentChangesList.vue` reads the stream, renders `container_name state at HH:MM`. Empty state stays honest ("Nothing has changed recently.").

### B2. Metrics sampler skeleton

Follow §M3 in DASHBOARD_BRIEF.md. Do NOT ship charts tonight — just the sampler + schema + storage.

- New `MetricsSamplerService` scheduled every 30s samples: CPU %, mem used/total, disk used/free per mount, per-container CPU + mem.
- Persist to SQLite table `metric_sample(id, ts, key, value)`. Ring-buffer prune older than 25h on write.
- `GET /api/metrics/last24h?key=…` returns downsampled 5-min buckets.
- Vue: leave the Metrics card empty-state as-is (still "Metrics land next release") — this task is backend-only groundwork. Log a follow-up for the uPlot chart wiring.

### B3. Container logs tail (last 200 lines)

- `GET /api/containers/{id}/logs?tail=200` returns plaintext via `docker-java` `logContainerCmd().withTail(200)`.
- Auth: admin session required.
- No live stream in v0.3; just the tail snapshot.
- Frontend: new route `/containers/{id}/logs` renders a `<pre>` with mono font, refresh button. Wire from `RecentChangesList` row click.

### B4. Security-rule model + first three rules

Follow §M4. Just the model + three rules. No UI yet.

- New Java record `SecurityFinding(id, severity, title, description, remediationUrl)`.
- New `SecurityRule` interface with three concrete rules:
  1. `WeakAdminPasswordRule` — checks stored password hash for known-weak inputs (length, common words).
  2. `DockerSocketExposureRule` — checks any container is mounting `/var/run/docker.sock` besides aurora itself.
  3. `UnpinnedImageTagsRule` — scans running containers' image refs; flags `:latest` or missing digest.
- `GET /api/security/findings` returns `List<SecurityFinding>`.
- Backend unit test per rule.
- Frontend: unchanged — still ships empty state until the rendering task lands.

---

## Stop conditions

- **Hard stop:** completed Phase A + landed B1 (docker events). Everything else is bonus.
- **Soft stop:** if Ralph is on iteration 25 and Phase A is only half done, spend remaining iterations depth-first on the incomplete Phase A items, not moving to Phase B.
- **Fail-safe:** if 3 consecutive iterations produce no green commit, halt and write a `HALT.md` explaining why.

## Deliverable in the morning

Bruce will find:
- `feat/v0.2-overnight` on origin, N commits ahead of `f9c4406`
- `logs/ralph-overnight-v02.md` with per-iteration entries + a top-of-file executive summary
- `DECISION_NEEDED.md` (if any) with product-judgement forks
- `HALT.md` (only if the fail-safe fired)
- Backend + frontend still green

The morning walk-through will be: read the summary at the top of `logs/ralph-overnight-v02.md`, `git log --oneline f9c4406..origin/feat/v0.2-overnight`, then rebase or merge into `rename/aurora`.

## Final verification command

Externally rerunnable from a fresh shell inside this worktree. Uses only docker-run tooling — no host JDK, no host node. Never touches the live aurora on `http://192.168.0.110:8090`.

```bash
cd /home/bruce/aurora-v02-wt
bash scripts/verify-v03-overnight.sh
```

Environment (all optional):
- `WORKTREE` (default `$PWD`) — override the worktree location.
- `AURORA_BASELINE` (default `f9c4406`) — branch-point on `rename/aurora`.
- `SKIP_BACKEND` / `SKIP_FRONTEND` / `SKIP_DOCKER_CHECK` (default 0) — opt-out per phase for fast reruns.

Expected output (as of iter-17, commit `4d1a5cb`; rerun any time on `HEAD`):

```
✓ Commits since baseline (f9c4406)
  33 commits on feat/v0.2-overnight since f9c4406
✓ Backend tests (docker-run maven, no host JDK)
  mvn test green — Tests run: 257, Failures: 0, Errors: 0, Skipped: 0
✓ Frontend typecheck (docker-run vue-tsc, no host node)
  vue-tsc --noEmit exit 0
✓ Dockerfile static check (docker build --check)
  docker build --check: no warnings
✓ verify-v03-overnight.sh: 4 checks passed, 0 failed
```

Artifacts preserved for a rerun in a fresh shell:
- `logs/ralph-overnight-v02.md` — executive summary + per-iter log.
- `packages/dashboard/backend/src/test/resources/fake-repo/.state.yml` — test fixture protected by a `.gitignore` exception (iter-17 fix).
- `scripts/verify-v03-overnight.sh` — the command itself.
- No caches or build directories required; the script re-creates everything it needs inside the docker-run steps.
