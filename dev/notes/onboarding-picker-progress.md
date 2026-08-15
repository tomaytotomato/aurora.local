# Onboarding picker — progress log

Three pieces of owner feedback from walking the first-run wizard on a
real box. Tracked here so a stall doesn't lose the reasoning.

## 1. Core package still claims Homepage is included

Homepage was the dashboard before Aurora's own admin UI existed; it is
no longer needed because Aurora *is* the dashboard now.

**Is Homepage still deployed? No.**

- `packages/core/compose.yml` has the Homepage service commented out
  entirely, with a note: "Homepage retired in v0.1 — Aurora is the
  single landing page."
- `packages/core/caddy/Caddyfile` has no Homepage vhost. The apex
  `$DOMAIN` vhost reverse-proxies straight to `aurora:8090` (the
  dashboard container), with its own comment confirming the same
  retirement.
- `packages/dashboard/caddy.snippet` is deliberately empty — Aurora is
  served at the apex, not a separate `admin.$DOMAIN` (a manifest
  post-install note still said `admin.$DOMAIN`; fixed as part of this
  change since it was actively wrong).

**What's still there but unused (did not touch — see constraints):**

- `scripts/lib/render.sh`'s `render_homepage_services()` still runs on
  every `up.sh` and writes `packages/core/homepage/config/services.yaml`
  from `services.base.yaml` + every enabled package's `homepage.yml`
  fragment. Nothing reads that file — no Homepage container mounts it.
- Every package still ships a `homepage.yml` fragment (identity, media,
  jellyfin, monitoring, backup, photos, notes, memos, git, ai, dev,
  documents, home-automation, storage's peers, etc.) and the package
  contract (`docs/PACKAGE_CONTRACT.md`) still documents `homepage.yml`
  as a recognised optional file.
- `packages/core/homepage/config/*.yaml` (settings, docker, bookmarks,
  widgets, services.base) are all still checked in.

This is pure dead weight, not a running service, so removing it isn't
"deleting something the owner may still be using" in the risky sense —
but it's a real cleanup (touches every package's file tree plus
render.sh plus the contract doc) and wasn't asked for here. Flagging
for a follow-up ticket rather than doing it as a drive-by.

**Copy fixed:** `packages/core/manifest.yml` (description +
`post_install_notes`, including the stray `admin.$DOMAIN` URL),
`packages/core/README.md`, root `README.md` package table,
`group_vars/all.example.yml`, `Essence.md`, `packages/dashboard/manifest.yml`
(also fixed its own stray `admin.$DOMAIN` post-install URL),
`packages/dashboard/README.md`, `packages/dashboard/frontend/README.md`,
`packages/dashboard/frontend/src/mocks/fixtures/packages.ts`,
`docs/ARCHITECTURE.md` (mermaid diagram — dropped the Homepage node and
edge, annotated the still-real-but-pointless render step),
`docs/DASHBOARD_BRIEF.md` and `packages/dashboard/docs/DASHBOARD_COMPETITIVE_ANALYSIS.md`
(both predate the retirement; added corrections rather than rewriting
their whole arguments).

Commit: "docs: Aurora is the dashboard now, Homepage isn't" (change 1).

## 2 + 3. Picker + Auth step

**Design update from the coordinator, mid-task:** mandatory packages
(`core`, and `identity` once it's mandatory) should be shown in the
picker with a "Core" pill, not hidden. Locked, not invisible. This
supersedes the original brief's "remove core from the list" framing.
Reusing `Badge` (`tone="info"`) with the text `core`, matching the
exact convention already used on `PackageDetail.vue` (`<Badge v-if=
"isCore" tone="info">core</Badge>`) rather than inventing a new pill.

### Change 2: core in the picker, locked not hidden

A parallel UX audit landed three more findings in the same file while
this was in flight; folded in rather than deferred, per the
coordinator:

1. **`storage` is core too.** `api/packages.ts` already has
   `isCorePackage()` (`core`, `identity`, `storage` — see
   `PackagesCore.vue`'s own comment) as the single source of truth for
   "can't be added/removed from the box", used by the Catalogue and
   Package Detail pages. The picker's own `name === 'core'` check was
   the odd one out and the actual bug: an operator could deselect Samba
   in the wizard and then find it relabelled "Always on — can't be
   removed" one screen later. Onboarding now calls `isCorePackage()`
   instead of keeping a second list, so it can't drift from the other
   two pages again.
2. **The Homepage fix didn't reach the fallback catalogue.**
   `OnboardingPackages.vue`'s hardcoded `fallback` array (used when
   `GET /packages` hasn't answered yet — common on first boot) still
   read "Caddy reverse proxy + Homepage. Required." Fixed the copy.
   Kept the fallback array itself: it's a deliberate resilience feature
   (first boot is exactly when the backend is slow), not dead
   duplication — but it does mean this text has to be kept in sync by
   hand, which is exactly how the Homepage line survived the manifest
   fix. Noted in the file as a caution for the next person, not solved
   here.
3. **Nested interactive control.** The card was a `<button>` wrapping
   `<Checkbox>`, itself a `<button role="checkbox">` — invalid HTML,
   undefined focus/AT behaviour. Restructured to one control per card:
   the card itself is `role="checkbox"` + `aria-checked`, and the small
   box on the left is a decorative (aria-hidden) echo of that state, not
   a second interactive element. Locked cards use `aria-disabled`
   instead of the native `disabled` attribute so a screen reader still
   reaches them, and point via `aria-describedby` at the footnote
   explaining why they're locked, instead of just going silent.

Mandatory packages (core/identity/storage) stay visible with the
`core` Badge, checkbox locked on. Footnote under the grid: "Core
packages are switched on by default — they're essential to
aurora.local, so they can't be turned off here." `proceed()` still
force-includes any missing mandatory package into `enabled_packages`
before it PATCHes the draft, so hiding-vs-locking makes no difference
to what actually gets installed.

`npm run typecheck` and `npm run test:unit` (450/450) both clean after
this change.

Commit: "frontend: lock core packages in the picker instead of hiding them".

### Change 3: Authelia is core, the Auth step goes

`isCorePackage()` already included `identity` (see change 2), so the
mechanical part of "make identity mandatory, hidden/locked in the
picker by the same mechanism as core" was already done as a side
effect of switching the picker onto that helper. What was left: the
now-orphaned SSO step.

**Decision: removed the dedicated `sso` step entirely** (not just the
package-choice checkbox on it), folding its one real action — calling
`POST /onboarding/sso {enable: true}`, which generates the three
Authelia secrets and neutralises other packages' internal auth — into
`OnboardingPackages.vue`'s `proceed()`, right after the final package
selection is PATCHed to the server. This is the same call the old step
made on explicit opt-in; it's now automatic and unconditional, same as
Caddy's own setup never got a dedicated step either. Non-fatal on
failure (console.warn), same posture as the preview call and
`patchDraft` itself — the wizard must still complete if the backend
hiccups, and it's idempotent to retry from Packages → identity →
Enable afterwards.

**Why not just lock the checkbox and keep the SSO page as a read-only
"here's what's happening" screen?** Considered it, but three
independent things pointed the same way:

1. `docs/UX_SPEC.md`'s own acceptance criterion **G1** already reads
   `Step header text matches /^Step \d of 9$/` — nine, not ten. The SSO
   step (Phase D iter-11) was added after that spec was written and
   never reconciled with it.
2. `packages/dashboard/e2e/tests/wizard-happy-path.spec.ts`'s own
   header comment states the canonical flow as "Welcome → Admin →
   Domain → Packages → Secrets → DNS → TLS → Review → Done" — nine
   steps, no SSO page, and the whole spec file never once visits
   `/onboarding/sso`.
3. `packages/dashboard/README.md`'s "What it does" bullet already
   describes "9-step onboarding: welcome → admin → domain → packages →
   secrets → DNS → TLS → review → done" — also nine, also no SSO page.

All three predate or are contemporaneous with the SSO step's addition
and none of them were ever updated for it. Removing the step doesn't
just satisfy "the Auth step should go" — it resolves a real, pre-existing
drift between the shipped 10-step build and its own spec/e2e/README.
Treating this as confirmation rather than coincidence.

**Mechanics:**

- `stores/onboarding.ts`: dropped `'sso'` from `STEPS` and
  `STEP_LABELS`. `OnboardingShell.vue` reads both reactively and needed
  no changes of its own.
- `router/index.ts`: removed the `sso` child route. Router guard's
  resume-to-last-step redirect now falls back to `'welcome'` for any
  step string not in `STEPS` (defends against a draft persisted
  mid-`sso` before this change, which would otherwise 404 into the
  catch-all and could loop).
- `api/onboarding.ts`: dropped `'sso'` from the `OnboardingStepId` union.
- Deleted `OnboardingSso.vue` (dead: unrouted, nothing else imported it).
- Added a `/api/onboarding/sso` MSW mock handler (there wasn't one
  before — the old step already called this with no mock backing it in
  dev mode; now that every wizard run calls it, worth having).
- Fixed the "Step X of 10" eyebrow text in every step view I'm allowed
  to touch: Welcome (1/9), Admin (2/9), Domain (3/9), Packages (4/9,
  unchanged position), Secrets (was 6/10, now 5/9), DNS (was 7/10, now
  6/9), TLS (was 8/10, now 7/9).

**What I could NOT fix, and why it matters:** `OnboardingReview.vue`
("Step 9 of 10") and `OnboardingDone.vue` ("Step 10 of 10") are off
limits — another agent is fixing a launch-ordering bug in those files
right now. Both still show the old 10-step numbering and need to
become "Step 8 of 9" / "Step 9 of 9". Flagged to that agent directly
(see SendMessage) rather than left silent, since they own the files and
I don't.

**New step count: 9** (was 10). `npm run typecheck` and
`npm run test:unit` (450/450) clean after this change too.

**Blast radius flag (not fixed here, per instructions):** a recent
audit found rotated `AUTHELIA_*` secrets don't reach Authelia's
`configuration.yml`. Making `identity` mandatory means every box now
runs this path by default instead of only boxes where an operator
opted in — the same templating gap, wider default exposure. Out of
scope for this change; another fix is already in flight.

Commit: "frontend: retire the Auth wizard step, Authelia is mandatory".
