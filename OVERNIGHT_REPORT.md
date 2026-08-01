# Overnight run report — 2026-07-31 to 2026-08-01

Branch: `rename/aurora`
Baseline commit: `86d911f` (last commit before the overnight session)
Head at report time: `3ff7dfb` + `425d3fd` (docs cherry-pick)

## Timeline

### Scheduled overnight (2026-07-31 ~23:15)

Two async runs were launched:

- Main chain `2ec5fc59-4524-4efb-96ab-bd5a91d58faa`: 6 worker steps + 1 reviewer,
  sequential, all committing to `rename/aurora`.
  1. Build + deploy the in-flight `SystemService` (cpu / disks / gpu) and verify
     `/api/onboarding/env`.
  2. Frontend `OnboardingEnv` types + Welcome three-card row.
  3. Manifest resource-warning schema on `ai` + `media`, `plan()` evaluator,
     `?enabled=` query parameter on the plan endpoint.
  4. `OnboardingPackages` live debounced resource warnings.
  5. Fix `lanIp` bug (docker bridge address instead of the real LAN IP).
  6. Push `rename/aurora`, write `OVERNIGHT_REPORT.md`.
  7. Reviewer diff pass.

- Docs task `84574263-e7d7-4774-82fe-6f8d2b48b7f7`: worktree-isolated on
  `docs/migration-and-v0.2-2026-07-31`, writing `docs/MIGRATION.md` and
  `docs/ONBOARDING_V0.2.md`. Not merged; to be cherry-picked in the morning.

### What actually happened

- Steps 1 through 4 completed cleanly and committed.
- Step 5 (LAN IP fix) died mid-investigation with an Anthropic 429 quota
  exceeded error. The worker had already characterised the problem correctly
  (docker bridge `172.18.0.2` was being returned instead of the host
  `192.168.0.110`, and a naive default-route approach would pick the
  ProtonVPN interface at metric 98) and had identified the fix
  (parse `/host_root/proc/net/fib_trie` for RFC1918 addresses, prefer
  `192.168/16`, then `10/8`, then non-docker `172.16-31`). No commit was
  produced.
- Steps 6 and 7 never ran. No push, no report, no reviewer sign-off.
- The docs task completed and produced commit `cc5abbd` on its worktree branch.

### Resumed 2026-08-01

- LAN IP fix implemented from the worker's notes and committed as `3ff7dfb`.
- Docs commit cherry-picked onto `rename/aurora` as `425d3fd`.
- Branch pushed to `origin/rename/aurora` (new remote branch, upstream set).
- This report written and committed.

## Commits ahead of `origin/main` on this branch

Overnight:

- `5a36c82` aurora: host resource snapshot (cpu/disks/gpu) + onboarding v0.2 backend
- `f5f4106` aurora: welcome screen shows CPU/RAM/disks host snapshot
- `351a4a5` aurora: manifest-driven resource warnings + plan(?enabled=)
- `d6661ca` aurora: live resource warnings on package selection

This morning:

- `3ff7dfb` aurora: detect real LAN IP from /host_root/proc/net/fib_trie
- `425d3fd` docs: MIGRATION.md + ONBOARDING_V0.2.md (cherry-picked from
  `docs/migration-and-v0.2-2026-07-31`)

The two pre-overnight commits `cce1232` and `5d0b081` (hero photos and
full-viewport onboarding background) are also on this branch but predate the
run.

## Verified behaviours

`GET /api/onboarding/env` returns the full host snapshot on this box:

- `cpu`: Intel Core i5-6500T @ 2.50GHz, 4 cores / 4 threads, 1 socket,
  `mhz` 2700, `load1` around 1.2
- `memory`: `MemTotal` 16.6 GB, `MemAvailable` 12.6 GB
- `disks`: one real drive, `/dev/sda1` mounted at `/`, ext4, 458 GB total,
  379 GB free
- `gpu`: `present=false` (Dell OptiPlex, no discrete GPU — correct)
- `lanIp`: `192.168.0.110` (was `172.18.0.2` before the fix)

`GET /api/onboarding/plan?enabled=core,ai` returns the expected manifest-driven
warnings on this box:

- DNS mode is `adguard` but the privacy package (which provides AdGuard Home)
  is not selected.
- Ollama runs CPU-only without a GPU. Anything above a 7B model will be
  painfully slow on this box. Consider `phi3:mini` or `llama3:8b`.

The GPU-absent and RAM-below-threshold rules fire correctly against host
facts (`gpu.present=false`, `MemTotal` 15.5 GB). The CPU-threads and
free-disk-below rules correctly do not fire.

Live package selection warnings in `OnboardingPackages.vue` debounce at
250 ms with a monotonic sequence guard so stale responses cannot overwrite
newer ones. Preview failures log to `console.warn` and do not block
selection.

## Push result

`git push -u origin rename/aurora` succeeded. This was a new remote branch;
upstream is now set. All eight commits ahead of `main` (six overnight/morning
plus the two pre-overnight hero-photo commits) are on the remote.

## Outstanding items

Nine pre-existing dirty frontend files remain uncommitted in the working
tree. They were dirty before the overnight run started and were deliberately
left untouched by both the overnight chain and the morning resume. They
should be triaged before any further onboarding work.

- `packages/dashboard/frontend/src/components/layout/OnboardingShell.vue`
- `packages/dashboard/frontend/src/lib/utils.ts`
- `packages/dashboard/frontend/src/router/index.ts`
- `packages/dashboard/frontend/src/stores/onboarding.ts`
- `packages/dashboard/frontend/src/views/onboarding/OnboardingAdmin.vue`
- `packages/dashboard/frontend/src/views/onboarding/OnboardingDns.vue`
- `packages/dashboard/frontend/src/views/onboarding/OnboardingDomain.vue`
- `packages/dashboard/frontend/src/views/onboarding/OnboardingDone.vue`
- `packages/dashboard/frontend/src/views/onboarding/OnboardingReview.vue`

The overnight reviewer step (step 7 of the main chain) never ran. Any
review findings will land in a follow-up pass.

The docs worktree `~/aurora-docs-wt` on branch
`docs/migration-and-v0.2-2026-07-31` is still linked. Since `425d3fd`
brings the same content onto `rename/aurora`, the worktree can be removed
with `git worktree remove ~/aurora-docs-wt` and the branch deleted.

## Suggested next moves for v0.2

- Run the deferred reviewer pass over the range
  `86d911f..HEAD` on `rename/aurora`.
- Triage the nine dirty frontend files: decide whether they belong to the
  in-flight onboarding refactor, to earlier abandoned work, or should be
  reverted.
- Open a PR for `rename/aurora` against `productionize` once the reviewer
  pass is clean and the dirty tree is resolved.
- Consider a small integration test for `SystemService.detectLanIp()` that
  feeds a fixture `fib_trie` and asserts the RFC1918 preference order, so
  the ProtonVPN / docker-bridge regression cannot come back silently.
- Extend the manifest warnings schema to `home`, `dev`, and `privacy` so the
  live warning UX is not disproportionately loud on `ai` and `media` alone.
