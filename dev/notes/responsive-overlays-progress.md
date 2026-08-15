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

Verification: unit tests + reading the markup only. No browser tool was
available in this session and outbound network access (even to
`127.0.0.1:8090`) was denied by the sandbox permission system, so I could
not visually confirm the fix against the real box. Flagging this plainly
rather than asserting it looks right.
