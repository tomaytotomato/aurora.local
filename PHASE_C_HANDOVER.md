# Phase C handover — shadcn-vue migration

**Written:** 2026-08-03 by the session that shipped the v0.2/v0.3 overnight Ralph loop.
**Intended reader:** a fresh Pi session tasked with executing Phase C.
**Read this first**, then `RALPH_TASK_C_SHADCN.md`.

---

## TL;DR

- Aurora is a Vue 3.5 + Vite + Spring Boot 4 admin plane for a homelab box (`aurora.local`).
- Overnight Ralph loop just shipped 77 commits closing v0.2 (A1–A8) + v0.3 (B1–B4) + every deferred followup. Merged into `rename/aurora` and rebuilt live; aurora container is healthy at `http://127.0.0.1:8090` (image `aurora-dashboard:0.1.0`).
- Bruce's next ask: **stop hand-rolling components; migrate to shadcn-vue**. Task file `RALPH_TASK_C_SHADCN.md` is committed on `rename/aurora` (commit `791e90b`).
- Your job: execute Phase C in a new Ralph loop, one item per commit, keeping `bash scripts/verify-v03-overnight.sh` at 5/5 the whole way.

## Repo layout

- **`/home/bruce/aurora.local`** — canonical repo. Branch `rename/aurora` is the merge target. Live aurora runs from here (`packages/dashboard/compose.yml`). **Do not build or restart from here during the loop** — Bruce owns post-merge rebuilds.
- **`/home/bruce/aurora-v02-wt`** — worktree from the just-finished overnight loop, branch `feat/v0.2-overnight`. Can be deleted (`git worktree remove /home/bruce/aurora-v02-wt`) once you're confident everything is merged.
- **`/home/bruce/aurora-c-wt`** — **your worktree**. Create it as your first step:
  ```bash
  cd /home/bruce/aurora.local
  git worktree add -b feat/c-shadcn /home/bruce/aurora-c-wt rename/aurora
  cd /home/bruce/aurora-c-wt
  ```

## Setup checklist for the new session

1. Confirm `rename/aurora` includes the overnight merge:
   ```bash
   cd /home/bruce/aurora.local
   git log --oneline -5   # top should be 791e90b or later; look for b341baa merge commit
   ```
2. Create the Phase C worktree (see above).
3. In the new worktree, sanity-check the verify script passes on the baseline **before** touching anything:
   ```bash
   cd /home/bruce/aurora-c-wt
   bash scripts/verify-v03-overnight.sh
   ```
   Expected: 5/5 green. If it fails, stop and investigate — do not start the migration on a red tree.
4. Read `RALPH_TASK_C_SHADCN.md` from cover to cover. It has all the acceptance criteria + component mapping table + phase items C0–C10.
5. Confirm shadcn-vue foundations are already installed (they are, from the overnight loop):
   ```bash
   cd packages/dashboard/frontend
   grep -E '"reka-ui"|"class-variance-authority"|"clsx"|"tailwind-merge"|"tailwindcss"' package.json
   ```
   Should return five hits. Foundations reused; **do not reinstall these**.

## What's on the file system

**Existing hand-rolled components to replace** (in `packages/dashboard/frontend/src/components/ui/`):
- `Alert.vue`, `Badge.vue`, `Button.vue`, `Card.vue`, `Checkbox.vue`, `Input.vue`, `Label.vue`, `Progress.vue`, `Tabs.vue`.

**Existing consumers** (18 files import from `@/components/ui/`):
- All views: `LoginView`, `PackagesList`, `PackageDetail`, `DashboardHome`, `SecurityPosture`, `SettingsView`, `ContainerLogsView`, wizard steps under `views/onboarding/*`.
- `DoneChecklist`, `Sidebar`, `ReachInfo` (component-side).

**Existing `lib/*` modules extracted last iter** (iters 35–37, keep unchanged, they're shadcn-friendly):
- `lib/severity.ts` — findings tone helpers.
- `lib/container-events.ts` — buffer arithmetic.
- `lib/http-error-copy.ts` — §5 error copy mapper.
- `lib/utils.ts::cn()` — **already the shadcn-shape cn**. shadcn-vue init should NOT overwrite it.

**Existing tokens** (in `src/assets/*.css` — grep to find; they're `--color-ink`, `--color-surface`, `--color-line`, `--color-warn-bg/fg`, etc.). **Do not delete during C0–C8** — dual-run alongside shadcn tokens; delete only in C9.

**Verify script:** `scripts/verify-v03-overnight.sh` — five checks (commits since baseline / backend mvn test expecting ≥348 / vue-tsc / vitest / docker build --check). This is your quality gate. Every commit must leave it 5/5. If a check goes red, fix or revert; do not push a red commit.

## Critical safety rails (same as the v0.2/v0.3 loop)

- **Work only in `/home/bruce/aurora-c-wt`.** Live aurora lives at `/home/bruce/aurora.local` — do not touch it.
- **Do not rebuild or restart docker containers.** Bruce will do that after merge.
- **Do not push to `rename/aurora`.** Only to `feat/c-shadcn`.
- **Never edit `.state.yml`, `packages/*/.env`, or `~/.aurora/`.** Live state.
- **Never edit `packages/dashboard/frontend/node_modules/` directly.** Use npm.

## Practical tips learned in the overnight loop

- **Docker-run everything.** No host JDK or host Node needed. The verify script uses `maven:3.9-eclipse-temurin-25-alpine` and `node:22-alpine` — replicate that pattern. First run pulls images (~2 min); subsequent runs are cached.
- **BusyBox wget in Alpine does NOT support `--https-only`.** If you touch the Dockerfile, don't add flags without checking busybox docs.
- **Vitest is bind-mounted.** The `frontend/node_modules/` survives docker-run reboots. Adding a new devDependency requires one docker-run `npm install`; subsequent `npm run test:unit` runs are fast.
- **`git status` inside the worktree** shows working state independently from the live repo.
- **Small blast radius.** One item per commit. The overnight loop's 77 commits averaged ~30–200 lines each. Nothing over ~500 lines.
- **Log every iter to `logs/ralph-shadcn-migration.md`** with SHA + files + verify result + deferred bullets + next-iter target. The overnight loop's `logs/ralph-overnight-v02.md` is a template you can study.
- **Refresh baseline numbers in three places whenever the verify script's expected floor moves:** `logs/ralph-shadcn-migration.md` executive summary, `RALPH_TASK_C_SHADCN.md` expected-output block, `scripts/verify-v03-overnight.sh` cached-expectation strings. They drift instantly if you forget.
- **Executive summary at the top of the log.** Update it at least every 8 iters + on final. Bruce's morning read starts there.

## Component mapping quick reference

| Ours | Shadcn | Prop mapping |
|---|---|---|
| `Alert tone=err\|warn\|ok\|info\|neutral` | `Alert variant=default\|destructive` (+ add `warning`, `success` via CVA) | `err→destructive`, `warn→warning` (CVA), `ok→success` (CVA), `info→default`, `neutral→default` |
| `Badge tone=…` | `Badge variant=default\|secondary\|destructive\|outline` (+ add `warning`) | `err→destructive`, `ok→default`, `warn→warning` (CVA), `info→secondary`, `neutral→outline` |
| `Button variant=primary\|secondary\|ghost size=sm\|md` | `Button variant=default\|destructive\|outline\|secondary\|ghost\|link size=default\|sm\|lg\|icon` | `primary→default`, sizes align |
| `Card` (single wrapper) | `Card + CardHeader + CardTitle + CardDescription + CardContent + CardFooter` | Compound API rewrite per caller |
| `Checkbox`, `Input`, `Label`, `Progress` | Same names, reka-ui backed | `v-model` works via `modelValue`/`update:modelValue` |
| `Tabs` (single wrapper prop) | `Tabs + TabsList + TabsTrigger + TabsContent` | Compound rewrite, PackageDetail is the main consumer |

Full mapping + acceptance criteria per item are in `RALPH_TASK_C_SHADCN.md`.

## Ralph loop kick-off command

```bash
cd /home/bruce/aurora-c-wt
# Ensure your Ralph task file is loaded from RALPH_TASK_C_SHADCN.md
# Suggested budget: 30-40 iterations for C0-C9, add 5-10 more for C10 bonus items.
```

The task file's "Stop conditions" section defines soft/hard stops. Your first iter should be C0 (shadcn-vue init), your last mandatory iter is C9 (delete legacy + token migration). C10 is bonus if the budget permits.

## Verification command (record for the completion gate)

Same script as the v0.2/v0.3 loop:

```bash
cd /home/bruce/aurora-c-wt
bash scripts/verify-v03-overnight.sh
```

Expected on any HEAD during Phase C: 5/5 checks pass. Backend test count should stay ≥348 (no backend surface changes in this phase). Vitest test count should GROW (per-component tests get added as you migrate). vue-tsc + Dockerfile check + commit count all trivially pass.

## What Bruce values (from prior loops)

- Honest commit bodies with verification numbers, deferred items, refs to prior commits.
- Small, reviewable diffs.
- Never a `DECISION_NEEDED.md` unless something is genuinely blocked. Document engineering calls in the commit body.
- Preserve the §4 (empty state) and §5 (error state) UX copy contracts. If a shadcn primitive doesn't have a slot for a specific piece of copy, add it — don't drop the copy.

## When you're done

- Verify 5/5 one final time.
- Update the executive summary at the top of `logs/ralph-shadcn-migration.md` — commit count, before/after test totals, remaining backlog if any.
- Push, then respond with `<promise>COMPLETE</promise>`.

Good luck.
