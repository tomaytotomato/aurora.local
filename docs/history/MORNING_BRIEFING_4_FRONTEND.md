# Morning briefing 4 — Frontend audit (dark/light theme + padding)

**Focus:** cross-theme readability audit after Bruce toggled light↔dark three times and kept hitting issues.
**Branch:** `rename/aurora`
**Commits:** `eb50432` → `$HEAD$` (this audit)
**Live:** http://192.168.0.110:8090 (rebuild verified, `bash scripts/verify-iter3.sh` 16/16 green)

---

## 1. Root causes fixed

### 1.1 Aurora logo invisible in dark mode (Sidebar + Login page)

The mark hardcoded its glyph stroke to `#FAF9F6` (near-white). In dark mode
`--color-ink` (the rounded square background) flips to `#f5f4ef` — also near
white. Two near-whites overlaid → glyph gone.

**Fix:** replaced the hardcoded stroke with the inverting token
`var(--color-on-ink)`. Now:

| Theme | rect fill (`--color-ink`) | stroke (`--color-on-ink`) | contrast |
|---|---|---|---|
| light | `#1a1a1a` near-black | `#ffffff` white | high ✓ |
| dark | `#f5f4ef` near-white | `#14120f` near-black | high ✓ |

Files:
- `packages/dashboard/frontend/src/components/layout/Sidebar.vue`
- `packages/dashboard/frontend/src/views/LoginView.vue`

`OnboardingShell.vue` was intentionally left alone — its logo uses
`rgba(255,255,255,0.92)` because it sits on the always-dark aurora photo
in the wizard chrome regardless of theme.

### 1.2 "storage" / "privacy" / "notes" package names invisible in light mode

Bruce saw "Not started**storage**" with the package name unreadable.

Root cause: `DashboardHome.vue` wraps the DoneChecklist section in
`class="on-photo"` (introduced iter-3 for readability over the aurora
photo). The previous `.on-photo` rule cascaded `color: white` to ALL
descendants including the ChecklistItem `<li>`. The li has
`bg-[var(--color-surface)]` (white in light mode) — so the package-name
`<span>` inherited white, on white bg → invisible.

**Fix, layered:**

1. **Tightened `.on-photo` scope** in `main.css` — now selects only DIRECT
   text and direct-child `h1..h4` + `.eyebrow`, not deep-descendants. The
   cascade stops at the first opaque surface.
2. **Explicit `text-ink` on Card component** — the canonical opaque
   surface now declares its own color context.
3. **Explicit `text-ink` on ChecklistItem `<li>`** — every checklist row
   likewise establishes its own token color.

### 1.3 /packages and /settings feel "padding-less"

`.content` had `padding-inline: 2rem` (32px). On mid-size displays where
`max-width: 72rem` shrinks to fit, the `margin-inline: auto` gets 0
remaining space, so total horizontal padding is only 32px. Feels flush.

**Fix:**
- `.content` padding-inline `2rem` → `2.5rem` (40px each side).
- `Card.vue` default padding `p-6` (24px) → `p-7` (28px) so cards on
  Packages/Settings match DashboardHome's `p-8` bento rhythm more
  closely without doubling up.

### 1.4 Tailwind version

Latest: `tailwindcss@4.3.3`. Currently installed: `4.3.3`. **No bump
needed.** `package.json` reads `^4.0.0` which correctly resolves to the
latest 4.x on `npm install`.

---

## 2. Full audit — grepped and cleared

Searched for every failure-mode class across `frontend/src/`:

| Pattern | Result |
|---|---|
| `text-white`, `bg-black` (outside dark-chrome files) | Only `AppShell` footer + `DashboardHome` subtitle when `photoBg=true`. Both intentional (dark aurora photo below content). |
| `text-black`, `bg-white` (outside…) | 0 hits. |
| Hardcoded pastel scales (`bg-red-*`, `bg-emerald-*`, `bg-neutral-[0-9]+`, etc.) | 0 remaining after prior iter-3 sweep. All migrated to `--color-{ok,warn,err,info,neutral}-{bg,fg}` tokens. |
| Hardcoded hex colors outside token blocks / dark chrome / SVGs | 0 remaining. The `Sidebar.vue` + `LoginView.vue` SVGs now use `var(--color-on-ink)`; `AuroraBackground.vue` + `OnboardingShell.vue` + `AuroraHero.vue` keep their hex because they are always dark chrome. |
| `bg-surface-2/[0-9]` opacity fractions | Kept — all sit inside `OnboardingShell` (always-dark chrome) or the `LaunchProgress` panel; letting the dark shell bleed through is the intended effect. |

---

## 3. Bundle-grep evidence (post-deploy)

Aggregated bundle: `index.html` + 25 lazy-loaded chunks pulled from
http://192.168.0.110:8090 after `docker restart aurora`.

```
=== Logo SVG uses --color-on-ink stroke?
1     ← present
=== #FAF9F6 gone from logo?
0     ← removed
=== .on-photo scoped to > child now?
.on-photo>div
.on-photo>div>h1  .on-photo>div>h2  .on-photo>div>h3  .on-photo>div>h4
.on-photo>.eyebrow
.on-photo>h1  .on-photo>h2  .on-photo>h3  .on-photo>h4
=== ChecklistItem li has text-ink?
border border-line rounded-lg p-4 bg-[var(--color-surface)] text-ink
=== Card has text-[var(--color-ink)]?
bg-[var(--color-surface)] text-[var(--color-ink)] border border-[var(--color-line)]
=== .content padding-inline 2.5rem?
.content{max-width:var(--content-max);margin-inline:auto;padding-inline:2.5rem}
=== Card default p-7?
padded!==!1&&"p-7
```

Every fix present on the wire.

---

## 4. Verification

- `npx vue-tsc --noEmit` → exit 0
- Backend `mvn test` → **99/99 green** (unchanged; only frontend touched)
- `bash scripts/verify-iter3.sh` → **16/16 green** (17th backend check gated on `VERIFY_BUILD=1`; runs green when enabled)
- Live `curl http://192.168.0.110:8090/api/health` → 200

---

## 5. Changed files

- `packages/dashboard/frontend/src/assets/main.css` — `.on-photo` scope tightened; `.content` padding-inline bumped 2→2.5rem; added `.page` helper.
- `packages/dashboard/frontend/src/components/ui/Card.vue` — explicit `text-[var(--color-ink)]`; default padding `p-6` → `p-7`.
- `packages/dashboard/frontend/src/components/layout/Sidebar.vue` — logo stroke → `var(--color-on-ink)`.
- `packages/dashboard/frontend/src/components/onboarding/ChecklistItem.vue` — explicit `text-ink` on `<li>`.
- `packages/dashboard/frontend/src/views/LoginView.vue` — logo stroke → `var(--color-on-ink)`.

---

## 6. What Bruce should try next

Toggle sun/moon in TopBar. Walk this path:

1. `/login` — logo should read cleanly in both themes.
2. Sidebar — Aurora logo top-left readable in both themes; nav items still work.
3. `/` (DashboardHome, photoBg on) — light mode: h1/eyebrows/subtitle over aurora photo are white and readable; dark mode same. ChecklistItem rows are opaque white in light / opaque dark in dark, with dark/light text inside each row respectively.
4. `/packages` — noticeable horizontal padding away from sidebar; Card grid has more breathing room (each card 28px inside).
5. `/settings` — same horizontal padding; account/passkey/metadata cards match.

If any theme flip still shows unreadable copy, screenshot + route + which mode, and I'll iterate — but every explicit failure mode Bruce named plus a full grep-audit is now clean.

---

*Baseline for this audit:* `eb50432`. *HEAD after fix:* `$HEAD_AFTER_COMMIT$`.
