# Phase C — shadcn-vue migration log

**Branch:** `feat/c-shadcn` (from `main` @ `be5baf5`)
**Worktree:** `/home/bruce/aurora-c-wt`
**Ralph task:** `~/.ralph/RALPH_TASK_C_SHADCN.md.md` → authoritative spec at `/home/bruce/aurora.local/RALPH_TASK_C_SHADCN.md`
**Verify gate:** `bash scripts/verify-v03-overnight.sh` (must stay 5/5)

## Executive summary (update every ≤8 iters + on final)

- **Status:** iter-1 setup complete. Baseline 5/5 green. Ready to start C0.
- **Commits on feat/c-shadcn since main:** 0 (baseline is 88 commits since `f9c4406`; that number climbs as we ship).
- **Backend tests:** 348 / 0 fails / 0 errors / 0 skipped (unchanged — Phase C is frontend-only).
- **Vitest:** 4 files, 32 tests (baseline; will grow with per-component `.spec.ts` files).
- **vue-tsc:** clean.
- **Dockerfile check:** clean.
- **Items shipped:** 0 / 10 (C0–C9 mandatory) + 0 / 6 (C10 bonus).
- **Deferred / blocked:** none yet.

## Iteration log

### iter-1 — 2026-08-03 — setup

- **SHA:** (no commit; setup only — worktree + node_modules bootstrap)
- **Files touched:** `~/.ralph/RALPH_TASK_C_SHADCN.md.md` (Ralph stub wired to real spec), this log created, `packages/dashboard/frontend/node_modules/` populated.
- **Verify:** 5/5 (88 commits since baseline, backend 348/0/0, vue-tsc clean, vitest 32/32, docker check clean).
- **Deferred:** none.
- **Next iter:** C0 — shadcn-vue init inside `packages/dashboard/frontend/`. Steps:
  1. `npm install --save-dev shadcn-vue` (docker-run node:22-alpine).
  2. `npx shadcn-vue@latest init` with: style `new-york`, base colour `neutral`, CSS variables yes, components at `@/components/ui`, keep existing `cn`.
  3. Save any starter Button.vue that init writes to `Button.shadcn-preview.vue` so it doesn't clobber ours until C3.
  4. Add shadcn CSS vars alongside existing `--color-*` tokens; do NOT remove old tokens.
  5. Commit `aurora: C0 shadcn-vue init (components.json + tokens dual-run)`.
  6. Verify 5/5 → push.
