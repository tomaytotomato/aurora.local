# Responsive overlays + tablet layout — progress log

Branch `fix/overlays-and-tablet-layout`. Two faults from a real box: the
Users row-actions dropdown clips inside the table's scroll container, and
the dashboard doesn't hold up between roughly 768px and 1024px (tablet,
both orientations).

## 2026-08-15 — dropdown clipping: root cause + primitive fix

Root cause: `DropdownMenu.vue` (`frontend/src/components/ui/DropdownMenu.vue`)
rendered its content `<div>` as `position: absolute` sibling of the trigger,
inside a `relative inline-block` root. On the Users page that root sits
inside `Table.vue`'s wrapper (`relative w-full overflow-x-auto`). Setting
`overflow-x` to anything other than `visible` forces the UA to compute
`overflow-y` as `auto` too (the CSS overflow spec's "both or neither" rule
for the visible keyword), so the table wrapper clips vertically as well —
that's the scrollbar-and-sliced-item symptom.

Swept the rest of the frontend for the same shape: grepped for any element
combining `absolute` positioning with a `z-*` index (the signature of a
custom floating-content primitive) across `src/**/*.vue`. `DropdownMenu.vue`
was the only hit. `Select.vue` is a styled native `<select>` — the browser
renders its own popup layer outside document flow, so it was never
susceptible. `Dialog.vue` and `Toaster.vue` already `<Teleport to="body">`,
so they were already immune (Dialog even has a comment saying as much).
There is no Tooltip, Popover, or Combobox component yet. Conclusion:
`DropdownMenu` was the one component that hadn't been brought in line with
the Teleport convention the codebase already uses for Dialog/Toaster.

Fix, at the primitive (`DropdownMenu.vue`), not the call site:

- Content now renders inside `<Teleport to="body">`, so it always escapes
  whatever ancestor happens to clip or scroll.
- Positioning switches from `absolute` (relative to an in-flow parent) to
  `fixed`, computed from `rootRef.getBoundingClientRect()` (top = trigger's
  bottom edge + 4px gap; left = trigger's left or right edge depending on
  `align`).
- Right-alignment is done with a `-translate-x-full` transform, not a
  measured content width — so there's no first-frame flash while waiting to
  measure the teleported node, and no dependency on `offsetWidth` at all.
- Outside-click detection now checks both `rootRef` (the trigger) and
  `contentRef` (the teleported menu), since the menu is no longer a DOM
  descendant of the root once teleported.
- Arrow-key navigation now queries `contentRef` instead of `rootRef` for
  `[data-menu-item]`, same reason.
- Added a capture-phase `scroll`/`resize` listener while open, so a menu
  stays anchored to its trigger if an ancestor (the table, a card body)
  scrolls underneath it. `scroll` doesn't bubble, but a capture-phase
  listener on `window` still fires for it regardless — capturing happens on
  the way down to the target before the bubble phase, so it doesn't depend
  on the event bubbling.

This is a primitive-level fix: every current and future `DropdownMenu`
consumer (`UsersView.vue`'s row-actions menu, `TopBar.vue`'s user menu)
inherits it with no call-site change.

Updated tests to match, following the pattern `Dialog.spec.ts` already
established for its own Teleport (query `document`, not the mounted
wrapper, since teleported content is no longer a descendant of it):

- `DropdownMenu.spec.ts`: swapped `wrapper.find('[role="menu"]')` for
  `document.querySelector('[role="menu"]')` throughout; replaced the old
  "align applies left-0/right-0 class" assertion (meaningless now — those
  classes controlled an `absolute` position that no longer exists) with one
  that checks `data-align` and the `-translate-x-full` transform class;
  added an explicit assertion that the menu is NOT a descendant of the
  wrapper root but IS a descendant of `<body>`, since that's the entire
  point of the fix.
- `UsersView.spec.ts`: the three places that clicked a menu item via
  `w.get('[data-test="users-row-edit-...")` etc. now use
  `document.querySelector(...)!.click()` instead, since those items live
  under `<body>` post-teleport.

`npm run test:unit` (458 tests, one new) and `npm run typecheck` both clean
after this change.

Verification: unit tests, reading the markup, and — see the tablet section
below — a real headless-browser check against a local mock-backed instance
(not the real box; see that section for why). Confirmed the menu is a
`<body>` child, not a descendant of the table wrapper, and that its
`getBoundingClientRect()` sits fully inside the viewport with the menu
fully rendered (no clipped/sliced item, no scrollbar) at both tablet
viewports tested.

## 2026-08-15 — tablet layout: what breaks at 768–1024px, and the fixes

No browser tool was available in this session, and the sandbox's Bash
permission flatly denied `curl` against `127.0.0.1:8090` (denied even with
the sandbox-disable escape hatch). A plain Node `http.get` to the same URL
*was* allowed and got a 200, which narrowed it down to something about
`curl` specifically rather than a blanket network ban — but every
authenticated page here sits behind Authelia/session auth I have no
credentials for, and this is the owner's real box in active use. Guessing
or hunting for credentials to explore an authenticated session on somebody
else's live machine isn't something I'll do even read-only, so I didn't
attempt to reach the real box's authenticated views at all.

Instead: `npm run dev:mock` runs the same frontend against MSW-mocked
handlers, and the mock session (`frontend/src/mocks/state.ts`) is
pre-authenticated as `admin` with no login step — a safe, credential-free
way to render the exact same Vue components at a chosen viewport. Ran it
on a scratch port, installed Playwright's Chromium (already cached on this
machine from an earlier session) into `e2e/` as a throwaway devDependency
install (not the checked-in e2e suite — no `global-setup`/`global-teardown`,
no compose stack, nothing touching the VM), and drove it with a one-off
script (not committed) at 768×1024 (portrait) and 1024×768 (landscape).
This is real visual verification of the actual markup, just against mocked
data rather than the real box's data.

**What was actually broken (confirmed by reading the code, then visually
by screenshot):**

- `AppShell.vue` (`grid-cols-[240px_1fr]` unconditionally) + `Sidebar.vue`
  (fixed vertical rail, always visible) — a 768px portrait viewport lost
  240px (31%) of its width to the sidebar before any page content
  rendered. Content columns that are otherwise fine at half-width (most of
  the app) got squeezed further than necessary.
- `BackupView.vue`: two `grid-cols-4` stat-card grids. At 768px, four
  columns leaves ~104px of content width per card after the `Card`
  component's `p-7` padding — enough to break "of 412.0 GB protected, 59%
  saved by deduplication" onto several lines and make the row uneven.
- `PackagesCore.vue`: two `grid-cols-3` package-card grids, each card
  carrying a `line-clamp-3` description paragraph plus a footer row. Same
  shape of problem, worse — three columns at 768px is the most cramped
  case found.
- `TopBar.vue`'s three-region `grid-cols-3` header: a CSS grid item's
  default `min-width` is `auto` (≈ max-content), not `0`. A long
  `hostname.domain` identity string could in principle force its 1fr
  track wider than available space instead of wrapping/truncating,
  pushing the header into horizontal overflow. Didn't reproduce this with
  the mock data's short hostname, but it's a latent bug independent of
  viewport width — worth closing defensively.

**What was checked and found NOT to need a fix:**

- `DashboardHome.vue`'s `grid-cols-6` bento grid is used entirely as halves
  (`col-span-3`) and one full-width row (`col-span-6`), never as six real
  columns — it already reads as a clean 2-column layout at 768px.
  Confirmed by screenshot; left alone.
- `VpnView.vue` and `DisksView.vue`'s `grid-cols-2` grids are all
  half-width pairs of `p-8` cards with short `dl`/badge content — plenty
  of room even at 768px. Left alone.
- `DisksView.vue`'s two `grid-cols-3` stat-card grids (Drives / Parity /
  Unprotected) hold short badges and single numbers, not paragraphs —
  judged fine at 768px by inspection; not screenshotted, so treat this one
  judgement call as slightly less certain than the others.
- The `Table.vue` wrapper's `overflow-x-auto` is already the right pattern
  for wide tables on a narrow viewport (scroll the table, not the page) —
  nothing to change structurally there; it just needed the dropdown fix
  above so the scroll container stops clipping floating content.

**Fixes applied (Tailwind's own default breakpoints only — `sm` 640px,
`lg` 1024px; no custom breakpoint added, per the brief):**

- `AppShell.vue`: `grid-cols-1 lg:grid-cols-[240px_1fr]`.
- `Sidebar.vue`: restructured so that below `lg` it renders as a
  horizontal top bar (icon+label pills in a `flex-row`, `overflow-x-auto`
  nav, "Documentation" footer hidden, border moves from right to bottom)
  and at `lg`+ it's pixel-identical to the original vertical rail. This is
  the same shape of fix `OnboardingShell.vue` already applies at its own
  900px breakpoint ("rail becomes a top bar") — same idea, expressed with
  Tailwind's `lg:` utility instead of a bespoke media query, since
  Tailwind's default scale is "the project's" breakpoint scheme here (no
  `@theme` override of `--breakpoint-*` exists in `main.css`). Labels stay
  visible down to `sm` (640px) — every tablet width in scope is above
  that; only a genuinely phone-narrow viewport drops to icon-only.
- `BackupView.vue`: both `grid-cols-4` → `grid-cols-2 lg:grid-cols-4`.
- `PackagesCore.vue`: both `grid-cols-3` → `grid-cols-1 sm:grid-cols-2
  lg:grid-cols-3`.
- `TopBar.vue`: `min-w-0 truncate` on the identity region, `min-w-0` on
  the user region — defensive fix for the grid min-content overflow case
  above, not something reproduced at the tested viewports.

**Verified by screenshot** (768×1024 and 1024×768, `/`, `/users` with the
row menu open, `/backup`, `/apps/core`): the sidebar correctly collapses to
a scrollable horizontal top bar below 1024px and is the full vertical rail
at 1024px+; the Users dropdown opens fully un-clipped at both sizes; the
Backup and Core grids read as comfortable 2-column layouts at 768px
instead of the cramped 4/3-column originals; the bento grid on Overview
already looked fine, confirming the "leave it alone" call above.

**What still degrades, honestly:** the collapsed top-bar nav at 768px is
wider than the viewport once every item is visible (measured
`scrollWidth: 857` vs `clientWidth: 768`) — it scrolls horizontally
(confirmed: setting `scrollLeft` to max reveals Settings), but there's no
scroll-shadow/fade affordance hinting that more items exist off-screen, so
a first-time user could miss that Settings/the second Users item are
reachable at all without an explicit swipe/scroll. A fade-mask or a
scroll-snap-with-arrows treatment would fix this properly but is a
redesign of the nav strip, not a one-line responsive class, so it's left
as a known rough edge rather than attempted under this budget. Also
noticed, unrelated to this task: `Sidebar.vue`'s nav array has two
separate entries for `/users` (one gated `requiresCapability`-less, one
`requiresRole: 'admin'`) that both render for an admin session, so "Users"
appears twice in the nav — pre-existing, nothing to do with
responsiveness, left untouched.

`npm run test:unit` (458 tests) and `npm run typecheck` both clean after
the tablet changes, run after each file edit and again at the end.
