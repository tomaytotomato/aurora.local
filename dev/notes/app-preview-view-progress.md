# App detail page — preview vs installed view, progress log

## The brief

Owner reviewed `/apps/photos` (NOT INSTALLED) and found the page incoherent
for anything never installed: Details said "Status: stopped" next to a
NOT INSTALLED badge, Version claimed "Up to date" for an image never
pulled, Backup listed a path as though data already existed there, and
Config/Network/Logs tabs rendered for a package with no container.

## Route and mode decision

One route, `/apps/:name` (unchanged), two modes on the same
`PackageDetail.vue`. Reasoning: a bookmark to `/apps/photos` must keep
working across an install, and the mode switch is a plain reactive flip —
no navigation event, no remount, so a package that installs mid-session
lands on the installed half immediately once the store's `enabled` flag
updates. Two routes would have needed either a redirect after every
lifecycle action or a router guard re-checking on every navigation; a
computed does the same job with no extra moving part.

`lib/packageLifecycle.ts::isInstalledView({ isCore, enabled })` is the
pure function the mode switch is built on — `isCore || enabled`. Core
packages are always the installed half regardless of what their `enabled`
flag says on the wire, matching the existing `isCore || detail.enabled`
precedent already used for the Restart/Update buttons in this file.

## Where the Status/badge contradiction actually came from

`deriveStatusLight()` (running/stopped/starting/unhealthy/not-installed/
unknown) already existed and already drove the badge correctly. The
Details card's Status row never used it — it read
`detail.running ? 'running' : 'stopped'` directly, a raw boolean with no
route through the derived light at all. For a not-installed package that
boolean is `false`, so it printed "stopped" next to a badge that (via
`deriveStatusLight`) correctly said "not installed" — two different,
disagreeing claims computed from two different inputs. Fixed by binding
the row straight to `lightState`, so it can never disagree with the badge
above it, and it now shows starting/unhealthy too instead of collapsing
both into a flat "running".

Structurally, the contradiction is also gone at the root: the Details
card only exists in the installed half now, and the installed half is
only reachable when `isInstalledView` is true — so a not-installed,
non-core app never reaches a card that talks about running/stopped at
all.

## Which tabs exist in preview, and why

None of Overview/Config/Network/Logs/Related render as tabs in preview —
`PackagePreview.vue` is a flat card stack instead. A tab strip with either
zero or one live tab is worse than no tab strip.

- **Logs**: no legitimate preview form. There is nothing running to have
  logs.
- **Network**: the real feature (moving an app onto a VPN gateway) isn't
  buildable pre-install per `dev/notes/detail-page-truth-progress.md`
  already on record, and the live/kill-switch framing ("the gateway is
  down, which means this app has no network **right now**") doesn't
  translate to future tense without inventing a chunk of new copy for a
  fact (whether the app *would* share a gateway's namespace) that
  `PackageImpactPanel`'s "Also starts" row already gestures at via
  `dependsOn`. Decided not to build a network-preview mini-feature.
- **Config**: does have a legitimate preview form, and it's in
  `PackagePreview.vue` — a plain card listing the env vars the manifest
  declares (key, required/secret badges, comment), with no values, no
  live fetch. This is manifest data already on the same
  `GET /packages/{name}` response, so it costs nothing extra and never
  claims a value that doesn't exist yet.

## Version and Backup in preview

**Version**: shows the tag each image would install
(`update.images[].currentTag`, parsed from the compose reference — known
whether or not the image has ever been pulled) and the `pinned` flag.
Deliberately omits `state` (current/available/unknown), `lastCheckedAt`
and `lastUpdatedAt` — those describe a comparison against a running
install, which doesn't exist yet. Confirmed on the mock dev server that
this matters: `mocks/fixtures/updates.ts`'s `photos` entry claims
`state: 'current'`, a `lastUpdatedAt`, is stale, disagreeing with
`photos`'s `enabled: false` in `mocks/fixtures/packages.ts` — exactly the
bug reported. Left that fixture alone (out of scope, and PackagePreview
never reads those fields regardless of what they claim) but it's worth
someone reconciling those two fixtures properly at some point.

**Backup**: no standalone card. Folded into `PackageImpactPanel`'s
existing "Stores data in" row (already present, already used one dialog
over from this page for exactly this). Reusing that row instead of
writing new backup-preview copy satisfies both the reframing the owner
asked for (states where data *will* live, claims no coverage) and the
"reuse rather than write a third version" instruction in one move.

## PackageResourcesCard

Instructed to reuse it, not write a new cost-disclosure surface. Dropped
its old `v-if="detail.enabled || isCore"` gate — it reads the manifest
default + any operator override regardless of install state (confirmed:
`PackageResourcesService.forPackage()` has no docker/running dependency
at all), so it now renders unconditionally in both `PackagePreview` and
the installed Overview. Letting the "Change" button appear pre-install is
deliberate: setting a ceiling in advance of installing is real,
compose.yml reads it at container-create time either way.

Found and fixed a live bug this surfaced on the mock dev server:
`mocks/fixtures/resources.ts`'s `LIVE` map had a hardcoded reading for
`photos` (`{ mem: 1840, cpu: 14 }`) despite `photos` being `enabled: false`
in the packages fixture — a not-installed app showing "1.8 GB of 6 GB,
14% CPU" in Limits, the exact class of fabricated-liveness bug this whole
piece of work exists to stop. Removed it; `photos` now correctly shows
just the ceiling with no usage bar, matching every other not-installed
package in that fixture.

## Backend

No backend files touched. Confirmed `Package.java`/`PackagesService.java`
already have no `vhosts`/`backup`/`sourceUrl`/`homepageUrl`/`envVars`/
`readme` fields at all — those are mock-fixture-only today (a bigger,
separate gap; `detail-page-truth`'s progress log already found the same
for `readme` and deliberately fixed it frontend-only rather than touch the
backend). `PackageImpactPanel`/`PackagePreview` guard every one of those
fields with `v-if`, so they render nothing extra until that gap closes,
same as the pre-existing install-confirm dialog already does today.

## Test counts

- Backend: 776, unchanged (no backend files touched). The `mvn test`
  console's own aggregate line is the number to trust here —
  `surefire-reports/*.txt`'s per-outer-class "Tests run" lines undercount
  badly for any class using `@Nested` (its own report says `Tests run: 0`
  even with the nested classes' tests genuinely running and passing), so
  don't sum those by hand.
- Frontend unit: 512 → 523 (11 new: 3 for `isInstalledView`, 8 in
  `PackageDetail.spec.ts` for the preview/installed split, the Details
  status-light binding, and the not-installed-never-stopped rule).
- `npm run typecheck`: clean.

## Verified how

**Visually**, driving the real mock dev server (`VITE_USE_MOCKS=1 vite`)
with Playwright + screenshots:
- `/apps/photos` (not installed): NOT INSTALLED badge, no tab strip,
  About/Impact/Version/Limits/Configuration cards in the future-tense
  framing described above, no Details/Backup/Version-freshness cards.
- `/apps/media` (installed, `running: false` but a `starting` probe in
  the mock service-status stream): Details' Status row read `starting`,
  matching the STARTING… badge — the exact contradiction class this work
  fixes, caught live rather than only in a stubbed test.
- Full install flow: clicked Install on `/apps/photos`, confirmed the
  `PackageImpactPanel`-based dialog, watched the mock job run, and the
  same page (`/apps/photos`, no navigation) flipped to the installed view
  — tabs appeared, Details read "running", the Backup card appeared with
  the same path `PackagePreview` had shown under "Stores data in", and
  the toast read "Installed — Photos is up and running."

**By test only**: the Config-preview card's exact rendering (no manifest
in the mock fixtures has a `comment` on every field), and the "not
described as stopped anywhere on the page" assertion across every string
on the page rather than just the visible cards.
