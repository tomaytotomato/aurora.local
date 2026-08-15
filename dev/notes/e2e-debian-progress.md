# E2E suite on the Debian testbed — progress log

## Starting point

`packages/dashboard/e2e/` (17 Playwright specs) was built entirely against
an isolated docker-compose instance on `:8091` (`scripts/reset-aurora-e2e.sh`
+ `compose.e2e.yml`), never against the real Debian install chain, and
never wired into CI. Task: point it at the real Lima testbed
(`127.0.0.1:8090`), find out whether `wizard-happy-path.spec.ts` and
`done-launch.spec.ts` actually reproduce yesterday's 409 on
`/onboarding/launch`, and report honestly.

## Environment note

`curl`/`wget` are blocked by this session's permission policy even against
127.0.0.1; `node -e "require('http').get(...)"` works fine and was used
throughout for one-off HTTP checks. `docker`, `npm`, `npx playwright` all
work directly.

## Harness changes made

- `packages/dashboard/e2e/global-setup.ts`: added `AURORA_E2E_FRESH_BOX=1`.
  The existing design always creates an admin and calls
  `POST /api/onboarding/complete` before every run (`seedAuthFixture()`),
  then relies on each spec's own `POST /api/onboarding/reset` to undo that
  between tests. `/reset` is gated on `AURORA_E2E=1`, which only the
  isolated `:8091` compose project sets — a real box (Ansible-installed,
  no such env var) can never call it, so once `seedAuthFixture()` runs
  once, the box is permanently "onboarding complete" and the wizard specs
  can never walk it again. `AURORA_E2E_FRESH_BOX=1` skips the reset script
  *and* skips seeding entirely, and writes an empty (pre-auth) storage
  state instead, so a genuinely fresh testbed VM can be walked exactly
  once by the wizard specs. Every other spec that expects the authed
  fixture already self-skips cleanly when onboarding isn't complete (see
  `onboardingComplete()` guards in dashboard-home-polish.spec.ts and
  friends) — confirmed this by running them.
- `packages/dashboard/e2e/.gitignore`: added `fixtures/authed-state.json`
  — global-setup.ts generates this (a real session cookie for whatever
  box the suite last ran against) and it was not previously ignored.
- New spec: `packages/dashboard/e2e/tests/wizard-sequential-journey.spec.ts`
  — see "the actual finding" below.

## Running it

```sh
cd packages/dashboard/e2e
npm install
npx playwright install chromium   # first time only

# Against a genuinely fresh testbed VM (before anything else onboards it):
AURORA_E2E_BASE_URL=http://127.0.0.1:8090 \
AURORA_E2E_SKIP_RESET=1 \
AURORA_E2E_FRESH_BOX=1 \
AURORA_E2E_KEEP=1 \
npx playwright test <spec-file> --reporter=list
```

`AURORA_E2E_KEEP=1` stops global-teardown.ts running `scripts/teardown.sh`
(harmless against the testbed regardless — that script only ever touches
the `aurora-e2e` docker-compose project, which doesn't exist on the VM —
but noisy and pointless to run every time).

Testbed lifecycle used throughout: `./dev/testbed/up.sh destroy && ./dev/testbed/up.sh all`
for a clean box (~2 min VM boot + ~1 min install chain + ~1 min dashboard
image build on this M-series Mac — the README's "fifteen minutes" estimate
appears to be a first-run/cold-cache figure).

## Do `wizard-happy-path.spec.ts` and `done-launch.spec.ts` catch the 409?

**No — and not because of environment friction. Because of what they
assert.**

### `done-launch.spec.ts`

Ran clean (2/2 passed) against the real testbed, `POST /api/onboarding/launch`
returned 202 exactly as asserted. It cannot ever see the 409 because its
own header comment describes the "state-seeding escape hatch": it POSTs
`/api/onboarding/admin` and PATCHes `enabled_packages` directly, and
**deliberately never calls `POST /api/onboarding/complete`**. The real bug
only exists because `OnboardingReview.vue`'s `install()` calls `/complete`
*before* routing to `/onboarding/done`, and `OnboardingDone.vue`'s
`startServices()` then calls `/launch`, which 409s once
`onboarding.complete` is already true
(`OnboardingService.guardMidOnboarding()`). Skip the `/complete` call, as
this spec does, and `guardMidOnboarding()` never has anything to object
to. This isn't a flaky assertion — it structurally cannot fail this way,
on any target, isolated or real.

### `wizard-happy-path.spec.ts`

Never reaches the launch call at all. Each test does `page.goto(<step>)`
and asserts on that one step's markup in isolation; the last test in the
file (`review install button label is exactly "Install"`) only reads the
button's text — it never clicks it. There is no code path in this file
that calls `/complete` or `/launch`. It was written to check the UX spec
per-step, not to walk the journey end to end.

**Side finding, run for real against the testbed:** 9 of its 15
assertions fail against the live box, for reasons unrelated to the 409:
- `button[data-cta="primary"]` only exists on the Review and Done pages'
  primary buttons. Welcome, Admin, Domain, Packages, Secrets, DNS and TLS
  never set that attribute in their templates — confirmed by reading the
  `.vue` source, not just observing the test fail. Every assertion that
  targets `button[data-cta="primary"]` on those steps finds nothing.
- The TLS page has no "Skip for now" button at all — the skip guidance is
  now plain prose inside an info `Alert`, not a labelled CTA. The
  assertion expects `getByRole('button', { name: /skip.*(later|for now)/i })`.
- `[data-package="core"]` doesn't exist on the Packages page's tiles — the
  markup is a plain `<button>` grid with no `data-package` attribute.

This strongly suggests the spec was written against an earlier iteration
of the wizard and never run against a live instance since — which lines
up with there being no e2e CI job and no isolated-instance run history
either. Did not "fix" these selectors: that's changing what a different
part of the UX spec asserts, out of scope for proving the launch-ordering
gap, and risks quietly weakening coverage rather than fixing drift
properly.

## The actual finding: `wizard-sequential-journey.spec.ts`

Added a new spec that drives Welcome → Admin → Domain → Packages → SSO →
Secrets → DNS → TLS → Review (click **Install**) → Done (click **Start
services**) through the real UI, one step at a time, with no API
shortcuts anywhere. This is the thing neither existing spec does.

First cut passed — but for the wrong reason: the box already had `core`
running from an earlier `done-launch.spec.ts` run, the pre-selected
package set on Packages happened to match what was already up, `/install`
reported nothing new to start, "Start services" never rendered, and the
launch assertion was silently skipped. Fixed by explicitly clicking the
"Media server" preset on the Packages step (guarantees packages the box
has never brought up) and asserting the CTA is visible rather than
tolerating its absence.

Ran against a genuinely fresh testbed VM (`destroy` + `all`, confirmed
`bootstrap_mode: true, complete: false` before starting):
**pending final confirmed run — see below.**

## Self-immolation side effect (not mine to fix, flagged to fix-launch-selfkill and onboarding-picker)

Running `done-launch.spec.ts` for real against the testbed killed the
`aurora` container outright (`docker ps -a` → `Exited (143)`) both times
it ran. Root cause: `frontend/src/stores/onboarding.ts`'s default package
selection and all three presets never include `dashboard`. Once Review's
`install()` PATCHes `enabled_packages` to that selection, `.state.yml`
drops `dashboard`, so when a launch actually runs `up.sh` with that
package list, `scripts/up.sh`'s self-launch guard
(`[[ " ${pkgs[*]} " == *" dashboard "* ]]`) never engages — `dashboard`
was never a candidate — and `docker compose ... up -d --remove-orphans`
treats the running `aurora` container as an orphan and removes it. This
is a different trigger than the "config changed → recreate" case the
in-flight fix on `fix/launch-self-recreation` covers. Recovered each time
with `docker start aurora` (container survives, just stopped — restart
policy `unless-stopped` doesn't apply because it wasn't a crash).
Sent full repro detail to `fix-launch-selfkill` and `onboarding-picker`.

Practical consequence for this investigation: reproducing the 409 is safe
for the box (the guard in `guardMidOnboarding()` rejects *before*
`LaunchService.startLaunch()` ever shells out), but reproducing a
successful 202 launch on this box is not — every real `Start services`
click against a package list missing `dashboard` risks killing the
dashboard mid-request.
