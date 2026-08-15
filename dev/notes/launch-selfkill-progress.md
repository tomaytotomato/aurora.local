# Launch self-recreation fix — progress log

## The bug

`LaunchService` runs `scripts/up.sh` from inside the dashboard's own
container with `AURORA_LAUNCHED_BY=aurora-dashboard` in the environment,
but nothing ever read that variable. `up.sh` brings up every enabled
package — including `dashboard` itself — with a bare `docker compose up
-d --remove-orphans`. The install step just rewrote `.env`, compose sees
changed config for the `aurora` service, and recreates the container the
`up.sh` process is currently running in. SIGTERM lands mid-invocation;
every other package is left `Created` but never `Started`.

Reproduced on the testbed before the fix: `aurora` container exited 143,
six containers `Created` and none `Started`, plus a stray renamed
`<hash>_aurora` container left over from the interrupted recreate.

## Plan

1. `scripts/up.sh`: when `AURORA_LAUNCHED_BY=aurora-dashboard` and
   `dashboard` is one of the enabled packages, keep every `-f` file (so
   `--remove-orphans` still sees the dashboard's compose file and does
   not treat the running container as an orphan) but pass `up -d` an
   explicit list of every service except the dashboard's own
   (`docker compose -f packages/dashboard/compose.yml config --services`
   subtracted from the full service list). Compose then never touches
   the dashboard's own container.
2. Surface (don't auto-remove) any stray `*_aurora` container left over
   from a previous interrupted recreate — cleanup is a one-off manual
   step, not something an install script should do automatically on
   every run.
3. Check every other path that shells out from inside the dashboard
   container for the same flaw: `down.sh`, package add/remove, the
   update flow.
4. Add backend test coverage for the seam (env var actually reaches the
   command).
5. Prove it on the testbed: get the dashboard running with enabled
   packages queued for a `.env` rewrite, trigger a launch the way the
   wizard does, confirm the dashboard container never restarts and
   everything else comes up. Also confirm `bootstrap.sh add` from the
   host (not self-hosted, must not be crippled by the guard) still
   brings the dashboard up.

## Findings while auditing the other shell-out paths

- `scripts/down.sh` is not currently called from inside the container by
  any Java code path today (no "disable package" endpoint exists yet —
  `PackagesController` is read-only: `list`/`get` only). But `down` has
  the same shape of risk if that ever lands, so I added the identical
  guard defensively. Docker Compose v5.3.1's `down` (unlike older
  versions people may remember) accepts explicit `[SERVICES]` arguments,
  so the same "keep every -f file, target every service but your own"
  trick applies. `down.sh` does not pass `--remove-orphans` today, so
  simply omitting the dashboard's service name from the target list is
  sufficient — there's no orphan-adjacent risk to guard against on top
  of that, but I kept the "keep all -f files" shape anyway for
  consistency and so the guard keeps working if `--remove-orphans` is
  ever added to `down.sh` later.
- Package add/remove and the update flow do not shell out to
  `up.sh`/`down.sh`/`bootstrap.sh` from inside the container at all yet.
  `JobService.submitCommand` sets a *second*, differently-named marker
  (`AURORA_INVOKED_BY=aurora-dashboard`) for the same purpose, but its
  only current caller is `DisksController` for SnapRAID parity
  sync/scrub — unrelated to compose. `UpdatesService` only reads image
  digests; it never re-runs `update.sh`/`up.sh`.
- `scripts/update.sh` is just `exec ./scripts/up.sh "$@"` — `exec`
  preserves the calling process's environment, so once `up.sh` honours
  the markers, `update.sh` does too with no separate change needed, for
  whenever it is called in-container.

## Update: two markers, one guard (flagged by the parallel seam audit)

The audit running alongside this fix caught exactly the loose end noted
above before it shipped: `AURORA_LAUNCHED_BY` (LaunchService) and
`AURORA_INVOKED_BY` (JobService.submitCommand) are two independently
invented names for the identical fact — "this process is running inside
the dashboard's own container." A guard that only recognised one of them
would look correct today (only `LaunchService` calls `up.sh` right now)
and then quietly fail to protect whichever in-container job gets wired
up to `up.sh`/`down.sh` next under the other name.

Decided to converge the *shell-side check*, not the Java-side names:
`up.sh` and `down.sh` now treat `AURORA_LAUNCHED_BY=aurora-dashboard` OR
`AURORA_INVOKED_BY=aurora-dashboard` as the same trigger, with a comment
in both scripts explaining why two names exist and which Java class sets
each. Renaming one of the Java-side markers to match the other was
rejected: `AURORA_INVOKED_BY` is JobService's generic marker for every
kind of in-container job (backup, restore, parity sync/scrub, and
eventually enable/disable/update), and calling a SnapRAID scrub a
"launch" would be the wrong word for what's happening. Added a matching
regression test (`JobsControllerIntegrationTest`, "tags the environment
so a shelled-out script knows it is self invoked") pinning that
`AURORA_INVOKED_BY` reaches the command runner, alongside the existing
one for `AURORA_LAUNCHED_BY` in `LaunchServiceTests`.
