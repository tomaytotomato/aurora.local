# Done-page dashboard status — progress log

## The fault

On the final screen of the first-run wizard, the checklist could report the
dashboard package as "Not started" — with a "Start" button — on the very
screen the dashboard's own container was serving. That is not a cosmetic
bug: it teaches the operator to distrust the one piece of status the
product exists to report, on the last screen of their first experience of
it, and it offers an action ("Start") against something that is already
running.

## Establishing the truth first

Read before assuming, per the brief. Traced the actual data path for the
Done page's living checklist:

`OnboardingDone.vue` → `DoneChecklist.vue` → `GET /api/services/status`
(`StatusController` → `StatusProbeService.snapshot()`), which loops over
`.state.yml`'s `enabled[]` list and, per package, calls
`StatusProbeService.probe(pkg)`. That method reads `probe:` out of the
package's own `manifest.yml` (`PackagesService.readProbe`); if the manifest
declares nothing, it silently defaults to `kind: docker` with the container
name defaulting to **the package name itself**.

`packages/dashboard/manifest.yml` had no `probe:` block at all. So a
`dashboard` row would default to `docker.findByName("dashboard")` — but the
dashboard's real container is named `aurora` (`packages/dashboard/compose.yml`,
`container_name: aurora`). The lookup could never succeed, no matter how
healthy the dashboard was, so the row always reported `not-started`.

This is exactly the same shape of bug `packages/notes/manifest.yml` already
had to fix (container name `silverbullet` vs package name `notes`,
`StatusProbeServiceTests.notes_containerNameIsSilverbullet_notPackageName`)
— except for `dashboard` there is a stronger, *certain* answer available:
if the request is being served at all, the dashboard is definitionally up.
That's precisely the trick `packages/core/manifest.yml` already uses
(`probe: {kind: self, container: aurora, external_url: http://{domain}/}`,
see `PackagesService`'s "B1" comment and
`StatusProbeServiceTests.snapshot_sortsBlockerFirst...`/
`PackagesServiceTests.coreProbeSelfMarksRunningEvenWithoutComposeLabels`).
`core` bundles Caddy + (historically) the dashboard, so its self-probe
already covers the same physical `aurora` container from a different
package name — but nothing gave the *dashboard package itself* the same
treatment.

Why this matters even though `dashboard` isn't normally in `.state.yml`'s
`enabled[]` today (the wizard never selects it — see
`dev/notes/orphan-reap-progress.md`): `PackagesService` already has a
dedicated `INFRASTRUCTURE_PACKAGES = Set.of("dashboard")` guard, specifically
so the Done screen doesn't tell the user to "stop the dashboard" once it
*does* appear enabled — which `dev/notes/orphan-reap-progress.md` and
`dev/notes/launch-selfkill-progress.md` both show does happen on a real box.
Once `dashboard` is enabled, its status has to be honest; that's this
ticket.

## Fix

Added a `probe:` block to `packages/dashboard/manifest.yml`:

```yaml
probe:
  kind: self
  container: aurora
  external_url: http://{domain}/
```

This mirrors `core`'s existing self-probe verbatim in shape. It bypasses the
docker lookup entirely for this package — the one question it can answer
with certainty ("am I serving this request?") replaces a docker lookup that
could only ever be wrong for this specific package.

Left `packages/core/manifest.yml` untouched — it's a different package
(Caddy + the historical core/dashboard bundle) with its own already-tested
self-probe; not in scope and removing/changing it risks reintroducing the
"Packages card shows a Start button for Core" bug the B1 comment describes.

### What the page now offers for an already-running package

No separate change was needed here: `ChecklistItem.vue`'s CTA is already a
pure function of `service.state`. Once the probe correctly reports
`running`, the row automatically renders "Open" (via `open_url`) instead of
"Start" — the fix in the manifest is sufficient to flip the offered action,
because the wrong action was a symptom of the wrong state, not a separate
bug in the CTA logic.

### Genuine uncertainty

`openapi.yaml`'s `ServiceState` enum is fixed at `[running, needs-config,
failed, not-started, starting]` — out of bounds to change (constraint: don't
touch `openapi.yaml`). The existing states already model honest uncertainty
within that contract: a probe timeout returns `starting` with "Still
checking…" (not a guess at `not-started`), and an unexpected exception
returns `failed` with "Aurora could not reach this service." rather than
silently reporting either extreme. For the dashboard specifically there is
no genuine uncertainty to model — a `self` probe is a *certain* fact, not a
guess, so `running` is the correct and only honest answer once it's
configured. The fix leans on the state that already correctly expresses
uncertainty (`starting`) rather than inventing a new one.

## Same inference, checked elsewhere

Audited every package manifest for the same "container defaults to the
package name" trap. Packages **with** an explicit `probe:` block (`core`,
`filebrowser`, `jellyfin`, `notes`, `memos`, `media`, `privacy`, `storage`)
are fine by construction. Packages with **no** `probe:` block at all, whose
compose container name doesn't match the package name, would hit the exact
same false-negative:

| package        | default lookup (wrong) | real container(s)                              |
|----------------|-------------------------|-------------------------------------------------|
| `identity`     | `identity`              | `authelia`                                       |
| `git`          | `git`                   | `forgejo`, `forgejo-runner`                      |
| `photos`       | `photos`                | `immich-server`, `immich-ml`, `immich-redis`, `immich-postgres` |
| `ai`           | `ai`                    | `ollama-cpu` / `ollama-gpu` (profile-gated), `open-webui` |
| `backup`       | `backup`                | `kopia`                                          |
| `dev`          | `dev`                   | `code-server`, `postgres`, `redis`               |
| `documents`    | `documents`             | `paperless`, `paperless-postgres`, `paperless-redis`, `paperless-gotenberg`, `paperless-tika`, `stirling-pdf` |
| `home-automation` | `home-automation`    | `homeassistant`, `mosquitto`, `zigbee2mqtt`      |
| `monitoring`   | `monitoring`            | `prometheus`, `grafana`, `node-exporter`, `cadvisor`, `uptime-kuma` |

`identity` is the one that matters most here: the frontend's own
`isCorePackage()` (`api/packages.ts`) treats `core`, `identity`, `storage`
as the three mandatory/locked packages shown in the wizard — so `identity`
is enabled on effectively every onboarding run, same as `core`, and is
exactly the "a core package that was already running before the wizard ran"
case the brief called out. Its manifest has no `probe:` block, so it
defaults to `docker.findByName("identity")`, which will never find the real
container (`authelia`) — the checklist would report Authelia as
`not-started` regardless of how healthy it actually is.

**Left unfixed, flagged instead.** `identity`/Authelia sits under active
work by other agents in this session (`fix-authelia-boot`,
`fix-authelia-secrets`) — editing its manifest here risked a collision with
work already in flight on that exact package, and it's a single-container
fix (`probe: {kind: docker, container: authelia, external_url:
https://auth.{domain}/}`) that whoever owns that thread can drop in trivially.
The other eight packages are optional, user-selected, and several are
multi-container (which real container should stand for "the package" isn't
obvious for `documents`, `monitoring`, `ai`, or `photos` without checking
which one the health check should actually gate on) — a broader audit than
"the Done page and the status it reports for the dashboard," and not
something to rush through as a side effect of this fix. Recommending a
follow-up ticket to work through the table above one package at a time,
the same way `notes` and now `dashboard` were fixed.

## Tests

Backend (`packages/dashboard/backend`, Java 25 via
`JENV_VERSION=25.0.3 jenv exec mvn test`):

- `StatusProbeServiceTests.dashboardSelfProbe_reportsRunning_regardlessOfDockerLookup` —
  pins the fix at the exact layer `/api/services/status` uses: stubs
  `docker.findByName` to return empty (the precise "container not found"
  shape that caused the bug) and asserts the self-probed `dashboard` row is
  `running` regardless, and that `docker` is never even consulted.
- `StatusProbeServiceTests.dashboardWithoutSelfProbeConfig_wouldWronglyReportNotStarted` —
  documents the bug's mechanism directly: an unconfigured manifest (the
  pre-fix shape) genuinely does report `not-started`.
- `PackagesServiceTests.dashboardProbeSelfMarksRunningEvenWithoutComposeLabels` —
  same assertion one layer up, through `PackagesService.find("dashboard")`,
  using a purpose-built `StateFileService`/`DockerService` mock rather than
  this test class's shared fake-repo `.state.yml` (several other suites,
  e.g. `MdnsAliasServiceTests`, pin that file's exact `enabled: [core,
  media, notes]` set, so it was left untouched).
- `PackagesServiceTests.realDashboardManifestDeclaresSelfProbe` — reads the
  actual `packages/dashboard/manifest.yml` (not a fixture copy) and asserts
  its `probe.kind`/`probe.container`, guarded the same way
  `AutheliaConfigurationInvariantsTests.snapshot_matches_source` is, so a
  future edit to the real file can't silently drift from the fixture-backed
  tests above.

Full suite: `mvn clean test` — 501/501 green (Java 25; an older JDK gives
"class file version 69.0" errors, as documented elsewhere in this repo).

Frontend (`packages/dashboard/frontend`):

- New `DoneChecklist.spec.ts` (the component had zero prior test coverage).
  Mocks `GET /api/services/status` directly rather than going through
  `OnboardingDone.vue`, since `DoneChecklist` sources its rows from that
  endpoint independent of the `enabledPackages` prop:
  - a running `dashboard` row never renders `not-started` or "Not started";
  - it renders "Open", not the `not-started` "Start" CTA;
  - the row appears even when `enabledPackages` (the `toStart`-shaped prop)
    never includes `"dashboard"` — pinning that DoneChecklist must not
    infer state from the launch's package set, only from the live backend
    snapshot;
  - sanity check that a genuinely not-started, unrelated package (`media`)
    still gets the `not-started` state and the "Start" CTA — the fix
    doesn't paper over every row.

`npm run typecheck` — clean. `npm run test:unit` — 461/461 (457 existing +
4 new), up from 457 before this change.

## Files touched

- `packages/dashboard/manifest.yml` — the fix.
- `packages/dashboard/backend/src/test/resources/fake-repo/packages/dashboard/manifest.yml` — new fixture.
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/PackagesServiceTests.java`
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/services/StatusProbeServiceTests.java`
- `packages/dashboard/frontend/src/components/onboarding/DoneChecklist.spec.ts` — new.

Not touched (explicitly out of scope): `PackageDetail.vue`,
`PackagesCatalogue.vue`, `OnboardingPackages.vue`, any picker/catalogue
component, `packages/dashboard/openapi.yaml`, `packages/core/manifest.yml`,
`packages/identity/manifest.yml` (flagged above, not fixed), and the Lima
VM testbed.
