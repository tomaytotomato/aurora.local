# Aurora UX — Iteration 1 plan

**Branch:** `rename/aurora`
**Baseline:** `packages/dashboard/e2e/results/baseline.md` — 34 pass / 16 fail / 10 skip
**Author:** product-manager (Sarah persona, no-CLI-ever principle)

---

## 1 · P0 target for iteration 1

**Kill the SSH-run-up.sh cliff on `/onboarding/done`.**

The current Done page ships this, verbatim:

> **Action required on the host**
> These packages are enabled but not running yet. SSH into the box and run:
> ```
> cd ~/aurora.local && ./scripts/up.sh
> ```

That is the single defect that makes Sarah delete Aurora. She has never SSHed
anywhere. She just clicked through nine wizard steps, hit "Install", and Aurora
now hands her a black terminal box and tells her to open a shell. Every other
UX complaint in the baseline is friction or polish. This one is
**"return the box to the shop"**.

Baseline failure that owns this cliff:

- `no-cli-instructions.spec.ts › /onboarding/done: does not mention ./scripts/up.sh`
  (not currently in the failure list — it slips past only because the test
  compares against a literal string; the visible `<pre>` with
  `cd ~/aurora.local && ./scripts/up.sh` is a **P0 regression** the moment
  the test runs against the built artefact. Fix the UX, verify the test goes
  green.)
- The `no-cli-instructions.spec.ts › /onboarding/tls` failures (visible `sudo`
  copy) are the same disease at a different step — track them, **but do not
  fix in iter-1**. Scope stays surgical.

Everything else — the 14 wizard-happy-path failures, the checklist grid on
Done, AdGuard probing, media probing, SMB probing — waits for iter-2 and
iter-3. Do not batch them with the P0. Do not touch them.

---

## 2 · Required implementation

### 2a · Backend — new "launch" surface

Aurora runs `scripts/up.sh` **itself**. The user never touches a shell.

- **`POST /api/onboarding/launch`**
  - Reads `.state.yml` `enabled_packages` (same source `OnboardingService.install()`
    already uses — no new source of truth).
  - Spawns `scripts/up.sh <pkg1> <pkg2> ...` as a background process. Working
    directory = `AURORA_REPO_PATH` (`/repo` in the container).
  - Returns `202 Accepted` with `{ "job_id": "<uuid>", "packages": [...], "started_at": "<iso>" }`.
  - Rejects with `409 Conflict` if a launch job is already active
    (`state == RUNNING`). One job at a time. No queueing in iter-1.
  - Guards via `OnboardingService.guardMidOnboarding()` — same guard the
    existing `/install` endpoint uses. This mirrors the current
    `/api/onboarding/**` permitAll posture (public **only** during
    bootstrap-mode & !complete). See §2c for the risk write-up.

- **`GET /api/onboarding/launch/{id}/stream`** (SSE, `text/event-stream`)
  - Emits three event types:
    - `event: log` — `{ "stream": "stdout"|"stderr", "line": "..." }` — one per
      output line from up.sh, streamed as they arrive.
    - `event: package` — `{ "package": "media", "state": "starting"|"healthy"|"unhealthy", "container": "sonarr" }`
      — one per state transition. Emit `starting` when up.sh's compose-up
      hits that service; emit `healthy`/`unhealthy` from a docker-health
      poll on the compose project `aurora` (2-second interval, 120-second
      cap per package).
    - `event: done` — `{ "state": "success"|"failed", "exit_code": 0, "failed_package": "media"?, "duration_ms": 12345 }`
      — final event; server closes the stream.
  - Heartbeat: `event: ping` every 15s while running so proxies don't kill
    the connection. **Never** allow more than 3s of silence between events
    while a compose stage is active — UX principle 5 (Progress is tangible).

- **`GET /api/onboarding/launch/{id}`** (final status, JSON)
  - `{ "id": "...", "state": "queued"|"running"|"success"|"failed", "started_at": "...", "finished_at": "...", "exit_code": 0, "packages": [{ "name":"media", "state":"healthy", "container":"sonarr" }, ...], "tail": ["last 200 log lines"] }`
  - Cheap to hit on page refresh — the Done page rehydrates from this if the
    user reloaded while a launch was mid-flight.

Storage of job state: **in-memory** (`ConcurrentHashMap<String, LaunchJob>`).
Aurora is single-instance; a restart nukes the job — which is fine because
the containers up.sh started keep running. On next Done-page load the
frontend detects "no active job + all packages healthy" and shows the
happy-path checklist.

### 2b · Frontend — rewrite the top half of `OnboardingDone.vue`

Replace lines 33–49 (the `v-if="toStart.length > 0"` SSH banner) with a
single primary CTA:

```
[  Start services  ]     ← big primary button, size="lg"
Aurora will bring these up for you: [core] [privacy] [media] ...
```

On click:

1. `POST /api/onboarding/launch` → capture `job_id`.
2. Replace the button with a `LaunchProgress` panel (new component,
   `components/onboarding/LaunchProgress.vue`):
   - **Header row**: `⟳ Bringing up N packages…` (or `✓ All N packages running` on success).
   - **Per-package rows**, one per enabled package, each with:
     - Package name + one-line description.
     - Status pill: `not-started` (grey) → `starting` (spinner) → `running`
       (green tick) → `failed` (red + Retry button).
     - Container name (e.g. `sonarr`) in mono, muted.
   - **Live log** in a collapsible `<details>` (closed by default, opens
     automatically on failure). Monospace, auto-scroll, tail-buffered at
     500 lines client-side.
3. Subscribe to `EventSource('/api/onboarding/launch/{id}/stream')`, dispatch:
   - `log` → append to log buffer.
   - `package` → mutate the matching row's status pill.
   - `done` → close SSE, flip header row, either enable "Take me to Aurora"
     (was previously hidden while launch was pending) or reveal the retry
     surface.
4. **Failure UX** — when `done.state === "failed"`:
   - Header flips to `✗ media failed to start`.
   - Log panel auto-opens.
   - A `[ Retry media ]` button appears (POSTs `/launch` again with the
     failed package only, per iter-1 minimum; a smarter partial retry can
     wait). Alongside it, `[ Show details ]` scrolls to the log tail.
   - **No stack traces.** The one-line reason is derived from the last
     `stderr` line of the failing service (fall back to
     `"Container ${name} exited before becoming healthy"`).

**Do not remove** the four `Card` tiles below (AdGuard first-run, Sonarr/Radarr/Seerr, SMB mount, Aurora home). Those are the follow-ups the checklist grid will replace in iter-2 — leaving them as-is keeps scope tight.

**Do not remove** the "Take me to Aurora" button at the bottom, but **disable
it while the launch is running** — Sarah shouldn't be shipped to a dashboard
where half her services are still spinning up. Enable it as soon as the
launch reports `success` (or immediately if there was nothing to start).

**Also remove**: lines 55–61 — the "Deselected packages have containers
still running: run `./scripts/down.sh <pkg>` on the host to stop them" Alert.
That is the same SSH-copy defect and it will fail `no-cli-instructions.spec.ts`
on the `done` route as soon as we point it at the built SPA. Cut it. iter-2
owns the "auto-stop deselected" story; for iter-1 just delete the copy — a
silent leftover container is friction, not a cliff.

### 2c · Security

`/api/onboarding/**` is **permitAll** in `SecurityConfig.java:44` **only**
because `OnboardingController.guardMidOnboarding()` re-asserts
`isBootstrapMode() || !isComplete()` inside each mutating handler. `/launch`
must do the same:

- Enter `guardMidOnboarding()` at the top of the handler — 409 if
  onboarding is already committed. This means a post-onboarding attacker
  cannot POST `/launch` to shell-inject via the packages list.
- Package list comes from `.state.yml`, not from the request body. **Do not
  accept a packages array from the client.** Any RCE surface via
  `up.sh <attacker-controlled>` is closed by construction.
- Job IDs are UUIDs. `/stream/{id}` and `/{id}` verify the id exists and,
  if onboarding has since been marked complete, additionally require an
  authenticated admin session (so a mid-launch onboarding-complete transition
  doesn't strand the running job's UI).
- Add an `audit.record(..., "onboarding.launch", ...)` entry at start and end,
  same shape as the existing `onboarding.install` audit line.

If the worker feels `/api/onboarding/launch` is wrong from a routing
standpoint, `/api/launch/**` is acceptable **iff** the SecurityConfig
matcher is extended to include it with the same bootstrap-mode guard.
Do not add a new permitAll rule that skips the guard.

---

## 3 · Explicit non-goals for iteration 1

- **No redesign of the checklist grid** below the launch button. iter-2 owns it.
- **No AdGuard probing.** The AdGuard first-run card stays as-is.
- **No media stack probing.** Sonarr/Radarr/Seerr card stays as-is.
- **No SMB probing.** Storage card stays as-is.
- **No changes to any wizard step other than `/onboarding/done`.** The 14
  wizard-happy-path failures are all iter-2/iter-3 territory. Do not touch
  Welcome/Admin/Domain/Packages/Secrets/DNS/TLS/Review.
- **No auto-stop for deselected packages.** iter-2 owns down.sh in-app. For
  iter-1 the down.sh Alert copy just gets deleted (see §2b).
- **No partial-service retry beyond "retry the whole failed package".** iter-3
  can add per-service granularity.

---

## 4 · Definition of done

1. `no-cli-instructions.spec.ts` — every test tagged `/onboarding/done` is
   green:
   - `does not mention ./scripts/up.sh` — passes.
   - `no <pre> or shell-text <code>` — passes.
   - `no SSH/terminal/CLI in visible copy` — passes.
   - `no shell-command patterns in visible copy` — passes.
2. `wizard-happy-path.spec.ts` — the Done-page portion of the happy path:
   test drives the wizard to `/onboarding/done`, clicks the primary
   "Start services" button, and asserts:
   - `POST /api/onboarding/launch` returns 202 with a `job_id`.
   - SSE stream emits at least one `package` event.
   - At least one package row transitions to state `running` (green tick)
     within the test timeout.
   *(The 14 upstream happy-path failures may still be red after iter-1 —
   this criterion is scoped to the Done click-through only. Worker adds a
   focused Playwright test `done-launch.spec.ts` that mocks the failing
   upstream steps via API state seeding rather than driving the whole
   wizard, if the upstream red flags block reach.)*
3. Live aurora container on `:8090` remains healthy through the change.
   Verify with `curl -sf http://localhost:8090/api/health` after the worker's
   rebuild.
4. All backend unit tests green: `mvn -pl packages/dashboard/backend test`.
5. No new `<pre>` or `<code>` element containing shell text is added
   anywhere in the frontend bundle. Grep the built `dist/` for
   `./scripts/` and `sudo ` — must return zero hits.
6. `docker exec aurora which bash docker` returns paths for **both** in the
   rebuilt image (see §6 risk 1). If either is missing, the endpoint cannot
   launch anything and iter-1 has silently failed.

---

## 5 · Files the worker will touch

**Backend (Java, Spring Boot 4):**

- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/controllers/OnboardingController.java`
  — add the three new endpoints. Prefer this to a new controller so the
  bootstrap-mode guard behaviour stays co-located.
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/LaunchService.java`
  — **new**. Owns the `ConcurrentHashMap<String, LaunchJob>`, the
  `ProcessBuilder` for `up.sh`, the `SseEmitter` per job, and the
  docker-health poll loop (reuse the existing docker-java client the
  `PackagesService` already uses — do **not** shell out for health checks;
  keep the shell-out surface to `up.sh` alone).
- Optional: `LaunchJob.java` (record) alongside `LaunchService` if the
  worker prefers to keep state modelling in a small type.
- `packages/dashboard/backend/src/test/java/.../LaunchServiceTest.java`
  — **new**. Unit test the state machine (queued → running → success/failed),
  the "one job at a time" rejection, and the guardMidOnboarding gate.

**Frontend (Vue 3.5 + TS):**

- `packages/dashboard/frontend/src/views/onboarding/OnboardingDone.vue`
  — replace the SSH banner block, add the CTA and progress panel, disable
  the bottom "Take me to Aurora" while running, delete the down.sh Alert.
- `packages/dashboard/frontend/src/components/onboarding/LaunchProgress.vue`
  — **new**. Per-package rows + collapsible log. Emits `success` /
  `failed` upward so `OnboardingDone.vue` can toggle the footer CTA.
- `packages/dashboard/frontend/src/api/onboarding.ts`
  — add `postLaunch()`, `getLaunchStatus(id)`, and a small `openLaunchStream(id)`
  helper wrapping `EventSource`.
- `packages/dashboard/frontend/src/api/events.ts`
  — **optional**: if the existing SSE helper is generic enough, extend it;
  otherwise leave it and inline the EventSource in `LaunchProgress.vue`.

**Infra:**

- `packages/dashboard/Dockerfile` — Stage 3 currently ships
  `eclipse-temurin:25-jre-alpine`, which has **neither `bash` nor the docker
  CLI**. Add:
  ```
  RUN apk add --no-cache bash docker-cli docker-cli-compose
  ```
  before the `USER` switch. Without this, `POST /launch` will 500 with
  `ProcessBuilder: bash: not found` and iter-1 ships broken. **This is a
  blocker check the worker must do before writing any Java.**

**Do not touch** — flagged so the worker doesn't drift scope:

- Any file under `packages/dashboard/frontend/src/views/onboarding/` other
  than `OnboardingDone.vue`.
- `scripts/up.sh` itself. If up.sh needs a `--json` progress mode to make
  per-service transitions cleaner, that is an iter-3 refactor. For iter-1
  parse its existing `log_step` output.
- `SecurityConfig.java` — no matcher changes needed; `/api/onboarding/**` is
  already permitAll under bootstrap mode.

---

## 6 · Risks + mitigations

**Risk 1 — Runtime image is missing bash + docker CLI.** *(blocker)*

`packages/dashboard/Dockerfile` Stage 3 is `eclipse-temurin:25-jre-alpine`.
Alpine base, no bash, no docker binaries. `scripts/up.sh` starts with
`#!/usr/bin/env bash` and calls `docker compose ...` heavily. Without an
`apk add bash docker-cli docker-cli-compose`, the launch endpoint 500s
immediately. **Worker's first commit in iter-1 must be the Dockerfile
change**, then rebuild the aurora image, then confirm
`docker exec aurora bash --version` and `docker exec aurora docker compose version`
both work before writing a single line of Java.

**Risk 2 — Docker socket group membership.** `compose.yml:24` runs aurora as
`${AURORA_UID:-1000}:${DOCKER_GID:-999}` — the DOCKER_GID env is set by
`scripts/up.sh` at aurora's own launch time. Inside the container, the
process's supplementary group must include the docker gid or `docker.sock`
returns EACCES. Worker verifies with `docker exec aurora id` and
`docker exec aurora docker ps` after the Dockerfile rebuild. If broken,
the fix is in compose.yml (`group_add: [docker]`) not in the Dockerfile.

**Risk 3 — up.sh writes to `/repo`.** The bind mount is rw
(`compose.yml:41`) so this works, but up.sh's `render_all` writes to
`packages/*/rendered/`. Aurora runs as UID 1000; those files were probably
created by the invoking user on the host. If the host user is not UID
1000, up.sh will fail with permission-denied on the first `cp` in
`render_all`. Mitigation: worker's post-rebuild smoke test is
`docker exec -u 1000 aurora bash -c 'cd /repo && ./scripts/up.sh core'`.
If it fails at render, that's iter-1 blocker #2 — fix by chowning `/repo`
in the entrypoint or by having up.sh tolerate pre-existing files (patch
scoped narrowly).

**Risk 4 — SSE + Spring Boot 4 behind Caddy.** Caddy defaults to buffering.
The dashboard Caddy snippet at `packages/dashboard/caddy.snippet` may need
a `flush_interval -1` for the SSE route. Worker checks; low-effort fix. In
dev (Vite proxy) SSE works out of the box.

**Risk 5 — "Retry" hitting a still-running compose project.** Second POST to
`/launch` while the first job's containers are half-up is a mess. Iter-1
mitigation: reject with 409 while `state == RUNNING`, and *on Retry after
failure* let `docker compose up -d` be idempotent (it is — up on
already-healthy services is a no-op). Do not add a `docker compose down`
step; that would kill healthy services from other packages.

**Risk 6 — Long up.sh runtime blows the SSE window.** First-ever `docker
compose pull` on media can take 3–5 minutes on a fresh box. The `ping`
heartbeat every 15s keeps the connection alive. But the frontend must not
show a spinner with no line movement for 5 minutes — surface the
`log` events (which will include `pulling sonarr...` etc.) live in the
collapsible panel and pop open the log after 30s of no `package` event
progress. Iter-1 acceptable: any live output within 3s of clicking Start.
Fail this and we've violated UX principle 5.

---

## 7 · One-line handoff to the worker

> Read `logs/ux-iteration-1.md`. Start with the Dockerfile blocker (§6
> risk 1) before writing any Java. Ship the Java + Vue on top. Do not
> touch anything outside §5. Run the E2E suite against the rebuilt
> aurora-e2e project. Definition of done is §4. Commit as
> `aurora: UX iteration 1 implement (launch endpoint + Done page)`.
