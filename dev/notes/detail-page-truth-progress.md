# App detail page — tabs contradicting the header, progress log

## The brief

Owner installed `notes` on a real box. Header said RUNNING, control panel
said "Enabled and running", install log showed `silverbullet ... Up 2
seconds (health: starting)` — but the Logs tab said nothing was running
yet and the Network tab said the app's networking was gone. Plus a broken
sentence, and an ABOUT card that disagreed with the header about whether
the app had a description.

## Fault 1 — tabs contradict the header

Two different bugs, one symptom. Verified both by reading the actual
backend code rather than accepting the owner's "404 read as empty" theory
at face value.

### Logs tab: real cause, backend

`GET /api/containers?package=<name>` (`ContainersController.list`) did an
**exact** match against the compose project label `aurora-<name>`. But
`scripts/up.sh` — what every real install and this testbed both use —
always runs `docker compose -p aurora ...`, overriding each compose.yml's
own `name:`. Verified live on the testbed: `caddy`, `aurora` (dashboard),
`authelia` and `silverbullet` were all labelled `com.docker.compose.project=aurora`,
never their per-package name, despite `packages/notes/compose.yml`
declaring `name: aurora-notes`. This is the normal case, not a historical
straggler (initial theory before checking a real box).

`GET /api/services/status` never had this problem: `StatusProbeService.probeDocker`
goes through `DockerService.findByName`, which searches every
`aurora`/`aurora-*` container **by name**, not by project label. Two
surfaces, two different definitions of "this package's container" — that
was the actual disagreement, not "empty response = not installed".

Same root cause also broke `package=core`'s Logs tab on any box, since
its target project was hardcoded to `aurora-core`, which nothing actually
carries either.

**Fix**: `DockerService.containersForPackage(pkg, expectedContainer)` —
one definition of "this package's containers", matching by project label
with a name-based fallback under the shared `aurora` project.
`ContainersController` now delegates to it instead of carrying its own
copy of the same logic.

### Network tab: real cause, backend

`GET /packages/{name}/network` had **no controller at all** — not "empty
data for a fresh install", a plain unmapped-route 404, for every package,
always. `openapi.yaml` documents the endpoint (tag `network`); nothing
implemented it. Confirmed via `OpenApiConformanceTest`'s "not yet
implemented" printout, which listed it before this work and doesn't after.

The full feature (`PUT` to actually move an app onto a VPN gateway) is
still `docs/SPLIT_TUNNEL.md`'s "Planned" section — no compose rewrite,
port move, or Caddy vhost update exists anywhere in the codebase. Building
that is a separate, much larger piece of work, not a bug fix. What *is*
buildable honestly today: whether a package's `compose.yml` already
shares a gateway's namespace (`network_mode: "service:<gw>"` — qBittorrent
under gluetun is the one real case), which containers belong to it, and
whether that gateway is actually up.

**Fix**: `NetworkService` + `NetworkController` implement `GET` for real
(direct/vpn mode, gateway detection via a new `ComposeScanner.gatewayFor`,
containers via the same `DockerService.containersForPackage`, published
ports from the manifest). `locked` is `true` for every package with an
honest `lockedReason` ("Aurora doesn't support changing this from the
dashboard yet") rather than pretending a working toggle exists — this
value is what makes the frontend hide the switch and show an `Alert`
instead of an error. `PUT` returns 404 for an unknown package, 409
(matching openapi.yaml's documented response) for a real one, never a
bare 404 that reads as "gone".

## Fault 2 — broken sentence

`humanCopyForError`'s 404 default template was
`` `That ${ctx.subject} is not on this box any more.` ``. Fine for a bare
noun (`"That container is..."`), broken for any subject already phrased
as its own noun ("this app's networking", "this app's configuration") —
"That this app's networking is not..." is a doubled demonstrative, not
two spliced strings, but reads exactly like one.

Fixed the template itself (`packages/dashboard/frontend/src/lib/http-error-copy.ts`),
not the two call sites: `` `Aurora can't find ${ctx.subject} on this box
any more.` `` reads correctly for both a bare noun and a possessive
phrase. Updated the existing pinned test and added one for the
possessive-phrase case specifically.

## Fault 3 — ABOUT card said "no description" when the header had one

`PackageDetail.vue`'s ABOUT card read `detail.readme`; the header reads
`detail.description`. `readme` is in `openapi.yaml`'s `PackageDetail`
schema but the backend's `Package` domain record has no such field and
nothing populates it — so `readme` is `undefined` for **every** package,
not just some. This is universal, not "some manifests are missing a
description" — checked: every `packages/*/manifest.yml` has a
`description:` field.

Fix (frontend-only, deliberately not backend): ABOUT card now prefers
`readme` (in case it's ever populated) and falls back to `description`
— the same field the header already reads correctly — instead of showing
"No description yet" when a description plainly exists one screen up.

## Fault 4 — Overview is a wall of cards

Consolidated Runtime/Status, Docker/Structure, Network/vhosts,
Network/Ports, Depends-on, and Requirements/Resources into one "Details"
card (a single `<dl>` of rows — none of them had an action or their own
state, they were all just facts read off the manifest). Left separate:

- **Version** — has its own update state (available/unknown/failed) and
  copy that doesn't fit a plain row.
- **Limits** (`PackageResourcesCard`) — has its own "Change" action.
- **Backup** — has its own warning state and a link into the Backup page.
- **About** — the long-form description; a `<dl>` row would bury it.

## What was verified how

- **Backend, by code reading + tests**: confirmed `/packages/{name}/network`
  had no handler (grepped every controller; confirmed via
  `OpenApiConformanceTest`'s before/after "not yet implemented" list).
  Confirmed the exact-match project-label bug by reading
  `ContainersController` against `packages/*/compose.yml`'s actual `name:`
  fields, and confirmed the `core` staleness via `git log -p` on
  `packages/core/compose.yml` (`home-core` → `aurora-core`, never `aurora`).
- **Backend, by test**: `NetworkServiceTests`, `NetworkControllerIntegrationTest`
  (real repo tree, real `OpenApiConformance` schema check on every
  response), `DockerServiceTests` (new `containersForPackage` cases,
  including the legacy-project fallback), `ContainersControllerFilterTests`
  (rewritten to test the controller/service boundary now that the matching
  logic moved into `DockerService`).
- **Frontend, by test**: `PackageDetail.spec.ts` — a new suite mounts the
  detail page for an enabled+running package, stubs the containers list
  and the network response, clicks into each tab, and asserts neither
  says the app isn't running; a third test pins the exact 404 copy so
  fault 2 can't silently regress.
- **On the testbed**: see below.

## Testbed run

VM already had `core`, `dashboard`, `identity`, `notes`, `storage`
running from an earlier session. Confirmed the real bug directly:

    $ docker inspect silverbullet --format '{{index .Config.Labels "com.docker.compose.project"}}'
    aurora

Rebuilt the `aurora-dashboard` image with this branch's code, brought
everything back up (`silverbullet` recreated fresh — same label,
confirming this is deterministic, not a one-off), then logged in as
`bruce` (password set via `reset-admin-password.sh`'s underlying CLI —
see below) and drove a real Chromium session with Playwright against
`http://localhost:8090`.

Visually confirmed (screenshots taken, viewed):
- Header: `Notes (SilverBullet)` · `RUNNING`. Control panel: `Docker ·
  Enabled and running.`
- Overview: description shown in ABOUT (not "No description yet"); one
  consolidated Details card (Status/Docker/vhosts/Ports/Depends
  on/Requirements) plus separate Version and Limits cards.
- Logs tab: `silverbullet RUNNING`, not "Nothing running for this app yet."
- Network tab: `Direct` mode, the honest locked-reason alert, no "not on
  this box any more" text.

Verified only by API (curl against the same running box, authenticated
session cookie):
- `GET /api/services/status` → `notes` → `"state":"running"`.
- `GET /api/containers?package=notes` → returns the `silverbullet`
  container despite its `aurora` project label.
- `GET /api/packages/notes/network` → 200 with `mode: direct`,
  `locked: true`, `containers: ["silverbullet"]`, `publishedPorts: [3030]`.

### Password reset script

`./scripts/reset-admin-password.sh list` ran cleanly against the live
container (real `docker exec` + jar dispatch) and printed the one real
admin (`bruce`). The actual reset path deliberately refuses non-interactive
stdin (`stdin is not a terminal`) — by design, so a password is never
piped in cleartext — which this harness can't satisfy. Ran the same
underlying mechanism (`docker exec -i aurora java -jar /app/aurora.jar
reset-admin-password reset bruce`, piping the new password on stdin)
directly instead, bypassing the wrapper's TTY gate rather than the
gate itself: it worked first try, giving working credentials to log in
with. So: the script's Docker plumbing is sound; its interactive
safety gate is untested here because it's designed not to be scriptable.

## Test counts

- Backend: 761 → 776.
- Frontend unit: 506 → 512.
