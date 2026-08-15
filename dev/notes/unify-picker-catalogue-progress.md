# Unify picker & catalogue — progress log

## Starting point (facts established before any change)

Read `OnboardingPackages.vue` (the onboarding picker), `PackagesCatalogue.vue`
and `PackagesCore.vue` (the Apps section), `api/packages.ts`, `stores/packages.ts`,
`lib/packageName.ts`, `mocks/fixtures/packages.ts`.

Already shared today (this repo has clearly been through unify passes before):
- `PackagesApi` / `PackageSummary` / `PackageCategory` — one API module, one wire
  type, used by both screens via `usePackagesStore`. The picker is not hitting a
  separate onboarding-only packages endpoint; it calls the same `GET /packages`
  as the catalogue.
- `isCorePackage` / `isRemovable` / `splitByCore` / `splitCatalogue` — one
  authority for "is this package part of the mandatory platform baseline"
  (`core`, `identity`, `storage`), used by the picker (to lock cards) and by
  `PackagesCore.vue` (to split the Apps section into Core vs everything else).
- `packageLabel` / `prettyPackageName`, `dockerStructureFor`, `packageLinks` —
  shared presentation helpers, already reused across catalogue/core/detail.
- `Tabs.vue`, `Card.vue`, `Badge.vue`, `Button.vue`, `Skeleton.vue` — shared
  primitives, no bespoke duplicates.

Confirmed drift (evidence, not just the ticket's claim):
1. **Hardcoded fallback catalogue** in `OnboardingPackages.vue` (a `fallback`
   const with 14 hand-written `{name, category, description}` entries) — used
   only when the real `/packages` fetch hadn't returned yet. Compared every
   fallback description against the real manifest `description:` block and
   every single one has drifted (e.g. fallback media = "Sonarr, Radarr,
   Bazarr, Prowlarr, Seerr, qBittorrent, SABnzbd." vs manifest = "Debrid-first
   (RDTClient) with qBittorrent-behind-gluetun as the local..."). Removed —
   see below.
2. **Title dropped in the picker.** The picker's `catalogue` computed used to
   re-map the store's `PackageSummary[]` down to `{name, category,
   description}`, silently dropping `title`. `packageLabel()` was then called
   on the stripped object, so the picker always showed the slug-prettified
   name and could never show a manifest's real `title` — a second,
   independent way for the picker to show the wrong text even when the
   backend answered correctly. Fixed by not re-shaping the store data at all.
3. **Category label duplicated and buggy.** The picker hand-rolled its own
   slug-to-label formatter for tab labels
   (`c.replace('-', ' ').replace(/\b\w/g, m => m.toUpperCase())`), which
   turned the `ai` category into "Ai" — the acronym map already sitting in
   `lib/packageName.ts` (used for package names) would have given "AI".
   Meanwhile the catalogue and core pages printed `pkg.category` raw
   (`home-automation`, `ai`) with no prettifying at all. Unified onto one
   function: added `categoryLabel` (an alias of the existing
   `prettyPackageName`) in `packageName.ts`, used by the picker's tab labels
   and by the catalogue/core eyebrow.

## Plan for task 2 (Core tab, Auth tab removed)

`isCorePackage()` is already the authority for the mandatory set (`core`,
`identity`, `storage`). The picker's tab grouping did not use it — tabs were
built straight off each package's raw `category`, so `identity` got its own
"Identity" tab. Regrouping: a package that `isCorePackage()` flags renders
under a synthetic `core` tab regardless of its raw category; everything else
keeps its own category tab. This mirrors what `PackagesCore.vue` already does
for the Apps section (`splitByCore`), so the picker and the Apps section now
agree on what "Core" means.

Manifest `category:` fields are **not** changing — `identity`'s manifest still
says `category: identity`, matching `PackagesCore.vue`'s existing precedent
(it also groups by `isCorePackage`/`splitByCore`, not by raw category, and
also leaves manifests alone). This is a presentation-only regrouping.

No tab can be empty: a category only appears in the tab list if at least one
package would render under it (same computed derives both). The Core tab
always has at least `core` itself, since `core` always ships. No package can
disappear: every catalogue package is either mandatory (renders in Core) or
not (renders in its own category) — never neither.

## Done

- `packages/dashboard/frontend/src/lib/packageName.ts` — added `categoryLabel`
  (alias of `prettyPackageName`).
- `packages/dashboard/frontend/src/views/PackagesCatalogue.vue`,
  `PackagesCore.vue` — eyebrow now prints `categoryLabel(pkg.category)`
  instead of the raw slug.
- `packages/dashboard/frontend/src/views/onboarding/OnboardingPackages.vue`:
  - Removed the hardcoded `fallback` catalogue. `catalogue` is now
    `packages.list` directly (no re-shaping, so `title` survives).
  - Added `loadError` + `load()`, mirroring `PackagesCatalogue.vue` /
    `PackagesCore.vue`'s existing loading/retry pattern: a skeleton while the
    fetch is in flight, an `Alert` with a "Try again" button if it fails.
    Stale cached data (if the store already had a list from an earlier visit
    in the same session) stays on screen under a failed-refresh banner rather
    than being blanked.
  - `categories`/`filtered` now group by `isMandatory()` (i.e.
    `isCorePackage()`) first, raw `category` second — see plan above. Tab
    labels go through the same `categoryLabel` as the catalogue/core pages.
- Added `packages/dashboard/frontend/src/lib/packageName.spec.ts` (9 tests)
  and `packages/dashboard/frontend/src/views/onboarding/OnboardingPackages.spec.ts`
  (8 tests): Core-tab grouping, no Identity/Auth tab, no empty tab, identity
  can't vanish from every tab, title-over-slug, skeleton-not-fallback on slow
  fetch, error+retry-not-fallback on failed fetch.
- Test count: 457 → 474 (43 → 45 files). `npm run typecheck` clean throughout.

## Deliberately left separate

- **The card markup itself.** The picker's card is a `role="checkbox"`
  button (single control, locked/mandatory via `aria-disabled`) because
  selecting/deselecting before install is fundamentally a different
  interaction from the catalogue's card, which is a navigation link to
  `PackageDetail.vue` (owned by another agent, not touched here) or an
  external link out to source/docs. Forcing these onto one component would
  need a "am I a checkbox or a link" prop and would make the simpler screen
  (the catalogue) carry complexity that exists only for the picker's benefit.
  Badge treatment, category labelling and the mandatory/"core" concept are
  shared; the interactive shell is not.
- **`mocks/fixtures/packages.ts`** — a third hand-written copy of package
  metadata, used only by MSW for dev/tests. Left alone: its purpose (a
  deterministic, richer fixture set with `enabled`/`running` states and
  READMEs for local dev without a backend) is different from the picker's
  fallback (a resilience measure for a slow real backend), and merging them
  would make the mock data less controllable for its own tests. Flagged in
  the report as a third source of hand-maintained package copy, not touched.

## Backend check (read-only — no backend files changed)

Dispatched a read-only investigation of `packages/dashboard/backend`:
- No duplication: `PackagesController` (`GET /packages`) and
  `OnboardingController`/`OnboardingService` (`GET /onboarding`, `GET
  /onboarding/plan`) all read through the same injected `PackagesService`,
  which parses `packages/*/manifest.yml` once. There is no second
  onboarding-specific catalogue endpoint and no second manifest-parsing code
  path.
- No shape overlap to collapse: `OnboardingDraft`/`PlanWire` only ever carry
  package *names* (`enabled_packages: string[]`, `packages_to_enable:
  string[]`, …); the richer `name`/`category`/`description`/`title` shape is
  served solely by `GET /packages`. The frontend genuinely needs both calls.
- **Real finding, not fixed (reported only):** the backend has no concept of
  `identity` or `storage` being mandatory. `OnboardingService` only ever
  force-adds the single package literally named `core`
  (`OnboardingService.java`, around lines 570–593). The frontend's
  `isCorePackage()` / `CORE_PACKAGES` set (`core`, `identity`, `storage`) is
  a frontend-only policy — the backend would not stop `identity` or
  `storage` being disabled via a direct API call. This is a pre-existing
  model disagreement, not something task 2 introduced (this change makes the
  picker's *tab grouping* agree with `isCorePackage()`, which was already the
  UI-lock authority before this change); flagging it because "drive the
  grouping from isCorePackage()" only strengthens a frontend rule that the
  backend doesn't itself enforce.
