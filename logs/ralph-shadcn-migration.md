# Phase C — shadcn-vue migration log

**Branch:** `feat/c-shadcn` (from `main` @ `be5baf5`)
**Worktree:** `/home/bruce/aurora-c-wt`
**Ralph task:** `~/.ralph/RALPH_TASK_C_SHADCN.md.md` → authoritative spec at `/home/bruce/aurora.local/RALPH_TASK_C_SHADCN.md`
**Verify gate:** `bash scripts/verify-v03-overnight.sh` (must stay 5/5)

## Executive summary (update every ≤8 iters + on final)

- **Status:** C0 shipped. shadcn-vue foundations wired (components.json + dual-run tokens).
- **Commits on feat/c-shadcn since main:** 2 (verify sees 90 since `f9c4406`).
- **Backend tests:** 348 / 0 fails / 0 errors / 0 skipped (unchanged — Phase C is frontend-only).
- **Vitest:** 4 files, 32 tests (baseline; will grow with per-component `.spec.ts` files).
- **vue-tsc:** clean.
- **Dockerfile check:** clean.
- **Items shipped:** 1 / 10 (C0–C9 mandatory) + 0 / 6 (C10 bonus).
- **Deferred / blocked:**
  - `--color-accent` / `--color-accent-foreground` NOT mapped to shadcn semantics in C0. Our existing `--color-accent` is the amber brand accent; shadcn expects `accent` to be a subtle hover surface. Only impacts DropdownMenu/Select hover shading which we don't install until C10. Revisit then — either rename our amber to `--color-brand` (touches ~17 refs) or override the affected shadcn components' CVA.

## Iteration log

### iter-1 — 2026-08-03 — setup

- **SHA:** `7d2e773` (worktree bootstrap commit).
- **Files touched:** worktree created, `node_modules/` populated via docker-run `npm ci`, this log file created.
- **Verify:** 5/5 (88 commits since baseline, backend 348/0/0, vue-tsc clean, vitest 32/32, docker check clean).
- **Deferred:** none.
- **Next iter:** C0 shadcn-vue init.

### iter-2 — 2026-08-03 — C0. shadcn-vue init

- **SHA:** (this commit)
- **Files touched:**
  - `packages/dashboard/frontend/components.json` (new) — shadcn-vue config: new-york style, neutral base, `@/components/ui`, existing `@/lib/utils` preserved (has `cn`).
  - `packages/dashboard/frontend/src/assets/main.css` — added shadcn `--color-background/-foreground/-card/-popover/-primary/-secondary/-muted/-destructive/-border/-input/-ring` (+ `-foreground` pairs) to `@theme` and `[data-theme="dark"]`. Dual-run with existing `--color-canvas/-surface/-ink/-line/-accent/...` — they will be deleted in C9.
  - Ralph stub updated with completion of setup entry.
- **Notes / engineering call:**
  - Did NOT run the interactive `npx shadcn-vue init` CLI — wrote `components.json` directly (deterministic, no docker-tty gymnastics). Subsequent `add <component>` calls read this file.
  - shadcn's `--color-accent` / `--color-accent-foreground` NOT mapped — collides with our amber brand accent. Only impacts C10 primitives (DropdownMenu, Select, Toast highlight bg). Documented in exec summary.
  - shadcn radius uses base `--radius`; we already declare `--radius: 6px`. shadcn components will compute `--radius-sm/md/lg` via `calc(--radius - Npx)`. Leaving as-is.
- **Verify:** 5/5 — 89 commits since baseline (verify runs pre-commit; will be 90 post-commit), backend 348/0/0, vue-tsc clean, vitest 32/32, docker check clean.
- **Deferred:** shadcn `accent` semantics (see exec summary).
- **Next iter:** C1 — `npx shadcn-vue add alert`, move our `Alert.vue` → `Alert.legacy.vue`, extend shadcn Alert with a `warning` variant via CVA, migrate `SecurityPosture.vue` (3 Alert instances) to the shadcn API, add `Alert.spec.ts`.
