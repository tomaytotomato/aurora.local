# Aurora UX — Iteration 2 plan

**Branch:** `rename/aurora`
**Baseline:** `packages/dashboard/e2e/results/iter-1.md` — Done-page SSH cliff dead; `done-launch.spec.ts` 2/2; `no-cli-instructions.spec.ts` 26/28 (2 out-of-scope TLS `sudo` copies).
**Author:** product-manager (Sarah persona, no-CLI-ever principle)

---

## 1 · P0 target for iteration 2

**Turn the four static "follow-up" tiles under the Start-services button into
a living, ordered, probed checklist.**

Right now `OnboardingDone.vue` still ships this, verbatim, below the
launch panel:

```
┌─ Home ────────────────┐  ┌─ Next ────────────────┐
│ Aurora                │  │ AdGuard first-run     │
│ The dashboard you're  │  │ Set the AdGuard admin │
│ standing in.          │  │ password. Aurora      │
│ Open Aurora →         │  │ can't do this for you │
└───────────────────────┘  └───────────────────────┘
┌─ Media ───────────────┐  ┌─ Files ───────────────┐
│ Onboard Sonarr/Radarr │  │ Mount the shared      │
│ Each *arr wants…      │  │ folder                │
└───────────────────────┘  └───────────────────────┘
```

Four tiles. No status. No priority. No probing. No one-click actions
beyond "Open X →". Sarah stares at this and doesn't know:

1. Which of the four is actually done.
2. Which one is the fire she must put out **now**.
3. That AdGuard's admin password is still unset — which means her router,
   if she pointed it at the box, is now doing LAN DNS through an
   unauthenticated admin panel exposed on `:3000`. That is the single
   biggest live-fire security issue Aurora ships, and today Aurora
   doesn't say a word about it.

The E2E suite already codifies the target shape. Three specs are red
against the current implementation and drive this iteration:

- `done-page-checklist.spec.ts` — `[data-package]` cards with `[data-status]`
  pills, ordered blockers → optional, primary CTAs limited to
  `Open` / `Finish setup` / `Retry` / `Waiting…` / `Start`, single
  "Go to my dashboard" primary at the bottom.
- `package-status-probing.spec.ts` — `data-status` matches the enum
  `running|needs-config|failed|not-started|starting` within 5s of load.
- `adguard-password-check.spec.ts` — privacy card labelled
  `Finish AdGuard setup`, pill = `needs-config`, dashboard checklist row
  visible with `data-tone="warn"|"err"`.

Everything else — the media sub-checklist (Prowlarr → Sonarr → Radarr →
Seerr), the SMB reachability probe, dashboard-home checklist rendering
outside `/onboarding/done` — waits for iter-3. Do not batch.

---

## 2 · Required implementation

### 2a · Backend — one new endpoint, one new service

Aurora probes each enabled package's health endpoint on the user's
behalf. The user never opens DevTools. The user never types a URL.

- **`GET /api/services/status`**
  - Reads `.state.yml` `enabled_packages` via the existing
    `StateFileService` — same source `PackagesService.list()` uses.
  - For each enabled package, runs a **probe** (§2b) with a **2-second
    hard timeout per service**, in parallel via a bounded executor
    (`ForkJoinPool.commonPool()` is fine; cap concurrency at 8).
  - Returns:
    ```json
    {
      "generated_at": "2026-08-01T18:14:22Z",
      "services": [
        {
          "package": "privacy",
          "container": "adguardhome",
          "state": "needs-config",
          "reason": "AdGuard admin password not set",
          "detail": "First-run setup incomplete. Open AdGuard to finish.",
          "open_url": "http://aurora.local:3000/",
          "priority": 0,
          "probed_ms": 84
        },
        {
          "package": "media",
          "container": "sonarr",
          "state": "running",
          "reason": null,
          "detail": null,
          "open_url": "http://sonarr.aurora.local/",
          "priority": 4,
          "probed_ms": 132
        }
      ]
    }
    ```
  - **Not** SSE. Frontend polls every 5s while `/onboarding/done` is
    mounted (§2c). SSE lives on iter-3 alongside dashboard-home rendering
    and the media-stack sub-checklist — one transport switch, one time.
  - Cache: 3s in-memory TTL per package (`ConcurrentHashMap<String,
    CachedProbe>`). Two clients polling at 5s must not amplify to eight
    upstream calls.
  - Bootstrap-mode posture: this endpoint is permitAll **during
    onboarding only** (mirrors `/api/onboarding/**` in
    `SecurityConfig.java`). Once onboarding is complete it requires an
    authenticated admin session. Add the matcher explicitly, do not
    piggyback on `/api/onboarding/**`.

- **`StatusProbeService.java`** — new. Owns:
  - The probe registry (§2b).
  - The 3s TTL cache.
  - The 2s per-probe timeout enforced with `HttpClient.Builder.connectTimeout(Duration.ofSeconds(1))`
    + a per-request `.timeout(Duration.ofSeconds(2))` on the
    `HttpRequest` — belt and braces because DNS-slow hosts still count
    against the wall-clock.
  - Priority weights (§5.1): `failed=0, needs-config=1, not-started=2, starting=3, running=4`.
  - **No** shell-out. **No** docker exec. Only HTTP against the LAN URL
    the package's manifest already publishes. If a probe needs container
    inspection, use the existing docker-java client injected into
    `PackagesService` — do not add a second docker client.

- **`StatusController.java`** — new. Exactly one handler:
  `GET /api/services/status`. Do not overload PackagesController; the
  status surface is going to grow (SSE, forced-refresh POST) and it
  earns its own file.

- **`StatusProbeServiceTests.java`** — new. Cover:
  - AdGuard 200 with `{"configured": false}` → `needs-config`.
  - AdGuard 401 → `running` (setup complete, locked as expected).
  - AdGuard 500 → `failed`.
  - Sonarr `/api/v3/system/status` 200 with valid JSON → `running`.
  - Sonarr connect refused → `not-started`.
  - Probe timeout (deliberate 3s server) → `failed` with reason
    `"probe timed out after 2s"`. No test that runs longer than 2.5s
    wall-clock.
  - Priority sort is stable when two rows share a state (secondary sort
    by package name alphabetical — deterministic renders keep the E2E
    snapshot honest).

### 2b · Probe registry — what "healthy" means per package

**Iter-2 covers only these four packages.** The others fall through to a
generic docker-inspect fallback (`running` if the compose service is
`Up (healthy)` or `Up`, `not-started` if absent, `failed` if `Exited`).
Iter-3 owns per-package refinement for media / storage / photos /
home-automation.

| Package | Probe | `running` | `needs-config` | `failed` |
|---------|-------|-----------|----------------|----------|
| **privacy** (AdGuard) | `GET http://adguardhome:3000/control/status` (in-cluster) | 200 **and** `dhcp_available`/`running`==true **and** password set (see §2d) | 200 with `configured:false` OR default-creds probe succeeds | 5xx or unreachable **after** container reports Up |
| **media** (Sonarr indicator only for iter-2) | `GET http://sonarr:8989/api/v3/system/status` | 401 (auth wall = up & configured) OR 200 | 200 with `authentication:"None"` and no API key set | timeout/5xx/connect-refused |
| **storage** (Files) | Docker inspect `files` container | health `healthy` OR state `running` >30s | container up but SMB share not exported (deferred — iter-2 returns `running` if container up) | container `Exited` |
| **core** (Aurora itself) | short-circuit → `running` (we're serving the request) | always | never | never |

Everything else in `enabled_packages` (ai, backup, dev, documents, git,
home-automation, identity, monitoring, notes, photos): docker-inspect
fallback. Grey pill, `Open` CTA, priority 4 (running) or 2 (not-started).

Explicit non-goal (repeat, because worker will be tempted): **do not
probe qBittorrent, Prowlarr, Radarr, Bazarr, Seerr, RDTClient,
Flaresolverr, Gluetun, Immich, Home Assistant, Grafana, etc. in iter-2.**
One probe per package, and only for the four in the table. The media
sub-checklist unfolds in iter-3.

### 2c · AdGuard first-run detection

Sarah's blocker. The AdGuard container has two lifecycle phases:

1. **First run** — image up, no config volume yet. `/control/status`
   returns `200` with `{ "protection_enabled": false, "running": false,
   "dns_addresses": [], ... }` and `/control/install/get_addresses`
   returns `200` with the setup payload. **This is the state Aurora
   MUST catch and surface as `needs-config`.**
2. **Configured** — `AdGuardHome.yaml` exists in the volume. `/control/status`
   returns `200` with `running:true` if the request is authenticated,
   or `401 Unauthorized` if not (AdGuard requires basic auth once a
   user is created). **401 is a success signal.** Treat as `running`
   with reason `null`. Do **not** try to guess or brute-force creds.

Detection order (short-circuit on first match):

1. `GET /control/install/get_addresses` → `200` → **first-run**,
   `needs-config`, `reason: "AdGuard admin password not set"`,
   `open_url: http://<lan-ip>:3000/`.
2. `GET /control/status` → `401` → **configured**, `running`.
3. `GET /control/status` → `200` with `running:true` → **configured**,
   `running`.
4. Any other response (timeout, 5xx, connect-refused after container Up)
   → `failed`, `reason: "AdGuard is not responding"`.

The `open_url` for AdGuard is **always the first-run port** (`:3000`,
per `packages/privacy/manifest.yml`), even after setup completes. That
port hosts the admin UI too. Do not try to be clever with
`http://adguard.aurora.local`; the Caddy vhost for that name is not
guaranteed to exist yet when the probe runs on the Done page.

### 2d · Frontend — replace the tile grid, not the launch panel

Two new components. `OnboardingDone.vue` gets a lower-half rewrite that
mounts `<DoneChecklist />` in place of the current
`grid grid-cols-2 gap-4` block (lines 121–158).

**`api/services.ts`** — new.

```ts
export interface ServiceStatus {
  package: string;
  container: string | null;
  state: 'running' | 'needs-config' | 'failed' | 'not-started' | 'starting';
  reason: string | null;
  detail: string | null;
  open_url: string;
  priority: number;
  probed_ms: number;
}

export interface ServicesStatusResponse {
  generated_at: string;
  services: ServiceStatus[];
}

export const ServicesApi = {
  status(): Promise<ServicesStatusResponse> {
    return http.get('/api/services/status');
  },
};
```

**`components/DoneChecklist.vue`** — new. Contract:

- Props: `enabledPackages: string[]` (fallback ordering source when the
  status endpoint hasn't returned yet).
- Fetches `ServicesApi.status()` on mount, then every **5 000 ms** while
  the component is mounted. `clearInterval` on `onBeforeUnmount`. **Not
  `setInterval` inside a `setup()` return** — use `onMounted` /
  `onBeforeUnmount` so hot-reload doesn't leak timers.
- On fetch failure: keep the previous frame's data, dim the pill
  container `opacity-70`, show a subtle `Reconnecting…` line at the
  bottom of the checklist. Never blank the list. Never throw.
- Skeleton frame **only on the very first render** while the initial
  request is in flight — render one placeholder row per
  `enabledPackages` entry with `data-status="starting"`, no button.
  This keeps the E2E "renders within 5s" assertion honest even when
  the backend is slow.
- Emits nothing upward. `OnboardingDone.vue` no longer needs to know
  about status; the "Take me to Aurora" CTA at the footer stays gated
  on `launchState === 'success' || toStart.length === 0` (unchanged
  from iter-1).
- Rendering order: sort by `priority` ascending, tie-break alphabetical
  on `package`. Do **not** re-sort on each poll if the array is
  identical — Vue's key-based diff will not thrash, but avoid layout
  jitter by keying `<ChecklistItem>` on `package` (not index).
- Collapse rule (spec §3): items with `state==="running"` **and** no
  `reason` render collapsed — one line, small green pill, "Open" link,
  no description. Everything else renders expanded.
- Banner: if any row has `state==="failed"` or `state==="not-started"`,
  render `<div data-banner="bringing-up">` above the list with copy
  `"Aurora is still bringing these online. Nothing for you to type."`
  (matches `done-page-checklist.spec.ts` `X4`).
- Root element carries `data-checklist="get-started"` so the dashboard
  spec (which will look for the same checklist post-onboarding in
  iter-3) already finds it here.

**`components/ChecklistItem.vue`** — new. Contract:

- Props: `service: ServiceStatus`.
- Emits: `(e: 'mark-done'): void`, `(e: 'skip'): void`.
- Root element `<li data-package="privacy" data-row="privacy" data-tone="warn">`.
  - `data-tone` derived from state: `failed → err`, `needs-config → warn`,
    `not-started → warn`, `starting → info`, `running → ok`.
- Contains `<span data-status="needs-config">…</span>` — the pill.
  - Palette: `running` = green, `needs-config` = **red** (blocker; spec
    §5.1 (1)), `failed` = red with an alert icon, `not-started` = grey,
    `starting` = grey with a spinner.
  - Text: capitalise-first English. `"Needs setup"` not
    `"needs-config"`. Spec §3.10 X3 expects the `data-status` **attribute**
    to be the enum value; the visible text is user copy.
- Primary CTA — **one** button per row. Label rules (must match the
  `done-page-checklist.spec.ts` regex `^(Open|Finish setup|Retry|Waiting…|Start)$`):
  - `running` → `Open` (opens `open_url` in a new tab, `rel="noopener"`)
  - `needs-config` → `Finish setup` (same target)
  - `failed` → `Retry` (POSTs `/api/onboarding/launch` with the package
    scoped down to just this row — reuses iter-1 launch machinery, does
    **not** invent a new endpoint. If the launch job is already
    running, disables to `Waiting…`)
  - `starting` → `Waiting…` (disabled)
  - `not-started` → `Start` (POSTs `/api/onboarding/launch` scoped)
- Secondary actions — text buttons, no border:
  - `I did this` → sets a local override (`localStorage['aurora.checklist.privacy.done'] = 'true'`)
    which the checklist honours by collapsing the row and treating it
    as `running`. Cleared on next poll if the backend disagrees for
    ≥ 2 consecutive polls. Small, muted, right-aligned.
  - `Skip` → hides the row for the rest of this session
    (`sessionStorage['aurora.checklist.privacy.skipped'] = 'true'`).
    Does not affect other tabs. Does not persist across restarts. If
    the row later flips to `failed`, unskip automatically — a failure
    is not skippable.
- The `I did this` and `Skip` buttons **exist only for
  `needs-config` and `not-started`** rows. Do not render them for
  `running` (no override needed) or `failed` (spec §5.1 (2) forbids
  hiding real failures behind a manual override).

**`OnboardingDone.vue`** — rewrite lines 121–158 only:

- Delete the entire `<div class="grid grid-cols-2 gap-4 mb-10">` block
  and the four hard-coded `<Card>` tiles.
- In its place, mount `<DoneChecklist :enabled-packages="toStart" />`.
- Wrap in `v-if="launchState !== 'running'"` (unchanged behaviour — the
  checklist hides while the launch is streaming so the log is the
  focus).
- Keep the top-half launch CTA and `LaunchProgress` exactly as iter-1
  shipped them. Do not touch lines 1–120 or 160–178.

The bottom-row "Take me to Aurora" button label stays `Take me to Aurora`
in iter-2 — the `done-page-checklist.spec.ts` test that expects
`"Go to my dashboard"` will still fail. **Rename it in iter-2 as part
of this ticket** — one-word copy change, no impact. Update the
`data-testid="to-dashboard"` to also carry the new label.

Nit: while we're here, drop `<code>` from
`Bookmark <code class="text-ink">{{ store.domain }}</code>` in the
Aurora tile — but the Aurora tile is deleted anyway. Non-issue.

### 2e · Manifest / config plumbing (small)

Add a `probe` block to the four manifests we probe:

```yaml
# packages/privacy/manifest.yml
probe:
  kind: adguard          # special-cased in StatusProbeService
  in_cluster_url: http://adguardhome:3000
  external_url: http://{lan_ip}:3000/

# packages/media/manifest.yml
probe:
  kind: http_json
  in_cluster_url: http://sonarr:8989/api/v3/system/status
  external_url: http://sonarr.{domain}/
  auth_treats_401_as_up: true

# packages/storage/manifest.yml
probe:
  kind: docker
  container: files
  external_url: smb://{lan_ip}/  # advisory only; not probed in iter-2

# packages/core/manifest.yml
probe:
  kind: self
  external_url: http://{domain}/
```

`{lan_ip}` resolves via `SystemService.lanIp()` (already in the
codebase per iter-0). `{domain}` is `RepoState.domain()`. Do this in
the service, not in the manifest loader — the manifest loader stays
schema-first.

Every other manifest can omit the `probe` block; the generic docker
fallback handles them.

---

## 3 · Explicit non-goals for iteration 2

- **No media sub-checklist.** Sonarr is one row with one pill. Prowlarr,
  Radarr, Bazarr, Seerr do not appear individually. iter-3 owns the
  media-stack unfold.
- **No SMB reachability probe.** Storage returns docker-inspect only.
  The advisory copy about macOS "Connect to Server" is gone; iter-3
  brings it back as a real probe with a real one-click "Show me on my
  Mac" walkthrough.
- **No changes to any wizard step before `/onboarding/done`.** The 11
  wizard-happy-path failures and the 3 admin/TLS CLI-word failures
  stay red; iter-3 owns them.
- **No SSE for status.** 5s polling is the iter-2 contract. Do not
  swap to SSE partway through — iter-3 will do the transport switch in
  one pass.
- **No dashboard-home rendering.** The checklist lives on
  `/onboarding/done` only in iter-2. The
  `package-status-probing.spec.ts` "renders on `/` post-onboarding"
  case will remain skipped via `test.skip()` when
  `bootstrap_mode:true` — that is the fixture's existing behaviour;
  no test change needed. iter-3 mounts the same
  `<DoneChecklist />` on the dashboard home.
- **No probing of ai/backup/dev/documents/git/home-automation/identity/
  monitoring/notes/photos beyond docker-inspect fallback.** One row per
  enabled package, grey pill or green pill, Open CTA. That's it.
- **No auth flow through the probe.** If AdGuard is 401'd, we take that
  as a signal, we do not try to log in.

---

## 4 · Definition of done

1. **`done-page-checklist.spec.ts` passes all 6 cases.**
   - `has no <pre> or shell-text <code>` — still green.
   - `has no "SSH" / "host" / "operator" / "terminal" text` — still green.
   - `renders a card per enabled package with a data-status pill` — new-green.
   - `every package card has an approved primary action label` — new-green.
   - `rows ordered blockers first, optional last` — new-green.
   - `single "Go to my dashboard" primary CTA at the bottom` — new-green
     (via the `Take me to Aurora` → `Go to my dashboard` rename, §2d).
   - `failed/not-started packages surface a top banner` — new-green
     (via `<div data-banner="bringing-up">` in `DoneChecklist.vue`).
2. **`package-status-probing.spec.ts` passes** on the Done page. The
   dashboard-home cases stay skipped via the existing
   `onboardingComplete()` fixture short-circuit — iter-3 problem.
3. **`adguard-password-check.spec.ts` passes all 3 cases** when the
   privacy package is enabled with a fresh AdGuard container (no
   `AdGuardHome.yaml` in the volume).
4. **`no-cli-instructions.spec.ts` /onboarding/done cases stay green.**
   New copy in `DoneChecklist.vue` and `ChecklistItem.vue` gets scanned
   for banned words (`SSH`, `terminal`, `sudo`, `./scripts/`, `docker `,
   `bash `). No `<pre>` element in either component. No `<code>` with
   shell text. Verify with `grep -RiE '<(pre|code)' packages/dashboard/frontend/src/components/DoneChecklist.vue packages/dashboard/frontend/src/components/ChecklistItem.vue`
   — must return zero hits.
5. **Backend unit tests green:** `mvn -pl packages/dashboard/backend test`
   including new `StatusProbeServiceTests`. No new failures anywhere
   else (guard against LaunchService flakiness — the two share nothing
   but they share the executor).
6. **Live aurora `:8090` healthy through the change.**
   `curl -sf http://localhost:8090/api/services/status` returns a valid
   JSON payload within 2.5s wall-clock, including under `time` — proves
   the 2s-per-probe timeout is holding. Payload must contain one entry
   per enabled package.
7. **Probe cache verified:** two consecutive `GET /api/services/status`
   requests within 3s return identical `generated_at`. Third request
   after 4s has a fresh `generated_at`. Add a
   `StatusProbeServiceTests#respectsTtl` unit test for this.
8. **No new backend test failures.** The `mvn test` counter goes up by
   the new `StatusProbeServiceTests` count and by no other delta.

---

## 5 · Files the worker will touch

**Backend (Java, Spring Boot 4):**

- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/StatusProbeService.java` — **new**.
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/controllers/StatusController.java` — **new**, `GET /api/services/status` only.
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/config/SecurityConfig.java` — add matcher for `/api/services/status` with the same permit-during-onboarding-only gate. **Do not** widen `/api/onboarding/**`; add the explicit path.
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/services/StatusProbeServiceTests.java` — **new**. Cases per §2a bullet list. Use `MockWebServer` (already on the classpath via Spring Boot's test starter, or add `com.squareup.okhttp3:mockwebserver:test` if missing — one-line pom change is acceptable, worker's call).

**Frontend (Vue 3.5 + TS):**

- `packages/dashboard/frontend/src/api/services.ts` — **new**, contract in §2d.
- `packages/dashboard/frontend/src/components/DoneChecklist.vue` — **new**, contract in §2d.
- `packages/dashboard/frontend/src/components/ChecklistItem.vue` — **new**, contract in §2d.
- `packages/dashboard/frontend/src/views/onboarding/OnboardingDone.vue` — **surgical edit**: lines 121–158 replaced with the checklist mount; footer button label + testid updated. Nothing else touched.

**Manifests (four surgical adds):**

- `packages/privacy/manifest.yml` — add `probe:` block per §2e.
- `packages/media/manifest.yml` — add `probe:` block per §2e (Sonarr only — iter-3 expands).
- `packages/storage/manifest.yml` — add `probe:` block per §2e.
- `packages/core/manifest.yml` — add `probe: { kind: self, ... }` per §2e.

**Do not touch** — worker guardrail:

- `LaunchService.java`, `LaunchProgress.vue`, `onboarding.ts` — iter-1
  code; the iter-2 checklist calls the existing launch endpoint for
  Retry/Start, it does not modify it.
- `PackagesService.java` — probe logic lives in its own service, not
  bolted onto the manifest scanner.
- Any manifest other than the four above.
- Any wizard view other than `OnboardingDone.vue`.
- `SystemService.java` — reuse `lanIp()`, do not extend.
- `scripts/up.sh`, `scripts/down.sh`, `compose.yml`, `Dockerfile`.

---

## 6 · Risks + mitigations

**Risk 1 — AdGuard's first-run probe path is not stable across versions.** *(medium)*

`/control/install/get_addresses` is a real endpoint on the AdGuardHome
setup wizard, but it's undocumented as public API. If upstream renames
it, our first-run detector goes silent and Sarah's blocker goes
undetected. Mitigation: probe order is short-circuit; if
`get_addresses` returns 404 we fall through to `/control/status` and
still return `running`/`failed`/`needs-config` sanely. Add a
`StatusProbeServiceTests#adguardFirstRunEndpointGone` case that
simulates 404 on `get_addresses` and asserts we still classify
correctly via `/control/status` unauthenticated response shape.

**Risk 2 — In-cluster hostnames don't resolve from the aurora
container until compose-up has attached it to each service's network.**
*(medium; iter-1 shipped a fix that puts aurora on the shared network,
worker must verify)*

If `http://sonarr:8989` returns `UnknownHostException`, we classify as
`not-started`, which is correct on a fresh box (Sonarr hasn't been
brought up yet) but wrong 30s after Start Services if aurora was
already on a different bridge. Mitigation: worker's smoke test is
`docker exec aurora getent hosts sonarr` **after** a Start Services
completes. If it comes back empty, that's an iter-2 blocker and the
fix is in `compose.yml` (add aurora to the `aurora_net` shared
network), not in the probe.

**Risk 3 — 2s timeouts × N packages could still walk-clock past 5s
under contention.** *(low)*

Parallel probes on `ForkJoinPool.commonPool()` with an 8-wide cap keep
worst-case at `ceil(N/8) × 2s`. With 10 enabled packages that's ~4s.
Add a JVM-side wall-clock assert in `StatusController` — if the total
elapsed hits 4s, return the partial set with `probed_ms: -1` on the
missing rows and let the frontend render them as `starting`. Do **not**
return 504.

**Risk 4 — `I did this` local override goes stale.** *(low, but user-visible)*

If Sarah clicks "I did this" on the AdGuard row but her actual AdGuard
setup fails silently, the row stays green in her browser while the
truth is red. Mitigation: the override is honoured for **at most 2
consecutive polls** where the backend disagrees. On the 3rd contrary
poll (10s+), clear the override and render the backend truth with a
subtle `"We rechecked — this still needs your attention."` line. This
is UX principle 4 ("Errors are actionable") — don't let a stale click
mask a live blocker.

**Risk 5 — Polling on Done page never stops.** *(low)*

If Sarah walks away and leaves `/onboarding/done` open for 8 hours, we
issue ~5,760 polls. Cheap for us, but bad manners. Mitigation:
`document.visibilityState` gate — pause polling when the tab is
`hidden`, resume on `visible`. Reset the interval on resume so the
first poll fires within 1s of tab focus. Standard trick; put it in
`DoneChecklist.vue` not a shared util.

**Risk 6 — CORS / same-origin on the AdGuard `open_url`.** *(nil)*

Non-issue in iter-2: we don't fetch the AdGuard UI from the browser.
The primary CTA `Open` / `Finish setup` is a plain `<a
target="_blank">`, not an `fetch`. Note this so the worker doesn't
invent a proxy route it doesn't need.

**Risk 7 — Copy tests will start reading the new component tree.**
*(low, but easy to miss)*

`no-cli-instructions.spec.ts` walks the whole DOM. Any accidental
`docker ` or `sudo ` string in a `reason`/`detail` field gets
surfaced. Backend rule: **reason/detail strings are for humans, never
copy-paste-able as shell commands.** Add a lint step to
`StatusProbeServiceTests` that asserts no probe result payload
contains the substrings `sudo `, `docker `, `bash `, `./scripts/`, or
`ssh `. This is a one-line `assertThat(json).doesNotContain(...)`
sweep.

---

## 7 · One-line handoff to the worker

> Read `logs/ux-iteration-2.md`. Backend first: `StatusProbeService` +
> `StatusController` + AdGuard first-run detector with a 2s per-probe
> timeout and a 3s cache TTL. Then the two Vue components and the
> lower-half rewrite of `OnboardingDone.vue`. Manifests get four
> tiny `probe:` blocks. Do not touch anything outside §5. Run
> `done-page-checklist.spec.ts`, `package-status-probing.spec.ts`,
> `adguard-password-check.spec.ts`, `no-cli-instructions.spec.ts`
> against the `aurora-e2e` project. Definition of done is §4. Commit
> as `aurora: UX iteration 2 implement (living checklist + probing)`.
