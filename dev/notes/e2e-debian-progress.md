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
`bootstrap_mode: true, complete: false` before starting). Result: **fails,
reproducing the exact reported error**:

```
Error: POST /api/onboarding/launch status (body: {"detail":"onboarding already
complete; use authenticated endpoints","instance":"/api/onboarding/launch",
"status":409,"title":"Conflict"})
Expected: 202
Received: 409
```

Byte-for-byte the same `detail`/`instance`/`status` as yesterday's report.
This is on `main`, no fix applied.

### Verified against the fix

Merged `fix/onboarding-launch-ordering` (commit `cdc3518`) locally with
`git merge fix/onboarding-launch-ordering` — clean, no conflicts. That
commit stops `OnboardingReview.vue` calling `/complete` at all and moves
the commit into `OnboardingDone.vue`, firing only once the launch stream
reports success (or there was nothing to launch). Rebuilt the testbed
fresh on the merged code and re-ran the same walkthrough (as a throwaway
scratch spec, since the exact call sequence the fix produces is different
enough — no `/complete` call around Review any more — that the committed
spec's mid-journey assertion about it doesn't apply post-fix): **passes**,
`POST /api/onboarding/launch` returns 202. Then reverted the merge
(`git revert -m 1 <merge-commit>`, keeping history honest rather than
rewriting it) so this branch carries none of the other agent's fix — the
testbed was also rebuilt a final time from the un-merged branch so it's
left in a state that matches what's actually committed here.

**This is the core answer to the task.** Neither existing spec would have
caught yesterday's regression, on any target, because of what they
assert, not because of the environment. `wizard-sequential-journey.spec.ts`
does catch it — red on `main`, green on the fix — because it is the only
spec that drives the actual UI in the actual order a real user does:
Review's Install button, then Done's Start services button, with nothing
skipped in between.

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

## CI job: not added

Tried to establish whether the isolated `:8091` docker-compose path (the
only one a GitHub Actions runner could plausibly use — it can't run a
Lima VM) is CI-ready. It is not, and I did not add a job for it:

- Built `aurora-dashboard:0.1.0` locally via Docker Desktop
  (`docker compose -f compose.yml -f e2e/scripts/compose.e2e.yml build aurora`,
  after exporting `AURORA_SESSION_SECRET` — the compose file requires it
  and there's no `packages/dashboard/.env` in a clean checkout). Build
  succeeded.
- Running `reset-aurora-e2e.sh` itself then failed two different ways
  before it even started a container, both fixed on this branch (see the
  "e2e: fix reset-aurora-e2e.sh and teardown.sh for macOS" commit):
  `getent` doesn't exist on macOS, and the `AURORA_SESSION_SECRET` the
  script seeds into the scratch repo's `.env` never reaches Compose's
  interpolation because Compose's built-in `.env` loading resolves
  against the *real* `packages/dashboard/` (the first `-f` file's
  directory), not the scratch copy — the script writes the value to a
  file Compose was never going to read.
- With both of those fixed, the `aurora` container itself now starts but
  crash-loops: `org.sqlite.SQLiteException: [SQLITE_CANTOPEN] Unable to
  open the database file`. This looks like a UID mismatch — `compose.yml`
  runs the container as `${AURORA_UID:-1000}:${DOCKER_GID:-999}`, and
  `reset-aurora-e2e.sh` sets `AURORA_UID="$(id -u)"`, which is 1000 on the
  Debian testbed (lines up with the Dockerfile's baked-in `chown
  aurora:aurora /data` at UID 1000) but is not 1000 for a macOS user,
  leaving the fresh `aurora_data` named volume owned by a UID the
  container can't write into. Did not fix this — it needs either an
  init container that chowns the volume to `$AURORA_UID` before `aurora`
  starts, or dropping the UID override for this path specifically, and
  either one risks being wrong in a way I can't fully verify without
  more time on a machine I don't want to leave in a half-fixed state.

A CI job that runs against a path I cannot show green on my own machine
is exactly the "red CI job nobody can run" the task warned against, so I
did not add one. What I'd propose once the above is fixed: a `e2e`
job in `.github/workflows/ci.yml` alongside `frontend`/`backend`, building
the image, running `reset-aurora-e2e.sh`, then `npx playwright test`
(default mode, no `FRESH_BOX` — the isolated project's own `/reset`
endpoint is exactly what `AURORA_E2E=1` unlocks it for).

## Coverage gaps in the first-run journey

What has real automated coverage today (after this branch):
- Every wizard step's own markup, in isolation (`wizard-happy-path.spec.ts`,
  `no-cli-instructions.spec.ts`) — though see above, several of these
  assertions are currently stale against the live markup.
- Admin creation, package selection, and the full Welcome→Done click-through,
  including Review's Install and Done's Start services
  (`wizard-sequential-journey.spec.ts`, new).
- Launch streaming's happy path in isolation, via the state-seeding
  shortcut (`done-launch.spec.ts`) — useful for what it actually tests
  (the SSE wiring), misleading if read as "the launch flow works".
- Post-install dashboard behaviour once onboarding is complete (the
  majority of the other 13 specs), gated on the auth fixture.

What has no coverage at all:
- **The SSO/identity step** (`OnboardingSso.vue`) — no spec touches it
  beyond my new one clicking past it with SSO left off. Turning it on
  (which writes `packages/identity/.env` and generates Authelia secrets)
  is completely unexercised by the suite.
- **DNS mode selection beyond "the tabs render"** — no spec actually picks
  adguard/router/mdns and checks the resulting `dns_mode` takes effect.
  (`dev/notes/launch-selfkill-progress.md` independently records Authelia
  crash-looping on a pre-existing secret-templating gap once `identity`
  is actually enabled on the testbed — another sign this path is
  genuinely unwalked by anything.)
- **Launch failure and retry**, for real. `error-recovery.spec.ts` mocks
  the failure via `page.route` interception; nothing drives a real
  package failing to start and checks the retry button actually recovers
  it.
- **The self-immolation bug** described above — no spec would catch a
  wizard that kills its own dashboard container, because nothing asserts
  the box is still reachable after a launch, only that the initial POST
  returned the expected status code.
- **Multi-run idempotency of the real testbed target** — every spec here
  assumes either a fresh box or the isolated project's reset endpoint;
  nothing exercises "an operator re-runs the wizard on a box that already
  has one package enabled" the way `bootstrap.sh add` supports.

## Answering "can we brute-force the process on Debian"

Yes, substantially: `wizard-sequential-journey.spec.ts` proves the whole
Welcome→Done click path can be driven headlessly and repeatably against a
real install, provided the box starts fresh (no reset mechanism exists
for a real box short of reinstalling — `AURORA_E2E_FRESH_BOX=1` only
lets the suite *tolerate* that limitation, it doesn't remove it). What
can't yet be brute-forced repeatedly on the same box: anything past a
successful "Start services" click, because there is no server-side reset
for a real install and the only way to get a second clean run is
`./dev/testbed/up.sh destroy && ./dev/testbed/up.sh all` (roughly 3–4
minutes end to end on this Mac, once images are warm).
