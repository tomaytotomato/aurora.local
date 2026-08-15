# Seam audit: shell layer <-> Java layer <-> container boundary

Scope: the handover points between `bootstrap.sh` / `scripts/*.sh` / `host/roles/*`
(running on the Debian host as a real user) and the Spring Boot app in
`packages/dashboard/backend` (running inside the `aurora` container, talking to the
host's Docker socket). Read-only investigation; nothing in the repo was changed.
The known `AURORA_LAUNCHED_BY` self-recreate bug is out of scope except where it
interacts with something else below.

## The shape of the problem

Almost every finding here is the same mistake wearing different clothes: **code on
one side of a process or container boundary assumes something about the other side
that isn't guaranteed** — a binary is present, a variable is set, a process will
exit, a file already exists, a namespace is shared. Individually cheap to fix; as a
pattern, worth naming so the next feature doesn't repeat it. Concretely:

- **No timeout is actually enforced anywhere Aurora shells out for a long time.**
  `CommandRunner.stream` (Launch, parity sync/scrub) has no timeout parameter at
  all; `CommandRunner.run`'s timeout (Updates registry checks) is silently
  ineffective against a hung/silent process. Both routes converge on the same
  failure mode: a stalled subprocess wedges a job forever with no way to cancel it
  from the UI.
- **Two different `.env` writers, uncoordinated, one wins by accident of timing.**
  `up.sh` seeds from `.env.example` only when the real file is absent; Java writes
  into the same files independently (domain, secrets). The two currently don't
  collide in the documented install path, but the ordering is implicit, not
  enforced, and breaks the moment the assumption "up.sh has already run for core"
  stops holding.
- **The container boundary is trusted more than it should be.** Two features
  (SnapRAID parity actions, WireGuard live status) shell out to a binary that only
  exists on the host side of the container boundary, with nothing bridging the
  gap. Both fail every time, silently degrading to an honest "unknown/down" rather
  than crashing — which is the right failure mode, but means the feature has
  presumably never worked and nothing would tell you that from the UI alone.
- **UID/GID handling is asymmetric.** `DOCKER_GID` is auto-detected in `up.sh`
  specifically because hard-coding it "differs per distro" (the script's own
  words); `AURORA_UID` gets no such treatment and ships as a literal `1000` in
  `.env.example`.

## Findings, severity-ordered

### 1. HIGH — Launch (and any Job) has no way to time out or be cancelled if `docker compose` hangs

**File:** `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/CommandRunner.java:68`
(the `stream` method signature) and `ProcessCommandRunner.java:91-103` (the
implementation).

**Trigger:** The wizard's "Launch" button runs `LaunchService.run()` ->
`commands.stream(repo, ..., ["bash", "up.sh", ...], onLine)`. `up.sh` runs
`docker compose ... pull` then `up -d`. Either step can stall indefinitely on a
real install — a slow or momentarily-dropped home broadband connection pulling
multi-GB images for `media`/`photos`/`ai`, or a `start_period` healthcheck that
never turns green. `CommandRunner.stream` takes no `Duration` at all (contrast
with `run`, which at least documents one). There is also no cancel/abort endpoint
anywhere in `LaunchService` or `OnboardingController` — confirmed by grep, nothing
matches `cancel`/`abort`/`destroy` in either file.

**Consequence:** The job sits in `RUNNING` forever. `LaunchService.activeJobId` is
a global single-in-flight lock (`startLaunch` throws `LaunchInProgressException`
while one job is active), so **no further launch can ever be started** — not a
retry, not a different package set — until an operator restarts the whole `aurora`
container from the host CLI. That is precisely the "SSH into the box" cliff
`LaunchService`'s own class-level Javadoc says iter-1 exists to remove. On a
physical-hardware install with real (imperfect) network conditions, this is a
plausible, not merely theoretical, way to get stuck mid-wizard with no UI recourse.

**Suggested fix:** Give `CommandRunner.stream` a hard ceiling (even a generous one,
e.g. 30 minutes) that `destroyForcibly()`s the process and marks the job `FAILED`
with an honest "timed out" reason; add a `POST /api/onboarding/launch/{id}/cancel`
that does the same on demand.

---

### 2. HIGH — `AURORA_UID` is a hard-coded `1000`, not auto-detected like `DOCKER_GID`

**File:** `packages/dashboard/.env.example:10` (`AURORA_UID=1000`),
`packages/dashboard/compose.yml:43` (`user: "${AURORA_UID:-1000}:${DOCKER_GID:-999}"`),
`scripts/up.sh:152-156` (the `DOCKER_GID` auto-detect block — there is no
equivalent for `AURORA_UID` anywhere in `up.sh` or `bootstrap.sh`; confirmed by
grep across every `.sh`/`.yml`/`.example` in the repo).

**Trigger:** `bootstrap.sh install` -> `up.sh` seeds `packages/dashboard/.env` from
`.env.example` (the generic "no `.env`, `.env.example` exists" branch at
`up.sh:119-125`), which plants the literal value `1000`. Unlike `DOCKER_GID`,
nothing in the shell layer replaces it with the invoking user's real uid
(`id -u`). The comment on the `DOCKER_GID` block explicitly names the reason this
matters ("rather than hard-coding a number that differs per distro") — the same
reasoning was never applied to `AURORA_UID`.

**Consequence:** The `aurora` container always runs as uid 1000 regardless of the
actual host user, unless that user genuinely has uid 1000 (true for the first
regular user on a fresh Debian install, not guaranteed otherwise — a reused box,
a second admin account, or an already-provisioned user will differ). When it
differs, the container cannot write into the host-owned, same-path bind-mounted
repo: every write this whole audit is about — `.state.yml`, `packages/*/.env`,
`data/caddy/snippets/*`, identity secrets — fails with a permission error the
operator has no way to self-diagnose from the wizard.

**Suggested fix:** Auto-detect in `up.sh` exactly like `DOCKER_GID` does:
`AURORA_UID="${AURORA_UID:-$(id -u)}"`, exported before the `.env` seed step, or
written directly into `packages/dashboard/.env` at seed time instead of the
static `.env.example` default.

**Action for today:** cheap to rule in/out before installing — run `id -u` on the
target box. If it isn't `1000`, this will bite on first wizard write.

---

### 3. HIGH (proven) — `CommandRunner.run`'s declared timeout does not fire against a hung, silent process

**File:** `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/ProcessCommandRunner.java:59-83`.

**Proven independently** (outside the repo, no code changed): the `run` loop reads
`proc.getInputStream()` to EOF *before* calling `proc.waitFor(timeout, ...)`.
`BufferedReader.readLine()` blocks until the process closes its stdout, which
normally only happens at exit. A minimal reproduction (`ProcessBuilder("bash",
"-c", "sleep 10")`, declared timeout 1000ms) measured the call returning after
**10024ms**, not ~1000ms — the timeout parameter had no effect until the process
exited on its own.

**Trigger:** `UpdatesService.localDigest`/`remoteDigest`
(`UpdatesService.java:184`, `:215`) call `commands.run(null, REGISTRY_TIMEOUT
(20s), ..., ["docker", "buildx", "imagetools", "inspect", ...])` once per image,
serially, for every package, on every "check for updates" job. The class-level
Javadoc explicitly promises this is "short enough not to wedge a check" — that
promise is false for a registry connection that hangs (stalls after the TCP
handshake, or a proxy/DNS blackhole) without producing output before erroring.

**Consequence:** `docker buildx imagetools inspect` against a slow or unreachable
registry can hang far past the documented 20s, and since the loop is serial over
every image of every package, a single stuck registry call can turn a "check for
updates" click into a job that never finishes — same wedge shape as finding #1,
different call site.

**Suggested fix:** Read output on its own thread (or via
`ProcessBuilder.redirectOutput`/`java.io.InputStream` polling with a select-style
loop) so `waitFor(timeout)` is actually reachable while output is still arriving;
or wrap the whole call in an `ExecutorService` future with a real deadline.

---

### 4. MEDIUM — SnapRAID parity actions call a binary that only exists on the host, never in the container

**File:** `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/DisksService.java:52`
(`PARITY_HELPER = "/usr/local/bin/aurora-parity-action"`), deployed by
`host/roles/snapraid/tasks/main.yml:71-72` onto the **host's** `/usr/local/bin`.
Cross-checked `packages/dashboard/compose.yml`'s full volume list and
`packages/dashboard/Dockerfile`: nothing bind-mounts `/usr/local/bin` (or that
specific file) into the container, and nothing copies it into the image at build
time.

**Trigger:** Clicking "Sync parity now" or "Scrub" on the disks page ->
`DisksController` -> `JobService.submitCommand(PARITY_SYNC/SCRUB, ..., disks.syncArgv())`
-> `CommandRunner.stream` -> `new ProcessBuilder("/usr/local/bin/aurora-parity-action", "sync")`
executed *inside* the `aurora` container's own filesystem namespace, where that
path does not exist.

**Consequence:** `ProcessBuilder.start()` throws (`No such file or directory`);
`JobService.submit`'s catch-all marks the job `FAILED` with that raw message, which
`JobFailureClassifier` doesn't recognise (its patterns cover port conflicts,
registry issues, disk-full, docker-down, container-crashed — not "binary
missing") so it falls through to the generic "Something went wrong ... the log
below has the details" — technically honest, but gives no hint that the feature
is structurally unreachable rather than transiently broken. This is on the active
`backend-disks` branch, not yet a live regression, but as written both buttons will
fail every single time on real hardware.

**Suggested fix:** Either bind-mount that one script (read-only) into the
container at a fixed path, or route the action through the host's Docker socket
the same way `LaunchService` reaches `up.sh` (bind-mount the *directory*
containing it, matching the same-absolute-path trick `AURORA_REPO_PATH_HOST`
already uses) — the current design has no bridge at all.

---

### 5. MEDIUM — WireGuard live status shells to a binary that isn't installed, and even if it were, the interface lives in a different network namespace

**File:** `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/VpnService.java:184-189`
(`commands.run(List.of("wg", "show", DEFAULT_IFACE, "dump"))`), Dockerfile at
`packages/dashboard/Dockerfile:47` (`apk add ... bash docker-cli
docker-cli-compose avahi-tools bind-tools ca-certificates` — no
`wireguard-tools`), `packages/dashboard/compose.yml:29` (`networks: [aurora_net]`,
not `network_mode: host`).

**Trigger:** Any read of the VPN status page while identity/VPN peers exist.

**Consequence (proven, binary):** `wg` genuinely is not on the image; the class's
own Javadoc already concedes this ("On a box with no `wg` binary (every dev/test
box) that command fails to start... an honest 'gateway down'"), which reads as
though production boxes are expected to have it, but nothing installs it there
either.
**Consequence (suspected, not executed):** even with the binary added, `wg0` is
Aurora's own inbound WireGuard interface, which by every indication in the
package layout is a host or host-adjacent resource — the `aurora` container is
attached only to the `aurora_net` bridge network, with no shared network
namespace and no `NET_ADMIN` capability. Standard Linux behaviour is that a
network interface created in one network namespace is invisible to a process in
another; `wg show wg0` from inside `aurora` would very likely still report "no
such device" even with the binary present. This second half is a plausible
inference from the compose/network config, not something run against a real
`wg0` interface.

**Consequence for the operator:** the VPN peers page can never show live
handshake/traffic data; it will always read "gateway down", indistinguishable in
the UI from an actual outage. A `backend-vpn` workstream is active in this
session — worth confirming whether this is already a known, accepted gap before
spending more time on it.

---

### 6. MEDIUM — Shell-rendered Caddy snippets briefly bypass the Authelia SSO gate on every `up.sh` run

**File:** `scripts/lib/render.sh:26-51` (`render_caddy_snippets`, a raw
`install -m 0644` copy, no SSO awareness) vs.
`packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/CaddySnippetService.java:159-183`
(`reconcile()`, which injects `import authelia` into every vhost block for a
package with `sso.protect: true`) and its own Javadoc at lines 88-93, which
already names this exact gap ("no user-change hook... the 60s drift is fine...").

**Trigger:** Any invocation of `up.sh` — the wizard's "Launch" click, or a plain
`bootstrap.sh add <pkg>` from the CLI — writes the **unprotected** raw snippet
first (`render_all` runs synchronously inside `up.sh`, before the dashboard's own
reconcile has a reason to fire again). Caddy's `--watch` flag
(`packages/core/compose.yml:41`) picks up that file change and reloads within
seconds. `CaddySnippetService`'s own reconcile — which is what actually adds the
`import authelia` line — only re-runs on `ApplicationReadyEvent` or a 60-second
`@Scheduled` drift guard; there is no event-driven hook for "a package was just
enabled".

**Consequence:** for up to ~60 seconds after every launch or CLI-driven package
add, an SSO-protected package's vhost is reachable through Caddy **without** the
Authelia gate — a real, if short and self-healing, authentication bypass window.
The class's own doc comment already flags the mechanism as a known trade-off; it
may be judged acceptable for a homelab, but it's worth being explicit that this is
a live security property of every launch, not just a theoretical edge case.

**Suggested fix:** Have `up.sh` call the dashboard's own reconcile endpoint (or
skip writing the raw snippet entirely when the dashboard is reachable) instead of
writing an intentionally-inferior version first.

---

### 7. LOW/latent — `AURORA_INVOKED_BY` vs `AURORA_LAUNCHED_BY`: two names for what should be one guard

**File:** `LaunchService.java:255` sets `AURORA_LAUNCHED_BY=aurora-dashboard`;
`JobService.java:153` sets a *different* variable, `AURORA_INVOKED_BY=aurora-dashboard`,
for the same "a job started this subprocess, not an interactive shell" purpose.

**Trigger:** None today — `JobService.submitCommand` is currently only wired to
the SnapRAID parity helper (finding #4), which isn't `up.sh` and doesn't care
about self-recreate. But `JobService.Kind` already has `ENABLE`, `DISABLE`,
`START`, `RESTART`, `UPDATE`, `DEPLOY` defined (unused today per a repo-wide
grep), which read exactly like future buttons that would shell out to `up.sh`.

**Consequence:** whoever fixes the known `AURORA_LAUNCHED_BY` self-recreate bug
will naturally make `up.sh` look for that one variable name. If a future PR wires
one of those unused `Kind`s to `up.sh` via `JobService.submitCommand` (the natural
thing to do, given the helper already exists), it inherits `AURORA_INVOKED_BY`
instead and silently loses the fix. Worth a one-line note wherever the fix lands:
either rename `JobService`'s variable to match, or have `up.sh` check for either.

---

### 8. LOW/latent — `packages/core/.env` can lose its `.env.example` defaults if Java ever writes `DOMAIN=` before `up.sh` has seeded the file

**File:** `OnboardingService.java:680-708` (`upsertCoreEnvDomain`, called from
`setDomain()`) vs. `scripts/up.sh:119-125` (seed-from-example, gated on
`[[ ! -f "$env_real" ]]`).

**Not triggered in the documented install path**: `bootstrap.sh`'s
`_resolve_selection` always forces `core` into the very first `up.sh` invocation
(`req_set["core"]=1"`), so `packages/core/.env` is already fully seeded from
`.env.example` (`TZ=Europe/London`, `HOMEPAGE_VAR_QBIT_USER=admin`, etc.) before
the dashboard container — and therefore its wizard — is ever reachable. Verified
by reading `bootstrap.sh` end to end.

**Where it would trigger:** any path that reaches the onboarding wizard's
`setDomain()` before `up.sh` has run once for `core` — e.g. the E2E harness
(`packages/dashboard/e2e/scripts/reset-aurora-e2e.sh`) or a hypothetical future
"start just the dashboard container and let the wizard do everything" quickstart.
In that case `upsertCoreEnvDomain` creates `packages/core/.env` from scratch
containing only the `DOMAIN=` line; `up.sh`'s seed step then never fires (file
exists), permanently losing `TZ` and every `HOMEPAGE_VAR_*` default including the
non-blank `HOMEPAGE_VAR_QBIT_USER=admin`.

**Consequence:** homepage widgets for qBittorrent/Sonarr/Radarr/etc. show auth
errors instead of the intended blank-but-documented state, and the container's
`TZ` silently falls back to whatever the compose file's own default is rather
than the box's real timezone. Not currently reachable via the documented install,
so latent rather than live — but a fragile, undocumented ordering dependency
that a future refactor could easily break.

**Suggested fix:** have `upsertCoreEnvDomain` seed from `.env.example` itself when
the target file is absent (the same template the shell side uses), rather than
synthesising a single-key file.

---

### Checked, no bug found

- **`.state.yml` snake_case -> camelCase**: `StateFileService.readState()` maps
  each YAML key to a `RepoState` field by hand (`bootstrap_version` ->
  `bootstrapVersion`, etc.); `mutateState()` round-trips the whole map generically
  and only touches the keys it means to (`domain`, `enabled`), so unrelated keys
  survive a Java-side write. The one soft spot: adding a new field to the shell
  schema requires remembering to add the matching `data.get(...)` in Java, or it's
  silently dropped on read — low-severity, no live symptom found.
- **Identity secrets** (`IdentitySecretsService` vs `scripts/rotate-secrets.sh
  --apply`): both can populate `packages/identity/.env`'s Authelia secrets, but
  `rotate-secrets.sh`'s weak-value check (`< 12` chars) never matches Java's
  64-character hex secrets, so the shell side won't clobber a Java-generated
  value. No conflict found.
- **Caddy snippet destination path**: both `render.sh` and `CaddySnippetService`
  write to the same `$REPO/data/caddy/snippets/` that `packages/core/compose.yml`
  bind-mounts into Caddy — no path mismatch, only the content-quality gap in
  finding #6.
- **`AURORA_REPO_PATH` same-absolute-path contract**: `compose.yml` deliberately
  bind-mounts the repo at its own host path rather than `/repo`
  (`AURORA_REPO_PATH_HOST`), specifically so a `docker compose` invocation shelled
  out from inside the container resolves relative bind mounts the same way the
  host daemon does. `application.yml`'s `/repo` is only a fallback default for
  local dev; production always overrides it. This looks like a deliberate, correct
  fix for exactly the class of bug this audit was looking for.

## Summary count

- 3 High (would plausibly affect a physical-hardware install today: #1 launch
  timeout/no-cancel, #2 `AURORA_UID` hardcoding, #3 registry-check timeout)
- 3 Medium (#4 parity helper unreachable from the container, #5 `wg`
  binary/namespace gap, #6 SSO-gate timing window)
- 2 Low/latent (#7 env-var naming split, #8 core `.env` ordering dependency)
