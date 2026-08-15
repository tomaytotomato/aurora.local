# App detail page — progress log

Scope: PackageDetail.vue and what it renders. Four faults from a real box.

## Fault 1 — core packages report the wrong state (DONE, backend)

Root cause was backend, not frontend. `GET /api/packages/{name}`
(`PackagesController.get()`) was wrapping the response in
`{package: {...}, env_example: "..."}`, but openapi.yaml's `PackageDetail`
schema (and the frontend's `PackagesApi.get()`, and the msw mock handler
in `src/mocks/handlers/packages.ts`) all expect a **flat** object.

Consequence on the wire: `detail.value.name`, `.enabled`, `.running` were
all `undefined` on the detail page for every package. That cascades:

- `isCorePackage(detail.value)` does `CORE_PACKAGES.has(p.name)` — with
  `name` undefined, this is always `false`, so `isCore` was `false` for
  identity/storage/core too.
- `removable` (`!isCorePackage`) was therefore `true`, so the "Add app"
  button rendered for core packages.
- The badge and "Docker — Not currently running" text read off
  `detail.enabled` / `detail.running`, both `undefined` → falsy → the
  DISABLED/"not running" branch, regardless of real state.

No test covered `GET /api/packages/{name}`'s response shape (only path
existence via `OpenApiConformanceTest`, not body schema), so this drifted
silently.

Fix: `PackagesController.get()` now returns the `Package` domain object
directly (flat), matching what the frontend and its mocks always expected.
Dropped the unused `env_example` field — nothing on the frontend ever read
it; env values come from the separate `GET /packages/{name}/env` endpoint.

Added `PackagesControllerTests` (backend) asserting the response is flat
and that a running+enabled core package reports both flags `true` at the
top level.

## Fault 2 — action button invisible until first tab click

Investigating next.

## Fault 3 — page width jumps between tabs (DONE, CSS)

Root cause: `.content` (the page column in `AppShell.vue`, applied via
`<div class="content py-10 flex-1">`) is a flex item of `main`
(`flex flex-col`), not a plain block box. `.content`'s own rule sets
`max-width` + `margin-inline: auto` with no explicit `width`. Per the
flexbox spec, an auto margin on the cross axis (horizontal, since `main`
is a column flex container) takes priority over `align-items: stretch`
— so the box fell back to shrink-to-fit sizing based on its own content
instead of filling the column up to `max-width`. Overview's two-column
grid is wider content than Config's single form, so the whole page frame
visibly resized when switching tabs.

Confirmed with a Playwright script driving the real dev server
(`VITE_USE_MOCKS=1 vite`) against `/apps/media`: before the fix,
`.content`'s measured width was 913.5px on Overview vs 693.4px on
Config (viewport 1440px); patching in `width: 100%` made both read
1080px. Applied that as the actual fix in `main.css`'s `.content` rule.

No jsdom regression test added for this one — jsdom doesn't run a real
layout engine, so `getBoundingClientRect`/computed widths aren't
meaningful there. Verified empirically instead (see above); the fix
itself is one line plus a comment recording the reasoning so it isn't
undone by accident.

## Cross-check with the parallel StatusProbeService finding

A parallel agent flagged that `StatusProbeService.probe()` defaults the
probed container name to the package name when a manifest has no
`probe:` block, and that `identity`'s real container is `authelia`, not
`identity` — the same class of bug already fixed for `dashboard`/`core`
and `notes`.

Checked whether this is the same root cause as fault 1: it is not.
`/api/packages/{name}`'s `enabled`/`running` fields come from
`PackagesService.parseManifest()`, which derives `running` from
`runningPackageNames()` — a docker-compose **project-label** path match
(`/packages/<name>/` in `com.docker.compose.project.config_files`), not
a container-name lookup. That path never touches `StatusProbeService`,
so fault 1 was genuinely the wrapped-response bug, independent of this.

It does matter for fault 4, though: the status light needs richer
states (starting, unhealthy) than the two booleans on `PackageDetail`
give, and `StatusProbeService`'s per-package probe (`/api/services/status`,
already in openapi.yaml) is the natural source for that. Wiring the new
light to it would have silently reproduced the identity bug one layer up
— probing container "identity" (default) instead of "authelia", reporting
not-started/unknown for a healthy Authelia. Fixed by adding a `probe:`
block to `packages/identity/manifest.yml` (`container: authelia`),
matching the existing pattern in `notes`/`memos`/`media`. Verified
against `.github/schema/manifest.schema.json` and the full backend suite
(725 tests, unchanged).

**Is the container-name default the right long-term shape, or should it
fail loudly?** Silent default-to-package-name is how this survived in
ten manifests before anyone noticed — a wrong answer that looks like a
right one. My view: the default should stay (a package with a single,
plainly-named container is the common case, and forcing every manifest to
declare `probe:` explicitly would be pure boilerplate for no benefit),
but `PackagesService`/`StatusProbeService` should distinguish "container
never found" from "container found and stopped" more loudly than they do
today — right now both paths land on the same generic `not-started`
result, so a wrong container name and a package that's genuinely switched
off are indistinguishable from the JSON alone. A follow-up worth raising:
have `probeDocker()` log at `warn` (not silently) when
`docker.findByName(container)` comes back empty AND the package is in
`enabled[]`, since "enabled but its container was never found" is almost
always a manifest typo, not a real down state. Sweeping the remaining
eight (`git`, `photos`, `ai`, `backup`, `dev`, `documents`,
`home-automation`, `monitoring`) is out of scope here — left for whichever
agent owns those packages this session.

## Fault 2 — action button invisible until first tab click (DONE)

Reproduced the real mechanism with a Playwright script against the live
dev server rather than guessing: a plain fresh load of `/apps/identity`
renders the action card fine within ~250ms with zero interaction — no
bug there. The real defect only shows up navigating **between two
package detail pages** (`/apps/media` → `/apps/identity` via a link, or
browser back/forward) — Vue Router reuses the `PackageDetail` component
instance across a `:name` param change on the same matched route, so
`onMounted` never fires a second time. `packages.fetchOne(name.value)`
was only ever called from `onMounted`, so the whole page — not just the
action button — kept showing the previous app's data (or nothing, first
visit) until a hard reload.

Confirmed with Playwright: `action-card exists = false` at every
timestamp checked (250ms through 1750ms) after a client-side nav to a
second package, including after clicking between tabs — clicking a tab
never fixed it in isolation, which matches "not a design decision": it
is a real gap, not perceived load latency.

Fix: extracted `loadDetail()` and added a `watch(name, () => loadDetail())`
alongside the existing `onMounted` call, mirroring the pattern the two
other `watch(name, ...)` blocks in this file already use for
containers/env and the job/network reset. Also added a loading skeleton
for the action-panel area (`data-test="package-actions-skeleton"`) so the
brief real-network gap before first paint reads as "loading" rather than
blank silence — a genuine, if secondary, contributor to how this looked
in the wild.

Regression test: `PackageDetail.spec.ts` — "re-fetches package detail
when the route :name param changes without a remount" mounts once,
asserts on `media`'s buttons, pushes to `/apps/photos` without
remounting, and asserts the buttons updated to `photos`'s state.

## Fault 4 — control panel (Install/Disable/Start/Uninstall + status light) (DONE)

### Status light — six states, wired to the real probe

`StatusLight.vue` wraps the existing `Badge` primitive (it already
renders a currentColor dot for non-neutral tones) rather than inventing
a bespoke dot component. States: `running` (ok/green), `starting`
(warn/amber), `unhealthy` (err/red), `stopped` / `not-installed` /
`unknown` (all neutral/grey, but distinguished by **label text** — the
task's "unknown must be representable" requirement is met by giving it
different copy from `not-installed`, not a different colour, since both
are honestly "grey" states).

Derivation (`deriveStatusLight` in `lib/packageLifecycle.ts`):
1. `unknown` — the initial `GET /packages/{name}` hasn't resolved yet.
   This is the only case that shows unknown; once `enabled`/`running`
   are known, there is always a real answer for at least running/stopped.
2. `not-installed` — `!enabled`.
3. Otherwise, prefers the live probe (`GET /services/status`, via the
   existing `useServiceStatusStream` composable — same SSE-with-poll-
   fallback the Done checklist and dashboard home already use) for the
   `starting`/`unhealthy` distinction the plain booleans can't make;
   falls back to the `running` boolean when no probe entry exists yet
   (stream not delivered, or the package isn't in `StatusProbeService`'s
   scope).

This is exactly where the identity/`StatusProbeService` container-name
bug (see above) would have resurfaced one layer up if left unfixed — the
identity manifest fix was a precondition for this light being honest.

### Action matrix — pure function, 13 tests

`packageActionSlots` in `lib/packageLifecycle.ts` maps
`{isCore, enabled, running}` to the four slots. Endpoint check against
openapi.yaml:

| Action    | Endpoint                              | Exists? |
|-----------|----------------------------------------|---------|
| Install   | `POST /packages/{name}/enable`         | yes |
| Start     | `POST /services/{package}/start`       | yes |
| Uninstall | `POST /packages/{name}/disable`        | yes (its own summary is "Stop and disable a package" — it already stops a running package as part of removing it, so Uninstall stays available whether the app is running or stopped, matching the pre-existing "Remove" button's behaviour) |
| Disable   | *(stop only, keep enabled/installed)*  | **no** |

**Disable has no backend verb.** `enable` installs-and-starts; `disable`
stops-and-removes. There is no "stop this app but leave it configured"
endpoint in openapi.yaml. Per the task's instruction not to touch the
spec, the Disable button is implemented visible-but-disabled (only shown
at all for a running, non-core app — a state where stopping is
conceptually valid) with inline reason copy
("Aurora doesn't have a way to stop this app without also uninstalling
it yet.") rather than silently wired to `disable()` or hidden outright.
**This is the one control that needs a spec change** (a new
`POST /packages/{name}/stop`-shaped endpoint) before it can do anything.

State → visible actions:
- not-installed: Install only.
- stopped (enabled, not running): Start, Uninstall.
- running (enabled, running): Disable (disabled+reason), Uninstall.
- core (any enabled/running combination): none of the four — locked
  purely on `isCore`, independent of the wire state, per "a core package
  can do none of them."

Restart and Update keep their pre-existing, separate gating
(`isCore || detail.enabled`) — the task named four specific actions to
add, not to remove what already worked.

### Wiring

- Install → `PackagesApi.enable()` (unchanged, renamed from "Add app").
- Start → `ServicesApi.start()`, new to this page. Its response shape is
  `{job_id, packages, started_at}` (snake_case `job_id`), unlike the
  other lifecycle verbs' `{jobId}` — handled explicitly in `startJob()`
  rather than papering over the mismatch.
- Uninstall → `PackagesApi.disable()` (unchanged, renamed from "Remove"),
  still behind the existing destructive confirm `Dialog` (danger variant).
- Disable → not wired; see above.
- All three real actions stream into the existing `JobLogPanel` via
  `activeJobId`/`activeAction`, exactly like the pre-existing
  install/uninstall/update actions — verified against the real dev
  server (mocked backend): clicking Start on a stopped package shows a
  live "Starting…" panel with an elapsed timer and log line, and locks
  the other buttons while it runs.

### Verification

Backend: `mvn test` 725 (was 721 + 4 new `PackagesControllerTests`).
Frontend: `npm run typecheck` clean; `npm run test:unit` 485 (was 457 +
13 `packageLifecycle.spec.ts` + 8 `StatusLight.spec.ts` + 7
`PackageDetail.spec.ts`). Manually verified against the mocked dev
server for identity (core, running, no actions), media (stopped,
Start+Uninstall, "Starting…" probe state), photos (not-installed,
Install only), and monitoring (running, Disable shown-disabled with
reason, Uninstall available).
