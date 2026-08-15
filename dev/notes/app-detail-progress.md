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

## Fault 4 — control panel (Install/Disable/Start/Uninstall + status light)

Investigating next.
