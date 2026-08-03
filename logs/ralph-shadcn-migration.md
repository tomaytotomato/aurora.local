# Phase C — shadcn-vue migration log

**Branch:** `feat/c-shadcn` (from `main` @ `be5baf5`)
**Worktree:** `/home/bruce/aurora-c-wt`
**Ralph task:** `~/.ralph/RALPH_TASK_C_SHADCN.md.md` → authoritative spec at `/home/bruce/aurora.local/RALPH_TASK_C_SHADCN.md`
**Verify gate:** `bash scripts/verify-v03-overnight.sh` (must stay 5/5)

## Executive summary (update every ≤8 iters + on final)

- **Status:** C1 shipped. Shadcn Alert live in SecurityPosture (pilot); 11 callers still on legacy.
- **Commits on feat/c-shadcn since main:** 3 (verify sees 91 since `f9c4406`).
- **Backend tests:** 348 / 0 fails / 0 errors / 0 skipped (unchanged — Phase C is frontend-only).
- **Vitest:** 5 files, 41 tests (+9 from Alert.spec.ts).
- **vue-tsc:** clean.
- **Dockerfile check:** clean.
- **Items shipped:** 2 / 10 (C0–C9 mandatory) + 0 / 6 (C10 bonus).
- **Deferred / blocked:**
  - `--color-accent` / `--color-accent-foreground` NOT mapped to shadcn semantics in C0. Our existing `--color-accent` is the amber brand accent; shadcn expects `accent` to be a subtle hover surface. Only impacts DropdownMenu/Select hover shading which we don't install until C10. Revisit then — either rename our amber to `--color-brand` (touches ~17 refs) or override the affected shadcn components' CVA.
  - **Visual delta on Alert (C1):** legacy Alert used fully-tinted bg (`bg-warn-bg`, `bg-err-bg`, etc.). New shadcn Alert uses `bg-<variant>/8` (8% color-mix tint) + colored border + colored text. Reads as softer/lower-urgency. If Bruce wants the loud banner back, either bump `/8` → `/15` in `alertVariants.ts` or add tinted-bg tokens.

## Iteration log

### iter-1 — 2026-08-03 — setup

- **SHA:** `7d2e773` (worktree bootstrap commit).
- **Files touched:** worktree created, `node_modules/` populated via docker-run `npm ci`, this log file created.
- **Verify:** 5/5 (88 commits since baseline, backend 348/0/0, vue-tsc clean, vitest 32/32, docker check clean).
- **Deferred:** none.
- **Next iter:** C0 shadcn-vue init.

### iter-2 — 2026-08-03 — C0. shadcn-vue init

- **SHA:** `0925d86`
- **Files touched:**
  - `packages/dashboard/frontend/components.json` (new).
  - `packages/dashboard/frontend/src/assets/main.css` — added shadcn `--color-*` tokens in `@theme` and `[data-theme="dark"]`.
- **Verify:** 5/5 (90 commits since baseline, backend 348/0/0, vue-tsc clean, vitest 32/32, docker check clean).
- **Deferred:** shadcn `accent` semantics.
- **Next iter:** C1 alert migration.

### iter-3 — 2026-08-03 — C1. Migrate Alert (pilot)

- **SHA:** (this commit)
- **Files touched:**
  - `src/components/ui/Alert.vue` — rewritten as shadcn-vue "new-york" Alert.
  - `src/components/ui/AlertTitle.vue` (new).
  - `src/components/ui/AlertDescription.vue` (new).
  - `src/components/ui/alertVariants.ts` (new) — CVA with `default`, `destructive`, `warning`, `info`, `success` (three semantic extensions).
  - `src/components/ui/index.ts` (new) — barrel exports for the shadcn primitives.
  - `src/components/ui/AlertLegacy.vue` — old hand-rolled Alert, renamed from `Alert.vue`. 11 callers still import from here.
  - 12 caller `.vue` files updated: sed-replaced `@/components/ui/Alert.vue` → `@/components/ui/AlertLegacy.vue` so no visual change on unmigrated callers.
  - `src/views/SecurityPosture.vue` — the one Alert on this view migrated to shadcn compound API (`<Alert variant="destructive"><AlertDescription>{err}</AlertDescription></Alert>`). Legacy `Alert` import remains unused now on this file; kept alongside the shadcn import to avoid a second grep-sweep this commit — will drop on final C2 sweep.
  - `src/components/ui/Alert.spec.ts` (new) — 9 tests: variant→class mapping (default/destructive/warning/info/success), AlertTitle renders h5, AlertDescription slot, caller class merge, CVA declares all extended variants.
  - `src/assets/main.css` — added `--color-warning`, `--color-info`, `--color-success` (+ `-foreground` pairs) to `@theme` (light) and `[data-theme="dark"]` (dark).
- **Notes / engineering call:**
  - Adopted shadcn compound API (`<Alert><AlertTitle/><AlertDescription/></Alert>`) throughout the new components. Callers pass a slot instead of a `title` prop — more flexible for future rich content.
  - Visual delta on `destructive`/`warning`/etc.: 8% color-mix tint bg instead of fully tinted `--color-warn-bg`. Reads softer. Documented in exec summary; trivial to bump if Bruce wants the loud look back.
  - Semantic tokens `--color-warning`, `--color-info`, `--color-success` added (+ `-foreground`). These didn't exist in shadcn's default token set — they're our domain-specific extensions and will also be used by Badge in C4.
  - Rename strategy: `Alert.vue` → `AlertLegacy.vue`, import path sed-swap across all 12 callers. Cleaner than the `.legacy.vue` naming in the spec because it works with standard `import X from 'X.vue'` patterns.
- **Verify:** 5/5 — 90 commits (pre-commit; 91 post-commit), backend 348/0/0, vue-tsc clean, vitest **5 files, 41 tests** (+9), docker check clean.
- **Deferred:** SecurityPosture still imports the legacy `Alert` even though it's now unused on this view; C2 will drop that import when it migrates the other callers.
- **Next iter:** C2 — migrate remaining 11 Alert callers to shadcn compound API, then delete `AlertLegacy.vue` + the legacy `--color-warn-bg`/`-err-bg`/`-info-bg`/`-ok-bg` tokens iff no other consumers remain. Order: LoginView (2 Alerts) → ContainerLogsView (2) → SettingsView (2) → PackageDetail (3) → OnboardingAdmin (2) → OnboardingWelcome (2) → OnboardingPackages (2) → OnboardingReview (2) → OnboardingDomain (1) → OnboardingSecrets (1) → OnboardingTls (1). One caller per commit; may batch onboarding views if the diff stays small.

### iter-4 — 2026-08-03 — C2. Migrate remaining Alert callers + delete AlertLegacy

- **SHA:** (this commit)
- **Files touched (11 views + 1 delete):**
  - `src/views/LoginView.vue` (2 Alerts: err→destructive, info→info).
  - `src/views/ContainerLogsView.vue` (2: err→destructive, warn→warning).
  - `src/views/SettingsView.vue` (2: info, err→destructive).
  - `src/views/PackageDetail.vue` (3: err→destructive ×2, info).
  - `src/views/SecurityPosture.vue` (drop redundant legacy import; rename `ShadcnAlert` alias → `Alert`).
  - `src/views/onboarding/OnboardingAdmin.vue` (2: info multi-line, err→destructive).
  - `src/views/onboarding/OnboardingWelcome.vue` (2: err→destructive, warn+title→warning w/ AlertTitle).
  - `src/views/onboarding/OnboardingPackages.vue` (2: err→destructive, warn v-for→warning v-for).
  - `src/views/onboarding/OnboardingReview.vue` (2: warn→warning, warn v-for→warning v-for).
  - `src/views/onboarding/OnboardingDomain.vue` (1: err→destructive).
  - `src/views/onboarding/OnboardingSecrets.vue` (1: info+title→info w/ AlertTitle).
  - `src/views/onboarding/OnboardingTls.vue` (1: info multi-line).
  - `src/components/ui/AlertLegacy.vue` DELETED.
- **Mechanical transform applied:**
  - Import: `import Alert from '@/components/ui/AlertLegacy.vue';` → `import { Alert[, AlertTitle], AlertDescription } from '@/components/ui';`.
  - Body-only Alert: `<Alert tone="X" [attrs]>body</Alert>` → `<Alert variant="X-mapped" [attrs]><AlertDescription>body</AlertDescription></Alert>`.
  - Title Alert: `<Alert tone="X" title="Y" [attrs]>body</Alert>` → `<Alert variant="X-mapped" [attrs]><AlertTitle>Y</AlertTitle><AlertDescription>body</AlertDescription></Alert>`.
  - tone→variant map: `err→destructive`, `warn→warning`, `info→info`, `ok→success`, `neutral→default`. Only err/warn/info used in current callers.
- **Notes:**
  - Batched into one commit because the transform is purely mechanical — no per-view design decision. Blast radius ~20 lines per file, 12 files.
  - Post-sweep grep: zero `AlertLegacy` refs, zero `<Alert tone=` refs in src/**. Remaining `tone=` hits are on Badge (C4 territory).
  - UX_SPEC §5 error copy preserved verbatim on every migration. Titles preserved verbatim.
- **Verify:** 5/5 — 91 commits pre-commit (92 post), backend 348/0/0, vue-tsc clean, vitest 5/41, docker check clean.
- **Deferred:** none. C2 complete.
- **Next iter:** C3 — Button migration. `Button.vue` has variants primary/secondary/ghost + sizes sm/md. shadcn Button has variants default/destructive/outline/secondary/ghost/link + sizes default/sm/lg/icon. Migration: rename our Button.vue → ButtonLegacy.vue, write shadcn Button.vue + buttonVariants.ts. Callers use `variant="primary"` (many) — map to `default` (shadcn's default). `size="sm"` maps 1:1. Batch caller sweep if grep is mechanical.

### iter-5 (2026-08-03) — C3 shadcn Button

**Item:** C3. Migrate Button primitive to shadcn tokens.

**What changed.**
- Extracted CVA to `src/components/ui/buttonVariants.ts` sidecar (mirrors alertVariants).
- Rewrote every variant to shadcn semantic tokens:
  - `primary`  → `bg-primary text-primary-foreground hover:bg-primary/90`
  - `secondary` → `bg-secondary text-secondary-foreground border-border hover:bg-muted`
  - `ghost`    → `text-muted-foreground hover:bg-muted hover:text-foreground`
  - `link`     → `text-foreground hover:underline`
  - `danger`   → `text-destructive border-border hover:bg-destructive/10`
  - `accent`   → `bg-[var(--color-accent)] text-[var(--color-on-accent)]` (brand amber; shadcn `accent` intentionally unmapped per main.css comment).
- Added proper `focus-visible:ring-2 ring-ring ring-offset-2 ring-offset-background` — the old primitive only did `outline-none`, which was an a11y regression waiting to happen once dark mode landed.
- Variant/size **names unchanged** — no caller sweep needed. All 14 caller sites (13 views + ChecklistItem) keep working via public API.
- Barrel exports `Button` + `buttonVariants` from `src/components/ui/index.ts`.
- New `Button.spec.ts` — 12 tests pinning every variant → token contract + size/type/disabled/loading/class-merge/focus-ring behaviour.

**Why the API stayed Aurora-flavoured.** shadcn's canonical variant names are `default/destructive/outline/secondary/ghost/link`. Aurora already used `primary/secondary/ghost/link/danger/accent`, and 30+ callers use those names. The Phase C spec says "migrate to shadcn tokens" — that's a token-level migration, not an API rename. Preserving the names keeps this one-item commit tight and defers a cosmetic rename to a later cleanup (or never — Aurora's names read more clearly for a homelab UI).

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 6 files / 54 tests (32 → 54, +22 from Alert.spec growth + Button.spec.ts). vue-tsc clean. Dockerfile clean.

**Next.** C4 — migrate Badge to shadcn tokens (Sidebar / SecurityPosture / DashboardHome / PackageDetail).

### iter-6 (2026-08-03) — C4 shadcn Badge

**Item:** C4. Migrate Badge primitive to shadcn tokens.

**What changed.**
- Extracted CVA to `src/components/ui/badgeVariants.ts` sidecar (mirrors alertVariants/buttonVariants).
- Rewrote every tone onto shadcn semantic tokens:
  - `ok`      → `bg-success/12 text-success`
  - `warn`    → `bg-warning/12 text-warning`
  - `err`     → `bg-destructive/12 text-destructive`
  - `info`    → `bg-info/12 text-info`
  - `neutral` → `bg-muted text-muted-foreground`
- Tint density = `/12` (not Alert's `/8`) — Badge is a 10pt uppercase pill so needs slightly more density to read at that size. Rationale noted in the badgeVariants.ts header.
- Kept `tone` prop (not `variant`) and the same five values so all six callers (ContainerLogsView, PackagesList, PackageDetail, SecurityPosture, DashboardHome, TopBar) keep working.
- Coloured tones still render the small `bg-current` dot; neutral suppresses it. Switched from `:style="{ backgroundColor: 'currentColor' }"` to the `bg-current` utility for consistency.
- Added `role="status"` and `aria-hidden="true"` on the dot — a11y bump the old primitive lacked.
- Barrel exports `Badge` + `badgeVariants` from `src/components/ui/index.ts`.
- New `Badge.spec.ts` — 9 tests pinning every tone → shadcn token contract + role/dot/class-merge behaviour.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 7 files / 63 tests (54 → 63, +9). vue-tsc clean. Dockerfile clean.

**Next.** C5 — migrate Input + Label + Checkbox to shadcn tokens.

### iter-7 (2026-08-03) — C5 shadcn Input + Label + Checkbox

**Item:** C5. Migrate Input + Label + Checkbox primitives to shadcn tokens.

**What changed.**
- **Input:** `bg-[var(--color-surface)]` → `bg-background`; `border-[var(--color-line)]` → `border-input`; `placeholder:text-[var(--color-ink-4)]` → `placeholder:text-muted-foreground`; invalid → `border-destructive focus-visible:ring-destructive`; disabled → `disabled:bg-muted disabled:text-muted-foreground`. Dropped the ref-tracked `focused` state — replaced by standard `focus-visible:ring-2 ring-ring ring-offset-2` so keyboard users get an affordance before typing. Added `aria-invalid` when the `invalid` prop is set.
- **Label:** `text-[var(--color-ink-2)]` → `text-foreground`; hint → `text-muted-foreground`. Added `peer-disabled:cursor-not-allowed peer-disabled:opacity-70` + `leading-none` to match shadcn convention.
- **Checkbox:** unchecked → `bg-background border-input hover:border-muted-foreground`; checked → `bg-primary border-primary text-primary-foreground`; focus ring → `ring-ring` (was `--color-accent/40`). Kept the `button + role=checkbox + aria-checked` pattern (native `<input type=checkbox>` is hard to style consistently under warm-monochrome). Added `aria-hidden` on the tick svg.
- No CVA sidecars — the three primitives have no variant surface. Standard shadcn-vue keeps them flat too; if a variant appears later (Input `size`), extract at that point.
- Barrel-exports `Input`, `Label`, `Checkbox` from `src/components/ui/index.ts`.
- New `FormPrimitives.spec.ts` — 17 tests pinning token contracts + emit/aria/disabled/class-merge behaviour for all three.

**Callers.** Public API unchanged — all callers (LoginView, SettingsView, OnboardingAdmin, OnboardingDomain, OnboardingDns, OnboardingPackages) keep working via existing props (`v-model`, `invalid`, `disabled`, `for`, `hint`). No caller sweep needed.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 8 files / 80 tests (63 → 80, +17). vue-tsc clean. Dockerfile clean.

**Next.** C6 — migrate Tabs (PackageDetail rewrite).

### iter-8 (2026-08-03) — C6 shadcn Tabs

**Item:** C6. Migrate Tabs primitive to shadcn tokens.

**What changed.**
- Token swap:
  - `border-[var(--color-line)]`  → `border-border` (tablist underline)
  - `text-[var(--color-ink)]`     → `text-foreground` (active trigger)
  - `text-[var(--color-ink-3)]`   → `text-muted-foreground` (inactive)
  - `hover:text-[var(--color-ink-2)]` → `hover:text-foreground`
  - `bg-[var(--color-ink)]`       → `bg-foreground` (underline indicator)
- ARIA tab-pattern bump: **roving tabindex** (`0` on the active trigger, `-1` on the rest) — matches WAI-ARIA Authoring Practices for tabs and keeps keyboard nav sane. Old primitive had no tabindex management so every trigger was in the tab order.
- Added `focus-visible:ring-2 ring-ring ring-offset-2` + `rounded-sm` on the trigger so keyboard focus is visible; old primitive just had `outline-none`.
- Added `aria-hidden="true"` on the underline indicator span.

**Public API unchanged.** `<Tabs v-model :tabs>` + default slot for panels. Two caller files (PackageDetail, OnboardingDns) keep working. The spec's "PackageDetail rewrite" note referred to it being the biggest Tabs consumer — no restructure needed once the primitive migration is token-only.

- Barrel-export `Tabs` from `src/components/ui/index.ts`.
- New `Tabs.spec.ts` — 7 tests pinning tokens on tablist / active / inactive / underline, roving tabindex, click emit, focus ring, slot rendering, class merge.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 9 files / 87 tests (80 → 87, +7). vue-tsc clean. Dockerfile clean.

**Next.** C7 — migrate Card (biggest surface).

### iter-9 (2026-08-03) — C7 shadcn Card

**Item:** C7. Migrate Card primitive to shadcn tokens.

**What changed.**
- Token swap only:
  - `bg-[var(--color-surface)]`  → `bg-card`
  - `text-[var(--color-ink)]`    → `text-card-foreground`
  - `border-[var(--color-line)]` → `border-border`
  - `hover:border-[var(--color-ink-4)]` → `hover:border-muted-foreground`
- Preserved: default-padding p-7 gate, hover transition, opaque-surface behaviour (`text-card-foreground` guards against `.on-photo`'s white cascade over the photoBg canvas).
- **Kept Aurora's flat single-primitive Card** rather than splitting to shadcn's Card / CardHeader / CardTitle / CardDescription / CardContent / CardFooter. Six caller files use the flat surface with inline layout (eyebrow / h3 / body) — that reads well for a dense homelab admin, and splitting would be a bigger API break than the token migration warrants.

**Latent quirk surfaced.** Card.spec.ts revealed that Vue 3 coerces the missing boolean `padded` prop to `false`, so the "p-7 by default" contract has never actually fired for any caller. Every existing `<Card>` site either overrides padding via `class="p-8"` or accepts zero padding (PackageDetail's two overview cards). Not fixed in this commit — scope is TOKEN migration, and switching to `withDefaults({padded: true})` is a behaviour change that would visually shift PackageDetail. Documented in Card.vue header + Card.spec.ts note, added to scratchpad as "C-followup: Card padding default".

- Barrel-export `Card` from `src/components/ui/index.ts`.
- New `Card.spec.ts` — 6 tests: default tokens, opt-in p-7 via `padded=true`, `padded=false` suppresses padding, hover transition, hover-off, class merge.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 10 files / 93 tests (87 → 93, +6). vue-tsc clean. Dockerfile clean.

**Next.** C8 — migrate Progress.

### iter-10 (2026-08-03) — C8 shadcn Progress

**Item:** C8. Migrate Progress primitive to shadcn tokens.

**What changed.**
- Token swap:
  - track  `bg-[var(--color-line-2)]` → `bg-secondary`
  - fill   `bg-[var(--color-ink)]`    → `bg-primary`
- ARIA a11y bump: added `role="progressbar"` + `aria-valuemin/max/now`. Old primitive was two unlabeled divs — assistive tech had no way to announce onboarding progress.
- Added `data-testid="progress-fill"` on the inner fill div so tests can address it without brittle child-of-child selectors (learned the hard way — `div > div` in vue-test-utils' querySelector context matched the outer root, not the inner).
- Public API unchanged: `<Progress :value>`. The one caller (OnboardingShell) keeps working with its `.rail-progress` class prop.
- Barrel-export `Progress` from `src/components/ui/index.ts`.
- New `Progress.spec.ts` — 6 tests: track tokens, fill tokens + width binding, ARIA semantics, negative clamping, over-100 clamping, class merge.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 11 files / 99 tests (93 → 99, +6). vue-tsc clean. Dockerfile clean.

**Milestone.** C1–C8 done. Every legacy `--color-*` reference in `src/components/ui/*.vue` is now a shadcn semantic token. The only remaining Phase C work is C9 (audit + delete stale `.legacy.vue` files if any + gate on unused `--color-*` tokens) and C10 (bonus primitives).

**Next.** C9 — audit `src/components/ui/` for any surviving `.legacy.vue` files (Alert.legacy was already deleted in C2 iter-4), grep-scan the whole `src/` for `--color-canvas/-surface/-ink/-line/-ok/-warn/-err/-info` refs, and either migrate or gate deletion. Old tokens themselves stay in main.css until callers stop needing them.

### iter-11 (2026-08-03) — C9a caller utility sweep

**Item:** C9. Delete `.legacy.vue` + old `--color-*` tokens — split into three sub-commits. **This is C9a: sweep Tailwind utility classes across every caller.**

**Audit findings.**
- No `.legacy.vue` files remain in `src/components/ui/` — Alert.legacy was already deleted in C2 iter-4.
- Pre-sweep caller inventory (grep-scan of `src/views/` + `src/components/`, excluding `src/components/ui/` since those primitives were migrated in C1..C8):
  - 25 files with legacy utility classes
  - 380 total utility hits — top of the list: `text-ink-3` (87), `text-ink-4` (63), `border-line` (61), `text-ink-2` (54), `text-ink` (43), `bg-surface` (21), `hover:text-ink` (17), `bg-surface-2` (16).
- Explicit `var(--color-*)` references and main.css base styles left untouched — those land in C9b.

**Sweep script.** `packages/dashboard/scripts/c9a-utility-sweep.sh` runs a single `sed -E` pipeline per file with the substitutions below. Longest-suffix rules run before bare ones so `text-ink-4` doesn't become `text-foreground-4`; word boundaries (`\b`) prevent accidental matches inside longer identifiers.

    text-ink-4       → text-muted-foreground
    text-ink-3       → text-muted-foreground
    text-ink-2       → text-foreground
    hover:text-ink-2 → hover:text-foreground
    hover:text-ink   → hover:text-foreground
    text-ink         → text-foreground
    hover:border-ink-4 → hover:border-muted-foreground
    border-ink-2     → border-muted-foreground
    border-line-2    → border-border
    border-line      → border-border
    divide-line      → divide-border
    hover:bg-surface-2 → hover:bg-muted
    hover:bg-surface → hover:bg-card
    bg-surface-2     → bg-muted
    bg-surface       → bg-card
    bg-canvas        → bg-background
    text-accent      → text-[var(--color-accent)]   (brand amber preserved via arbitrary value)
    border-accent    → border-[var(--color-accent)] (same)

**UX drift note.** Aurora had four ink levels (ink / ink-2 / ink-3 / ink-4). Shadcn has two (foreground / muted-foreground). Merged mapping: ink & ink-2 → foreground; ink-3 & ink-4 → muted-foreground. One gradient step collapses — visible in DashboardHome subtitles and empty-state hints where ink-3 was mid-gray and ink-4 was very light. Verdict: acceptable for shadcn compliance; if the collapse reads too flat post-rebuild, add a `--color-muted-foreground-2` semi-token and reroute ink-4.

**Files touched.** 25 (11 views + 6 onboarding views + 8 layout/onboarding components). Total 288 substitutions (equal add + delete because every changed line is a token swap, no new lines).

**Residual audit.** Post-sweep grep for the same legacy utility set returns 0 hits in `src/views/` and `src/components/`. `src/components/ui/` UI primitives + spec files still show 0 legacy utilities (they were migrated in C1..C8). `src/assets/main.css` still declares the legacy tokens — deletion lives in C9b once the inline `var(--color-*)` refs across views also migrate.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 11 files / 99 tests. vue-tsc clean. Dockerfile clean.

**Next.** C9b — sweep inline `var(--color-*)` refs (bare `color:` / `background:` / arbitrary values) across the same caller set + `src/assets/main.css` base styles, then delete the legacy token declarations from the @theme block.

### iter-12 (2026-08-03) — C9b inline var(--color-*) sweep

**Item:** C9b — sweep inline `var(--color-*)` refs (arbitrary Tailwind values + raw `var()` in `style=""` / `<style>` / TS strings + main.css base/components layers).

**Sweep script.** `packages/dashboard/scripts/c9b-inline-sweep.sh`. Three `sed -E` passes per file:
1. **Arbitrary-value tint utilities** — `bg-[var(--color-ok-bg)]` → `bg-success/10`, `bg-[var(--color-err-bg)]` → `bg-destructive/10`, `text-[var(--color-warn-fg)]` → `text-warning`, `border-[var(--color-info-fg)]` → `border-info` (+ their /25 /35 /40 opacity companions inherited via the shorthand).
2. **Monochrome arbitrary-value utilities** — `bg-[var(--color-ink)]` → `bg-foreground`, `bg-[var(--color-canvas)]` → `bg-background`, `bg-[var(--color-surface-2)]` → `bg-muted`, `bg-[var(--color-line-2)]` → `bg-secondary`, `text-[var(--color-ink-3)]` → `text-muted-foreground`, etc.
3. **Raw `var(--color-<legacy>)` refs** in style attributes / `<style>` blocks / TS string literals — same shadcn re-routing (`--color-ink` → `--color-foreground`, `--color-line-2` → `--color-border`, `--color-ok-fg` → `--color-success`, etc.). Kept intact: `--color-accent`, `--color-accent-hover`, `--color-on-accent` (brand amber; shadcn's `accent` slot is intentionally unmapped for Aurora).

**Files touched.** 16 (11 views + 5 components) + `src/assets/main.css` `@layer base` + `@layer components`. Total substitutions: ~140.

**Manual fix — MetricChart.** The `var(--color-ink-2, currentColor)` fallback pattern isn't matched by the bare `var(--color-ink-2)` regex (comma inside the parens). Migrated three MetricChart line-stroke refs by hand: `ink-2` → `foreground`, `ink-3` → `muted-foreground`.

**Manual fix — Tabs/Card/Progress migration comments.** The token-migration doc comments in the three primitives (e.g. `bg-[var(--color-surface)] → bg-card`) got both sides of the arrow swapped by the sed pass. Restored the LHS (old token) so the comments still tell the migration story. Sed-first, review-after is a cheap way to catch this class of self-inflicted damage.

**Residual audit.** Post-sweep grep of `var(--color-<legacy>)` across `src/` returns 0 hits outside of:
- `src/assets/main.css` `@layer utilities` block (the hand-rolled `.text-ink { color: var(--color-foreground); }` shim — now dead code; deleted in C9c along with the legacy @theme declarations).
- `src/components/ui/buttonVariants.ts` intentional brand-amber refs (`--color-accent` / `--color-on-accent`).

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 348/0/0. Vitest 11 files / 99 tests. vue-tsc clean. Dockerfile clean.

**Next.** C9c — delete the legacy `--color-canvas/-surface/-ink/-line/-ok/-warn/-err/-info/-on-ink/-ink-hover` declarations from `main.css` `@theme` (both light + dark) + delete the entire `@layer utilities` legacy shim block (`.text-ink`, `.bg-canvas`, etc.). Keep brand-amber tokens (`--color-accent`, `--color-accent-hover`, `--color-on-accent`).
