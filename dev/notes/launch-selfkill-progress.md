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

## Backend suite

`mvn clean test` (Java 25, `JENV_VERSION=25.0.3 jenv exec mvn ...` — this
machine's jenv default is 21, and `JAVA_HOME=...` alone did not override
it because jenv shims ignore that variable unless a `.java-version` file
or `JENV_VERSION` is set) — 669/669 green, `BUILD SUCCESS`. One transient
"class file version 69.0" failure along the way turned out to be
self-inflicted: an accidental `.java-version` file briefly left the
worktree root (from a wrong `jenv local` invocation) meant one run
compiled and ran everything under 25, and after I deleted it a
subsequent run picked up jenv's global 21 default for the surefire fork
while `target/test-classes` still had a Java-25-compiled class file
sitting in it — `mvn clean test` under a consistently pinned JDK cleared
it. Not a defect in the fix; noted here so it isn't mistaken for one.

## Testbed verification

VM already had a partial reproduction of the bug sitting on it: `aurora`
up and healthy, `caddy`/`samba`/`minidlna`/`authelia` up, `adguard`
stuck `Created`, and a stray `3501e49e8a57_aurora` container (its name
literally the id of the still-running `aurora` container prefixed onto
`_aurora` — compose's rename-then-remove dance, interrupted). Removed
the stray container by hand (`docker rm`) — a one-off manual cleanup, as
decided above; `up.sh` does not do this automatically.

Wrote `.state.yml` with `enabled: [core, dashboard, identity, storage,
privacy]` to match what was actually running. `up.sh sync` had already
wiped every package's `.env` (they're gitignored and the Mac source
never had them, so `rsync --delete` removed the VM's copies) — a
reasonable stand-in for "the install step rewrote .env", since the next
`up.sh` run reseeds every `.env` from `.env.example` and
`rotate-secrets.sh --apply` generates fresh secrets for all of them.

Reproduced the exact LaunchService seam by `docker exec`-ing into the
running `aurora` container (not running the script from the VM host —
the bug only exists because the process is a descendant of the
container being recreated) and running:

    docker exec aurora bash -c 'cd /home/bruce/aurora.local && \
      AURORA_LAUNCHED_BY=aurora-dashboard bash scripts/up.sh \
      core dashboard identity storage privacy'

First run: exit 0, log showed `self-launch guard: excluding dashboard's
own service(s) [aurora] from 'up -d'`, `aurora` never appeared in the
Recreate/Recreated/Starting list, and `docker inspect aurora`'s
`Created`/`State.StartedAt` were byte-for-byte identical before and
after. `authelia` started crash-looping afterwards on a config error
(`identity_validation.reset_password.jwt_secret` / `storage.encryption_key`
required) — a pre-existing identity-package secret-templating gap
unrelated to this fix (the freshly rotated `AUTHELIA_*` secrets aren't
reaching `configuration.yml`); flagging it, not fixing it here.

Ran it again after manually forcing a real pending diff (changed
`AURORA_SESSION_SECRET` in `packages/dashboard/.env`, confirmed with
`docker compose up -d --dry-run aurora` that compose genuinely wanted to
recreate it). The guarded run still left `aurora`'s `Created` timestamp
untouched — even though this particular run then hit an unrelated,
pre-existing failure of its own (`adguard` couldn't bind
`0.0.0.0:53/tcp`; Debian's `systemd-resolved` already listens on port 53
— nothing to do with this fix) and exited 1. `aurora` was never a target
of that `up -d` either way, so it survived a real pending recreate *and*
a real failure elsewhere in the same invocation without being touched,
and no stray `*_aurora` container was created this time.

Confirmed the opposite is also true — the guard doesn't fire when it
shouldn't: ran `scripts/up.sh core dashboard identity storage` directly
on the VM host (not `docker exec`, no `AURORA_LAUNCHED_BY`) after forcing
another `AURORA_SESSION_SECRET` change. This time `aurora`'s `Created`
timestamp changed (new container, "1 second ago"), exactly like the old
unguarded behaviour, proving `bootstrap.sh add`/an operator SSH'd into
the box still gets a full, correct recreate when one is actually needed.

Also exercised `down.sh`'s guard directly (nothing calls it in-container
yet, so this was the only way to prove it): `docker exec`'d
`AURORA_LAUNCHED_BY=aurora-dashboard bash scripts/down.sh core dashboard
identity storage` — log showed the same guard message, `caddy`/`samba`/
`authelia`/`minidlna` stopped and were removed, `aurora` was never
targeted and stayed up throughout (`Created` timestamp unchanged, exit
0).

Left the VM with `core dashboard identity storage` back up
(`./scripts/up.sh core dashboard identity storage` from the host) and
`./dev/testbed/up.sh verify` green (dashboard 8090 → 200, caddy 80 →
200). `authelia` is still crash-looping on the pre-existing config gap
above — not a regression from this change, and not fixed as part of it.
