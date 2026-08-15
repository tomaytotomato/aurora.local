# Package lifecycle endpoints — progress log

## The brief

The app detail page's control panel wants four verbs: Install, Start,
Disable, Uninstall. Disable was shipped visible-but-disabled with an
inline reason because no backend endpoint existed for it. Task: work out
the real vocabulary, then build it.

## What was actually there (not what the docs implied)

Before touching anything, checked what the backend really does versus
what `openapi.yaml` and the frontend's own comments claimed:

- `POST /packages/{name}/enable` and `POST /packages/{name}/disable` were
  documented in `openapi.yaml` and referenced by the frontend
  (`PackagesApi.enable/disable`) as "(exists)" — but `PackagesController`
  only ever had the two `GET` methods. Neither had a controller method.
  `OpenApiConformanceTest` doesn't fail on this (a spec entry with no
  implementation is printed, not failed — by design, since ~forty of
  those exist on purpose), so it went unnoticed.
- `POST /packages/{name}/restart` and `.../upgrade` are in the same boat
  — documented, wired on the frontend (`restartPackage()`,
  `upgradePackage()` in `PackageDetail.vue`), never implemented. Out of
  scope for this task (not one of the four named verbs) but worth
  flagging: those two buttons currently 404 against a real backend.
- `POST /services/{name}/start` (the actual "Start" verb) does exist and
  works. Its implementation runs `bash scripts/up.sh <name>` through
  `LaunchService`, and up.sh's own trailing `state_set_enabled` call
  **overwrites** `.state.yml`'s `enabled[]` with whatever package list it
  was given. Passing a single name therefore risks silently un-enrolling
  every other already-enabled package. No test in the suite exercises
  `.state.yml` after a `/services/{name}/start` call, so this is a live,
  untested risk in code I did not touch (out of scope for the four named
  verbs, but noted here because the design below depends on knowing
  about it — see "the one bug that mattered").
- Only `core` was special-cased anywhere server-side (a couple of
  dependency-ordering spots in `OnboardingService`). `identity` and
  `storage` — the other two packages the frontend's `CORE_PACKAGES` set
  protects — were not recognised as mandatory by the backend at all.
  Nothing stopped a direct `curl` from stopping Authelia.

## The state machine

Two independent axes, not four ad-hoc verbs:

- **Enrolment** — is the package in `.state.yml`'s `enabled[]`? That's
  "installed".
- **Running** — does it have a live container right now?

```
NOT_INSTALLED  --install()-->  RUNNING
RUNNING        --stop()-->     STOPPED   (enrolled, no container)
STOPPED        --start()-->    RUNNING   (existing endpoint, untouched)
STOPPED/RUNNING --uninstall()--> NOT_INSTALLED
```

Core packages (`core`, `identity`, `storage`) sit outside this machine
entirely — none of the four verbs apply, in any state.

### Disable vs. Uninstall

- **Disable** = stop only. Tears down the containers
  (`scripts/down.sh <name>` — volumes preserved) but leaves the package
  in `enabled[]`. Reversible with a plain Start; no reinstall, no
  re-onboarding. This is genuinely new — there was no "stop but stay
  enrolled" verb before.
- **Uninstall** = stop + un-enrol. Same teardown, then removes the
  package from `enabled[]`. This is what the old `/packages/{name}/disable`
  already documented ("Stop and disable a package") — the existing
  contract was right, it just had nobody behind it.

### Does Uninstall delete data?

No. `data/<name>` is never touched by anything this change added.
`down.sh` was never asked to remove volumes, and the new
`PackageLifecycleService.uninstall()` calls nothing else that would. The
frontend's own uninstall confirm dialog already promises this ("Its data
stays on disk unless you also clear its volumes by hand") — the backend
now actually keeps that promise rather than it being aspirational copy.
A test (`data_on_disk_is_never_touched`) plants a file under
`data/media/config/` and asserts it survives an uninstall.

### The one bug that mattered: up.sh's argv must be the full set

`up.sh` (used by Install) writes `.state.yml`'s `enabled[]` to exactly
whatever package list it was invoked with — not additively. So Install
always reads the current `enabled[]`, adds the new package, and passes
the **whole merged set** to `up.sh`, never just the one package. Getting
this wrong would have silently un-enrolled every other package on the
box the first time someone clicked Install. `down.sh`, used by Disable
and Uninstall, has no such trap — given an explicit name it only touches
that package and never writes `.state.yml` — so Uninstall updates
enrolment itself, in Java, after the teardown has actually succeeded
(not before, so a failed teardown never left a package silently
un-enrolled — covered by `a_failed_teardown_leaves_enrolment_untouched`).

## What was built

- `PackageLifecycleService` (new) — `enable()`, `stop()`, `uninstall()`.
  Owns the core-package refusal (`CORE_PACKAGES = {core, identity,
  storage}`, mirroring the frontend's own set in `api/packages.ts`
  exactly) and all the state-transition guards, so a core package or an
  illegal transition is refused before any command ever runs, not just
  hidden by the frontend.
- `PackagesController` — three new `POST` endpoints: `/enable`, `/stop`
  (new), `/disable`. All delegate straight to the service and return
  `202 {jobId}`.
- `JobService.Kind` gained `STOP`.
- `openapi.yaml`: added `POST /packages/{name}/stop`, added `stop` to the
  `JobKind` enum, and documented what `/enable`/`/disable` actually do
  now that something implements them.

### HTTP status vocabulary

- `404` — package doesn't exist.
- `403` — core package (a fixed rule, not a state conflict).
- `422` — the package isn't installed (nothing to stop/uninstall).
- `409` — a state conflict on an otherwise-valid target: already
  enabled (Install), already stopped (Disable).
- `202 {jobId}` — accepted, streams through the existing `JobService` /
  `/api/jobs` / `JobLogPanel` mechanism. Nothing runs as a blocking
  request.

## Backend tests

New: `PackagesLifecycleControllerIntegrationTest` (extends
`AuroraIntegrationTest` — real SQLite, real repo tree, faked
`CommandRunner` and `DockerClient`), 16 tests across three `@Nested`
groups (Install / Disable / Uninstall). Covers the happy paths, the core
refusal for all three mandatory packages on all three verbs, the
not-installed refusal, the already-in-state conflict, unknown package,
the up.sh full-argv property, the down.sh-not-touching-.state.yml
property, and the "failed teardown doesn't un-enrol" property.

Added fixture manifests for `identity`, `storage` (so core-refusal can
be proven for all three, not just `core`) and `photos` (enabled: false,
so there's a real NOT_INSTALLED package to test Install against and
Disable/Uninstall's 422 against).

Fixed `PackagesControllerTests` to pass the new second constructor arg
(a mocked `PackageLifecycleService`) — no behavioural change there.

Backend: 729 → 745 tests, all green. (Note: the task brief said 725; the
worktree's actual pre-existing count was 729 — recorded here as measured,
not as a discrepancy worth chasing.)

## Still to do

- Frontend: `packageActionSlots()` needs a real `disable` slot (not
  visible-but-disabled), `PackagesApi.stop()`, and `PackageDetail.vue`
  wiring + a confirm/copy update. Frontend test counts to follow.
- `scripts/up.sh` / `down.sh` javadoc comments already said "package
  enable/disable/update once those land" for the `AURORA_INVOKED_BY`
  self-launch guard — confirmed this still applies unchanged: every
  command this service runs goes through `JobService.submitCommand` /
  `commands.stream(..., Map.of("AURORA_INVOKED_BY", "aurora-dashboard"), ...)`,
  which is the same seam the guard already recognises. Nothing new
  needed there.
