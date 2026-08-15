# Plan dependency warnings — progress log

## The problem

`OnboardingService.plan()` parsed `depends_on`/`recommends` off every manifest
via `PackagesService` but never read them. The only warnings it produced came
from two hardcoded string comparisons (`media` without `privacy`, DNS mode
`adguard` without `privacy`). A real hard dependency on any other package
would be silently ignored by the preview even though the real installer
(`scripts/up.sh`) already resolves it correctly.

## Key finding before writing any code

`scripts/lib/manifest.sh::manifest_resolve_deps` (used by both
`scripts/up.sh` and `bootstrap.sh`) already does exactly the resolution this
task asked for, in bash:

- `depends_on` is resolved transitively — hard, auto-added, dependency-first
  order.
- `recommends` is checked against the resolved set and only produces a
  `log_warn` — advisory, never blocks.
- A dependency cycle makes the bash resolver `die` (recursion guard against
  looping forever).
- A `depends_on` entry naming a package with no manifest also makes it `die`
  (the recursive call tries to read that package's own manifest and fails).

This settles the "auto-add or warn" question for `depends_on`: auto-add,
because that's what `up.sh` already does before docker compose ever sees the
list. The bug isn't that the installer does the wrong thing — it's that
`OnboardingService.plan()`'s *preview* didn't reflect it, so the Review
screen could show fewer packages than would actually end up running. Matched
the dashboard's Java resolver to the bash one bullet-for-bullet so the preview
and the real install never disagree.

## Design

- New `OnboardingService.DependencyResolution` record + `resolveDependencies()`:
  white/gray/black DFS over `depends_on`, dependency-first order, cycles
  recorded (not thrown — one broken pair shouldn't crash the whole plan),
  dangling deps (name with no manifest) recorded separately.
- `dependencyWarnings()` renders the hard-dependency side: auto-added
  packages ("X is needed by Y but is not selected — Aurora will turn it on
  for you"), dangling deps and cycles as manifest-bug copy ("...not something
  you did — installing will fail until it's fixed").
- `recommendsWarnings()` renders the soft side, always advisory ("works best
  alongside", "will still work without it").
- `core` is skipped in the generic auto-add loop — the two existing
  dedicated messages ("No packages selected...", "Core is not in the enabled
  set...") already tell that story; a third generic one would just repeat it.
- A cycle participant is excluded from the generic "auto-added" list too —
  it's already covered by the cycle message, and listing it a second way as
  a certain auto-add is misleading once we know the graph is broken.
- `plan()` and `install()` both resolve the full closure and use the same
  resolved set for ports/vhosts/budget calculations and for what gets
  written to `.state.yml` — the thing this task is actually about.

## Status

- [x] Read PACKAGE_CONTRACT.md, manifest.schema.json, every real manifest.
- [x] Found the real hardcoded checks live in `OnboardingService.plan()`
      *and* confirmed the shell scripts already do real resolution
      (`scripts/lib/manifest.sh`, `scripts/up.sh`, `bootstrap.sh`).
- [x] Implemented `resolveDependencies`, `dependencyWarnings`,
      `recommendsWarnings`, `prettyPackageName` in `OnboardingService`.
- [x] Rewired `plan()` to use the resolved set throughout (ports, vhosts,
      per-package manifest warnings, resource budget) instead of the raw
      selection.
- [x] Rewired `install()` to persist the resolved set (not just force-add
      core) and log auto-added dependencies in `applied`.
- [x] `mvn test` green at 684/684 with the implementation in place, before
      adding any new tests.
- [x] Added `OnboardingServiceDependencyResolutionTests` (19 pure-helper
      unit tests: transitive resolution, self-cycle, 2-node cycle, cycle not
      blocking unrelated packages, dangling deps, warning copy text,
      `prettyPackageName`). No filesystem/Spring context — `Package` records
      built in memory since the resolver only needs a `Map<String, Package>`.
- [x] Added `OnboardingPlanDependencyIntegrationTest` (6 tests through the
      real `/api/onboarding/plan` and `/install` HTTP endpoints, real SQLite,
      `AuroraIntegrationTest` harness). Uses the *existing* fake-repo
      `media -> core` / `media -> privacy` for the depends_on/recommends
      cases; writes synthetic `loop-a`/`loop-b`/`broken`/`leaf`/`privacy`
      manifests via `writeRepoFile` for the cycle/dangling/transitive/
      already-satisfied cases, scoped to each test's own repo copy (wiped
      and reseeded before every test) — the shared `fake-repo` fixture used
      by 11 other integration test classes was never edited.
- [x] Full `mvn test`: 684 before this work, 703 after the unit tests,
      709 after the integration tests. All green throughout, including
      `OpenApiConformanceTest` (no openapi.yaml change was needed).
- [x] Commit.

## Real manifests found

All real `packages/*/manifest.yml` only `depends_on: [core]` (or `[]` for
core itself) — no multi-level chains exist today. `recommends` pairs found:
`dashboard→identity`, `filebrowser→storage`, `jellyfin→media`,
`jellyfin→storage`, `media→privacy`, `photos→storage`. None are wrong; the
generic resolver handles all of them without special-casing any one pair
(the previous code only ever handled `media→privacy`, hardcoded).

## OpenAPI

No changes needed. `warnings` (`plan`) and `applied` (`install`) are already
`string[]` in `packages/dashboard/openapi.yaml` — sufficient to carry this
plain-English copy without a schema change.
