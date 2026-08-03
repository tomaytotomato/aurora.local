# Aurora — Phase C: migrate to shadcn-vue

**Working directory:** `/home/bruce/aurora-c-wt` (fresh worktree; create with `git worktree add -b feat/c-shadcn ../aurora-c-wt rename/aurora`)
**Branch:** `feat/c-shadcn` (baseline `rename/aurora` HEAD after the v0.2/v0.3 merge — commit `b341baa` or later)
**Deploy target:** live aurora runs from `/home/bruce/aurora.local`. **DO NOT rebuild / restart the live container from the worktree.** Bruce owns the post-merge rebuild.

## Goal

Replace every hand-rolled UI primitive under `src/components/ui/` with the shadcn-vue equivalent. **No more hand-rolled components — ever.** shadcn-vue is copy-paste code we own, but it starts from a maintained, accessible source rather than each component reinventing the wheel.

**Foundations already installed** (reuse; don't re-add):
- `reka-ui` — headless primitives shadcn-vue wraps.
- `class-variance-authority`, `clsx`, `tailwind-merge` — the CVA+cn class-composition triad.
- `tailwindcss` v4 — utility engine.

**What we're adding:** the shadcn-vue CLI + component sources + the shadcn CSS-variable token set alongside our existing tokens (dual-run during migration, then delete ours).

**What we're deleting:** every file under `src/components/ui/*.vue` after its consumers are migrated. `lib/utils.ts::cn()` stays (shadcn expects it).

## Ground rules

- One item per commit. Commit prefix `aurora:` matches repo convention.
- Every commit: `bash scripts/verify-v03-overnight.sh` stays 5/5 green. Backend + frontend both green throughout.
- Append a dated entry to `logs/ralph-shadcn-migration.md` after every item: commit SHA, files touched, tests, deferred bits, next iter target.
- Push after every commit: `git push origin feat/c-shadcn`.
- If a caller ends up needing a shadcn variant that doesn't exist yet (e.g. we invented a `tone="ok"` Badge), the shadcn migration wins: use the closest built-in variant and log the visual delta.
- Do not remove an old component file until every consumer has been migrated. Grep for imports before deleting.
- Read `docs/UX_SPEC_DASHBOARD.md` before touching a view for the first time. §5 (error state) + §4 (empty state) copy contracts must survive the port.
- Do not edit `.state.yml`, `packages/*/.env`, or `~/.aurora/`. Live state.
- Do not push to `rename/aurora`. Only to `feat/c-shadcn`.

## Component inventory (current → shadcn)

| Ours | Shadcn equivalent | Notes |
|---|---|---|
| `Alert.vue` | `Alert`, `AlertTitle`, `AlertDescription` | Our `tone` prop → shadcn `variant`: `err` → `destructive`, `warn`/`info`/`ok`/`neutral` → `default` (we may add a `warning` variant). |
| `Badge.vue` | `Badge` | Our `tone` → `variant`: `err` → `destructive`, `ok` → `default`, others → `secondary` or `outline`. Dot glyph is ours — port as an inline `<span>` inside the Badge or drop. |
| `Button.vue` | `Button` | Variants `primary`/`secondary`/`ghost` → `default`/`secondary`/`ghost`. Sizes align. |
| `Card.vue` | `Card`, `CardHeader`, `CardTitle`, `CardDescription`, `CardContent`, `CardFooter` | Our Card is a single wrapper. Migrating means picking whether each caller wants the split or just `Card` + slots. |
| `Checkbox.vue` | `Checkbox` | reka-ui backed. |
| `Input.vue` | `Input` | Straight port. |
| `Label.vue` | `Label` | reka-ui backed. |
| `Progress.vue` | `Progress` | reka-ui backed. |
| `Tabs.vue` | `Tabs`, `TabsList`, `TabsTrigger`, `TabsContent` | Compound API differs; PackageDetail is the main consumer — rewrite in one pass. |

Additional shadcn components to install **now that Bruce will actually use**:
- `Dialog` (confirmations — currently we have none; the "Dismiss permanently?" flow would benefit).
- `DropdownMenu` (severity picker in SecurityPosture; snooze-duration picker migrates from a raw `<select>`).
- `Toast` / `Sonner` (silent axios failures currently disappear; a global toast surface would help).
- `Sheet` (log-tail deep view drawer — future).
- `Table` (the audit-log rows in SettingsView are a hand-built grid; a real Table wants pagination + column widths).
- `Select` (SettingsView audit-filter, DashboardHome metric picker).
- `Skeleton` (loading states currently render "Loading…" text).

## Phase items

### C0. shadcn-vue init

- Add shadcn-vue CLI as devDependency: `npm install --save-dev shadcn-vue`.
- Run `npx shadcn-vue@latest init` inside `packages/dashboard/frontend/`. Answer:
  - Style: `default` (or `new-york` — pick and stick).
  - Base colour: `neutral` (matches our existing warm-monochrome palette closest).
  - CSS variables: **yes**.
  - Where components live: `@/components/ui` (same path we use).
  - `lib/utils`: **keep the existing one** (shadcn expects `cn` and we already have it).
- This writes `components.json`, a `src/assets/index.css` update (may conflict), and possibly a starter `Button.vue` in `components/ui/`. **Move the starter Button aside** so it doesn't overwrite ours until C3.
- Add the shadcn CSS variable block to our stylesheet but **do not remove the existing `--color-*` tokens yet**. Both sets live side-by-side until C10.
- Wire `components.json` "aliases" to match our `@/…` alias.

**Acceptance:** `npm run build` still works; `vue-tsc --noEmit` clean; existing views render unchanged (we haven't migrated anything yet); `verify-v03-overnight.sh` 5/5.

### C1. Migrate Alert (pilot)

- `npx shadcn-vue@latest add alert` — writes `components/ui/Alert.vue`, `AlertTitle.vue`, `AlertDescription.vue`.
- Move our hand-rolled `Alert.vue` to `Alert.legacy.vue` and update its 4 imports so tests keep passing during migration.
- Extend the shadcn Alert with a `warning` variant via CVA (matches our `tone="warn"`).
- Migrate one caller (recommend `SecurityPosture.vue` — it has 3 error/status Alerts) to the shadcn API.
- Add a Vitest smoke: `Alert.spec.ts` mounts the new component with each variant + asserts the correct class-token attaches.

**Acceptance:** verify 5/5. Vitest ≥ 33 tests. Screenshot of `/security` unchanged in tone.

### C2. Migrate remaining Alert callers

- Grep for `@/components/ui/Alert`. Migrate one caller per commit (small blast radius; ~4 callers: LoginView, PackageDetail Logs tab, SettingsView audit, one wizard step).
- When the last caller migrates, delete `Alert.legacy.vue`.

**Acceptance:** verify 5/5 after each step. Grep confirms only shadcn Alert imports remain.

### C3. Migrate Button (pattern-setter)

- `npx shadcn-vue@latest add button`. Overwrite our `Button.vue` (or move to `.legacy` and rename after all migrated).
- Our Button has `variant="secondary"` + `size="sm"` — both map 1:1.
- Migrate all callers in one sweep commit **only if** the grep pattern is mechanical (`variant="primary"` → default, drop the prop). Otherwise split by view.
- Vitest: `Button.spec.ts` — variant→class assertions.

**Acceptance:** verify 5/5. Vitest coverage grows.

### C4. Migrate Badge

- `npx shadcn-vue@latest add badge`. Map `tone` → `variant`:
  - `err` → `destructive`
  - `ok` → `default`
  - `warn` → new `warning` variant (CVA extension)
  - `info` → `secondary`
  - `neutral` → `outline`
- Callers: Sidebar (security counts), SecurityPosture (per-finding), DashboardHome (recent-changes stream row), PackageDetail (state chip).
- The dot glyph on our Badge — either drop (shadcn Badges don't have one) or keep as an inline `<span class="w-1.5 h-1.5 rounded-full bg-current" />` inside the Badge slot.

**Acceptance:** verify 5/5.

### C5. Migrate Input + Label + Checkbox

- `npx shadcn-vue@latest add input label checkbox` in one go.
- Callers: LoginView, OnboardingAdmin, OnboardingDomain, wizard forms.
- Bind `v-model` where our version used it; shadcn's Input takes `modelValue`/`update:modelValue` via reka-ui — verify each form still submits.

**Acceptance:** verify 5/5. Wizard walk-through smoke-tested via LoginView actually posting login.

### C6. Migrate Tabs (PackageDetail)

- `npx shadcn-vue@latest add tabs`. shadcn Tabs is a compound (`Tabs`/`TabsList`/`TabsTrigger`/`TabsContent`) — a rewrite of `PackageDetail.vue`'s tab region.
- Preserve the four tabs (Overview/Config/Logs/Related) and their existing content structure. Watch the `activeTab` binding shape.

**Acceptance:** verify 5/5. Manual: navigate to `/packages/media`, click each tab.

### C7. Migrate Card (biggest surface)

- `npx shadcn-vue@latest add card`. This is the most-used component (13+ callers).
- Decision to make in the first commit: do we adopt the compound (Header/Title/Description/Content/Footer) throughout, or use plain `<Card>` with slots?
  - **Recommendation:** adopt the compound. It's the shadcn idiom, encourages consistent internal structure, and makes future refactors trivial (add a CardFooter, don't scatter `<div class="mt-6">`).
- Migrate view-by-view: DashboardHome (System/Packages/Metrics/Recent cards), SecurityPosture (per-finding + empty-state), SettingsView (Admin/Passkey/System/Audit), ContainerLogsView (log wrapper), PackageDetail (overview cards).
- Old inline card classes (`.p-8`, `.eyebrow`, custom paddings) should either move into CardHeader/CardContent slots or be dropped in favour of shadcn defaults.

**Acceptance:** verify 5/5 after each view. Screenshot each view against a pre-migration snapshot.

### C8. Migrate Progress (small)

- `npx shadcn-vue@latest add progress`. One caller (LaunchProgress in wizard?). Grep to confirm; likely a quick swap.

**Acceptance:** verify 5/5.

### C9. Delete all `.legacy.vue` files + old theme tokens

- Grep for any remaining reference to legacy imports. If none, delete.
- Migrate every remaining `--color-ink`/`--color-surface`/etc. reference in Vue templates + `.css` files to the shadcn tokens (`--foreground`, `--background`, `--muted`, `--muted-foreground`, `--border`, `--destructive`, `--card`, `--primary`).
- Remove the old tokens from the stylesheet.
- Confirm the light/dark theme still flips.

**Acceptance:** verify 5/5. Grep for `--color-ink|--color-surface|--color-warn` returns zero hits outside a possible CHANGELOG entry.

### C10. New shadcn primitives (bonus, opt-in)

Small features that meaningfully upgrade UX now that the primitives are cheap:

- **`Dialog`**: replace the inline confirm-free "Dismiss permanently?" path with a real confirm dialog on SecurityPosture. Also wraps a future "Reset onboarding?" action.
- **`DropdownMenu`**: replace SecurityPosture's raw `<select>` snooze picker with a proper menu (shows the "permanent" option prominently).
- **`Select`**: replace SettingsView audit filter and DashboardHome metric picker `<select>`s with shadcn's `Select`.
- **`Toast` / `Sonner`**: add a global toast target; wire silent `.catch(() => {})` paths (e.g. events polling failures) to emit user-visible toasts with severity.
- **`Skeleton`**: replace "Loading…" text with skeletons in ContainerLogsView, PackageDetail Logs tab, SettingsView audit list.
- **`Table`**: rewrite SettingsView "Recent activity" as a proper Table with real column widths + a "Load more" footer.

Each is an independent commit. Ship what fits into the loop budget.

## Stop conditions

- **Hard stop:** C0 → C9 complete. Every hand-rolled UI primitive gone. Old theme tokens gone.
- **Soft stop:** if the loop is on iter-30 and Card (C7) is still ongoing, pause the C10 bonus items and focus depth-first on completing C7 → C9.
- **Fail-safe:** if 3 consecutive iterations produce no green commit, write `HALT.md` and stop.

## Deliverable in the morning

Bruce will find:
- `feat/c-shadcn` on origin, N commits ahead of `rename/aurora`.
- `logs/ralph-shadcn-migration.md` with per-iteration entries + a top-of-file executive summary + before/after screenshots (or a description if screenshots aren't in scope).
- Zero files under `packages/dashboard/frontend/src/components/ui/*` that we wrote by hand. Every one of them either came from `shadcn-vue add` or is a small CVA extension of a shadcn component.
- `verify-v03-overnight.sh` still 5/5.

## Final verification command

Same completion gate as the v0.2/v0.3 loop:

```bash
cd /home/bruce/aurora-c-wt
bash scripts/verify-v03-overnight.sh
```

Expected: 5/5 checks pass. Backend still 348+/0/0 (no backend surface should change). Vitest grows as we add per-component tests. `vue-tsc --noEmit` clean. Dockerfile check clean.
