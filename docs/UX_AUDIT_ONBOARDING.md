# UX audit: onboarding welcome and package picker

Scope: `OnboardingPackages.vue`, its carry-through to `PackagesCatalogue.vue` /
`PackageDetail.vue` / `PackagesCore.vue`, and `OnboardingWelcome.vue` plus the
`OnboardingShell.vue` wizard frame. Read-only audit; no application code
changed.

The shape of the problem is the same in both halves of the scope. Aurora
already knows how to disclose consequence and structure — `PackageImpactPanel`,
`PackageResourcesCard`, the honest loading/empty/error states in
`PackagesCatalogue.vue`, the ARIA already built into `Tabs`/`Checkbox`/
`Progress` — but the wizard doesn't reach for any of it. The picker asks for
the single highest-consequence decision in the product (what ports open, what
disk fills, what starts on boot) with less disclosure than the Catalogue gives
for changing one app after the fact. And the two-halves-of-one-page structure
(identity table + resource cards) on Welcome is specified, tested-for, and
persona-tested against a user who has already returned one box over exactly
this kind of spec-sheet framing. Neither screen is broken; both are
under-built relative to what the rest of the app already does.

Findings are ordered by user impact, highest first. "Fix before v0.1 alpha"
items are marked **P0**; the rest are marked **P1** (should fix soon) or
**P2** (taste / polish).

---

## P0 — fix before this goes in front of anyone

### 1. The picker nests a `<button>` inside a `<button>`

**File:** `packages/dashboard/frontend/src/views/onboarding/OnboardingPackages.vue`, lines 172–192.

Each package card is a `<button>` that click-toggles selection, and it wraps
a `<Checkbox>`, which itself renders as `<button role="checkbox">`
(`components/ui/Checkbox.vue`, lines 36–56). That's an interactive element
nested inside another interactive element — invalid HTML content model, and
in practice it produces an ambiguous focus/click target: a mouse click
usually works because the outer handler wins, but a screen reader or a
browser's own accessibility tree has two overlapping controls to reconcile,
and which one gets tabbed to or announced is undefined behaviour, not
graceful degradation.

**Suggestion:** make the checkbox presentational (`aria-hidden`, no
interactive role) inside the card button, or drop the card-level `<button>`
wrapper and let the `Checkbox` itself carry the click handler with the label
wired up via a `<label>`/`for`. Either way, one interactive element per card,
not two.

### 2. The offline fallback catalogue re-introduces the Homepage bug being fixed elsewhere

**File:** `OnboardingPackages.vue`, line 32.

```js
{ name: 'core', category: 'core' as PackageCategory, description: 'Caddy reverse proxy + Homepage. Required.' },
```

The owner has already flagged the `core` package's manifest description as
wrong (Aurora is the dashboard now, not Homepage) and another agent is fixing
it. But this exact wrong sentence is duplicated here, hardcoded, as the
picker's own fallback when `packages.fetchList()` fails or hasn't resolved
yet (`catalogue` computed, lines 92–101, falls back to this array whenever
`packages.list.length === 0`). Fixing the manifest or the API response alone
won't remove the claim — it will just make it resurface exactly when the
backend is slow or unreachable, which is disproportionately likely during
first boot, i.e. exactly when onboarding runs. Anyone fixing the Homepage
claim needs to fix it in both places or the fallback undoes the fix silently
under load.

### 3. The picker's "core" lock doesn't match the rest of the product's definition of core

**Files:** `OnboardingPackages.vue` lines 124, 181, 184 vs.
`packages/dashboard/frontend/src/api/packages.ts` lines 161 and
`packages/dashboard/frontend/src/views/PackagesCore.vue` lines 25–30.

The settled decision names `identity` as core infrastructure that should be
locked in the picker. But `api/packages.ts` already defines the product's
actual core set as three packages, not two:

```js
const CORE_PACKAGES: ReadonlySet<string> = new Set(['core', 'identity', 'storage']);
```

and `PackagesCore.vue`'s own comment confirms it: *"Core (the curated
platform set: core/Caddy, identity/Authelia, storage/Samba) runs the platform
everything else depends on."* `PackageDetail.vue` (line 474) already renders
`storage` with `"Always on — can't be removed."` outside onboarding.

If the in-flight fix locks `core` and `identity` in the picker but not
`storage` — because the settled-decisions list in this task only named
`identity` — a user can deselect Samba during setup, then open **Apps → Core**
five minutes later and find the exact same package labelled non-removable.
That's not a hypothetical edge case; it's the very next screen in the product
after onboarding finishes, contradicting the just-completed wizard in the same
session. Worth confirming `storage` is in scope for the same pill/lock
treatment, not just `identity`.

### 4. No consequence disclosure anywhere in the picker

**Files:** `OnboardingPackages.vue` (whole file) vs.
`packages/dashboard/frontend/src/components/PackageImpactPanel.vue` and
`PackageResourcesCard.vue`.

The card shows a name, a description, and a checkbox. Nothing about ports,
minimum RAM/disk, vhosts, or what else gets started (`depends_on`). Yet
exactly this information already exists, already wired to the manifest, and
is already rendered elsewhere in the product: `PackageDetail.vue`'s "Add app"
confirmation dialog (lines 904–913) shows `PackageImpactPanel` — "Also
starts", "Takes ports", "Adds addresses", "Wants at least" — before a single
app is enabled after onboarding.

The picker lets a user select six or seven packages in one motion, which is a
strictly bigger commitment than the one-app "Add" flow that already gets a
disclosure dialog. Right now, adding one app *after* onboarding is more
carefully disclosed than adding a whole starter stack *during* onboarding.
The live `/plan` preview (`previewWarnings`, lines 21–27, 64–90) partially
covers resource conflicts, but only the hand-written ones (see finding 5) —
not ports, not vhosts, not "this also starts X". A compact per-card summary
line (ports · RAM · "also starts …") or a single rolled-up disclosure above
the Continue button would close most of the gap without turning the picker
into a wall of tables.

### 5. `recommends` (and most of `depends_on`) are parsed from every manifest and then never read

**Files:** `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/domain/Package.java` line 16,
`.../services/PackagesService.java` line 265,
`.../services/OnboardingService.java` (the `plan()` method, roughly lines 458–472).

Every manifest declares `recommends:` (e.g. `packages/dashboard/manifest.yml`
declares `recommends: [identity]` with the comment explaining Aurora's own
admin URL sits behind Authelia forward-auth once `identity` is enabled). It's
parsed into the `Package` record and served over `/api/packages`. But
`OnboardingService.plan()`'s warning generator never reads it. The only two
warnings that exist today are hand-written string comparisons —
`media` + `!privacy`, and `dns=adguard` + `!privacy` — not a general walk of
the schema's own `depends_on`/`recommends` arrays. So the specific example in
this brief ("dashboard recommends identity") produces no warning to the user
at all today; it would need to be hand-added as a third special case, and the
next package with a real recommendation would silently get nothing again
unless someone remembers to hardcode it.

**Suggestion:** the fix isn't a new one-off string for `dashboard`/`identity`
— it's making `plan()` walk `recommends` and `depends_on` generically across
whatever's enabled, the same way it already walks `warnings` generically
(`evaluateWarningCondition`, line ~482). That turns "dependency" into
something the user can act on for every current and future package, not just
the one this task happened to mention.

---

## P1 — should fix soon, not necessarily before an alpha goes out

### 6. The wizard still says "packages"; the rest of the product renamed to "Apps"

**Files:** `OnboardingPackages.vue` lines 145, 220 (h1, Continue button label);
`packages/dashboard/frontend/src/stores/onboarding.ts` line 48
(`STEP_LABELS.packages = 'Packages'`).

`packages/dashboard/docs/STYLEGUIDE.md`'s Terminology section is explicit:
*"The user-facing word is Apps, not 'packages'."* The router carries a dated
comment confirming this was a deliberate 2026-08-06 rename
(`router/index.ts` lines 39–46: *"'Packages' is now 'Apps' in the UI"*), with
`/packages` routes redirecting to `/apps`. The wizard never got the memo: the
step heading, the rail label, and the Continue button ("Continue with N
packages") all still say packages. (`docs/UX_SPEC.md` also still says
"Packages" in its acceptance criteria — P5, R1 — but that spec predates the
rename and should be updated alongside the code, not treated as the reason to
keep the old word.) Whichever term wins, the wizard shouldn't be the one
screen in the product still using the other one.

### 7. The package grid has no responsive breakpoint; the welcome screen's resource grid does

**File:** `OnboardingPackages.vue` line 172 (`class="grid grid-cols-2 gap-3 mb-6"`)
vs. `OnboardingWelcome.vue` line 151 (`class="grid grid-cols-1 md:grid-cols-3 gap-3 mb-8"`).

`OnboardingShell.vue` already collapses the rail into a top bar below 900px
(line 166), acknowledging narrow windows matter. But the content card's own
padding (`px-12 py-14`, `OnboardingShell.vue` line 108) doesn't shrink at that
breakpoint, and the packages grid stays fixed at two columns regardless of
viewport width. At a narrow window, that's roughly 48px of padding on each
side eating into whatever width remains, split two ways with a name, a "Core"
pill, and a two-line description squeezed into each half. The welcome
screen's own resource cards solved this exact problem one file away
(`grid-cols-1 md:grid-cols-3`); the picker should follow the same pattern
(`grid-cols-1 sm:grid-cols-2` or similar).

### 8. A failed catalogue fetch is swallowed silently, exactly the pattern its sibling page was fixed to stop doing

**File:** `OnboardingPackages.vue` lines 48–51:

```js
onMounted(async () => {
  try {
    await packages.fetchList();
  } catch { /* fall through to fallback */ }
```

Compare `PackagesCatalogue.vue` lines 26–34, which deliberately tracks
`loadError` with its own comment explaining why: *"A failed load used to be
swallowed, so the catalogue rendered 'No apps' — indistinguishable from a
genuinely empty view. Track it so the template can offer an honest error
state with a retry."* The picker does the thing that comment says was a bug:
on failure it quietly renders the hardcoded fallback list with no signal that
this isn't the box's real catalogue. Keep the resilience — the picker
shouldn't hard-block if the backend hiccups mid-onboarding — but say so, the
same way the styleguide's state vocabulary (`loading`/`empty`/`error`/
hydrated) already requires elsewhere.

### 9. The fallback catalogue and the real manifest schema disagree about what category `backup` is in

**Files:** `api/packages.ts` lines 5–19 (`PackageCategory` union — no `backup`
member); `packages/backup/manifest.yml` line 9 (`category: backup`);
`OnboardingPackages.vue` line 37 (`{ name: 'backup', category: 'storage' as PackageCategory, ... }`).

The real manifest schema has a `backup` category distinct from `storage`
(confirmed across every manifest: `grep '^category:' packages/*/manifest.yml`
shows `backup`, `storage`, `media`, etc. as siblings). The frontend's
`PackageCategory` type never grew a `backup` member, so the picker's offline
fallback mislabels the Kopia backup package as category `storage` just to
satisfy the type. Net effect: which category tab Backup appears under
depends on whether the live API answered before the fallback kicked in — the
one thing a category filter should never do is disagree with itself depending
on network timing.

### 10. Locking a "Core" card with the native `disabled` attribute removes it from the tab order entirely

**File:** `OnboardingPackages.vue` lines 181, 184
(`:disabled="pkg.name === 'core'"` on both the card `<button>` and the
`Checkbox`).

The owner's decision is that mandatory packages stay visible with a Core
pill specifically *so the user can see what they are getting*, rather than
being hidden. A native `disabled` button is removed from the keyboard tab
order and, in many screen readers' forms mode, skipped entirely — a
keyboard-only or screen-reader user tabbing through the grid will jump clean
over the locked cards and never encounter them, undercutting the stated
reason for keeping them visible in the first place. Worth flagging to
whoever is implementing the Core pill now: use `aria-disabled="true"` with a
no-op click handler (card stays focusable and its label/description/pill get
announced) rather than the `disabled` attribute, which silently deletes it
from anyone navigating by keyboard.

### 11. The progress rail gives no `aria-current` and the progress bar has no accessible name

**File:** `packages/dashboard/frontend/src/components/layout/OnboardingShell.vue`,
lines 66 (`<Progress :value="store.progress" ...>`) and 73–96 (rail step
buttons).

`Progress.vue` already does the right thing for role/value semantics
(`role="progressbar"` + `aria-valuemin/max/now`, added specifically so
"assistive tech announces onboarding progress" per its own comment) — but
without an `aria-label`, a screen reader announces "30%, progress bar" with
no indication of what's 30% complete. Separately, the rail's step buttons
convey "this is the current step" purely through background colour
(`.is-active`, lines 144–147); there's no `aria-current="step"` on the active
button, so the one piece of information the rail exists to carry — *where am
I* — isn't exposed to assistive tech at all beyond linear reading order.
Both are small, mechanical fixes (`aria-label="Onboarding progress"` on the
`Progress` instance; `:aria-current="step.active ? 'step' : undefined"` on
the rail button).

---

## P2 — taste, or genuinely arguable

### 12. Welcome screen: is Kernel + Docker version reassurance, or a spec sheet?

**File:** `OnboardingWelcome.vue`, lines 120–143 (the identity table).

`docs/UX_SPEC.md`'s own persona (Sarah, §1) returned a Synology because its
setup screen asked what a "storage pool" was. The same spec's acceptance
criteria (W1) require Hostname, LAN IP, Distribution, Kernel, and Docker
version to each render on this exact screen. Kernel and Docker version carry
no reassurance value to that persona — they're the two fields on this page
closest to the "storage pool" failure mode the spec elsewhere warns against.
This isn't a code bug (the screen does exactly what its spec asks), but it's
worth raising with whoever owns `UX_SPEC.md`: collapsing Kernel/Docker behind
an "Advanced" disclosure (the same pattern anti-pattern #8 in the spec itself
recommends for jargon) would keep the reassurance value (Hostname, LAN IP,
"yes, Docker's here") without presenting five rows as equally important when
two of them mean nothing to the target user.

### 13. The CPU tile's most prominent figure is also its least reliable one

**File:** `OnboardingWelcome.vue`, lines 153–166.

The CPU card's headline (`font-serif text-lg` — the single largest, most
editorial piece of type on the page) is the CPU model string. `cpuLine`
(cores/threads/MHz — the more reliably-available figure) is demoted to a
secondary `font-mono text-xs` line underneath. On a box where `cpu.model` is
absent — confirmed on the Lima testbed, and plausible on real mini PCs and
SBCs with terse `/proc/cpuinfo` strings — the biggest, most stylised text on
the very first screen a new user sees is a lone em dash. Consider promoting
`cpuLine` to the headline slot when `cpuModel` is null, rather than leaving
the visual anchor of the tile empty.

### 14. Restarting onboarding mid-session may still reproduce the "white-on-white" bug, in a narrower form

**Files:** `OnboardingShell.vue` lines 154–163 (`.content-card`, unconditionally
warm off-white); `packages/dashboard/frontend/src/composables/useTheme.ts`
(theme is applied to `document.documentElement`, a single global element,
and only loaded once `TopBar.vue` mounts — i.e. after the dashboard, not
during onboarding).

This is inferred from reading the code, not confirmed in a browser, so treat
it as "worth checking" rather than a confirmed regression of the
already-fixed defect. The general first-run case is fine: `useTheme.ts` is
only imported by `TopBar.vue`, which only mounts inside `AppShell` — so on a
fresh onboarding run, `data-theme` is never set and everything renders with
the light-theme tokens the content card assumes. But `data-theme` lives on
`<html>`, not on a component, and the shell's own footer says *"You can
restart onboarding any time from Settings"* (line 100). If a user has already
used the dashboard in dark mode (setting `data-theme="dark"` globally) and
then restarts onboarding via an in-app navigation (no full page reload), that
attribute persists across the route change, while `.content-card` stays
hardcoded light and the text inside it keeps using theme-reactive tokens
(`text-foreground` etc., which resolve to near-white under
`[data-theme="dark"]`). Worth a two-minute manual check: toggle dark mode in
the dashboard, then restart onboarding from Settings without a hard refresh.

### 15. Once packages get locked, does a single-card category tab make sense?

**File:** `OnboardingPackages.vue`, lines 103–113 (`categories` computed).

Category tabs are derived directly from whatever categories exist in the
catalogue. Once `identity` (and, per finding 3, presumably `storage`) carry
a permanent Core lock, their category tabs (`Identity`, `Storage`) will
contain exactly one card each, and that card offers no choice at all. That
may be fine — consistency of "every category gets a tab" has its own value —
but it's worth deciding on purpose rather than as a side effect of the pill
work landing. Not a defect; a decision worth naming out loud.

### 16. No live-region announcement when a category filter changes the visible set

**File:** `OnboardingPackages.vue`, lines 164–170, 172–193.

`Tabs.vue`'s keyboard handling and roving tabindex are solid (arrow keys,
Home/End, per WAI-ARIA tab pattern). But switching category re-filters the
card grid below with no `aria-live` announcement of the new count. A sighted
user sees the grid change; a screen-reader user gets silence until they
re-explore the page. Minor relative to the rest of this list, but cheap to
fix (an `aria-live="polite"` region reporting "N apps in {category}" would
cover it).

---

## Summary count

- **P0 (fix before alpha):** 5
- **P1 (fix soon):** 6
- **P2 (taste / worth a decision, not urgent):** 5

16 findings total.
