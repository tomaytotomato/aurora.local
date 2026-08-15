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
