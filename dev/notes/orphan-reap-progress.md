# Dashboard orphan-reap fix — progress log

## The bug

`stores/onboarding.ts` never puts `dashboard` in `selectedPackages` — not
in the default, not in any of the three presets. A genuine wizard
completion therefore writes `.state.yml` without `dashboard` in the
enabled set, even though the dashboard's own container is what served
the wizard in the first place.

`up.sh` builds its `-f` file list from the enabled packages only. With
`dashboard` missing from that list, `docker compose up -d
--remove-orphans` sees a running `aurora` container with no matching
service in the merged config and removes it as an orphan. Observed:
exit 143 mid-launch.

This is the mirror image of the self-recreation bug fixed previously
(`1ba0c8a`, `317fa5e`): that guard only excludes the dashboard's own
services from `up -d`'s target list when `dashboard` **is** among the
enabled packages. When `dashboard` is missing from the set entirely, the
existing guard never engages, because its own condition
(`" ${pkgs[*]} " == *" dashboard "*`) is never true.

The root cause (the frontend never selecting `dashboard`) is out of
scope here — tracked separately on the backend/frontend side. This fix
is the invariant that holds regardless of what `.state.yml` says:
`up.sh` must never let a missing package name in the enabled set expose
the dashboard's own container to `--remove-orphans`.

## Fix

`scripts/up.sh`: after assembling `files`/`env_files` from the enabled
package set, added a "dashboard orphan guard" block. If `dashboard` is
not in the resolved package list but is genuinely installed
(`packages/dashboard/.env` exists), force `packages/dashboard/compose.yml`
into the `-f` list and its `.env` into the merged shell environment (for
`${VAR}` substitution) — purely so `--remove-orphans` has a matching
service definition for the running `aurora` container. It is never
added to `pkgs` itself, so `render_all`, per-package seeding,
`state_set_enabled`, and the post-up `seed.sh` hooks are all unaffected;
`.state.yml` is written exactly as before, still missing `dashboard`,
because fixing that file's content is the other half of this problem.

Generalised the self-recreation guard's exclusion logic so `up -d`
never gets handed the dashboard's own service name in either of two
independent cases:
- `dashboard_forced` (not requested at all): never start or recreate an
  uninstalled-by-request dashboard.
- self-launch marker set **and** dashboard genuinely requested: the
  existing guard, unchanged in behaviour.

A host operator explicitly bringing `dashboard` up from a normal shell
hits neither condition and gets ordinary recreate semantics, same as
before the change.

### The "not installed" case

Forcing an uninstalled dashboard's compose file into the list would
trade one crash for another: its `AURORA_SESSION_SECRET` is a hard
requirement (`${AURORA_SESSION_SECRET:?set in packages/dashboard/.env}`)
and any `docker compose` invocation against it — `pull`, `config`,
`up` — aborts before doing anything if that variable is unset. Gated the
whole guard on `packages/dashboard/.env` existing, the same signal every
other package's env-seeding step already relies on to mean "this package
has been brought up at least once." No `.env` → no guard → no forced
`-f` file → nothing for `--remove-orphans` to protect, which is correct:
without a `.env` there's no way the container is running in the first
place.

### `down.sh`: deliberately left untouched

`down.sh` never passes `--remove-orphans`, so there is no orphan-reap
exposure to fix there in the first place — `docker compose down`
without that flag only touches services declared in the `-f` files it's
given, and leaves everything else alone. Forcing dashboard's compose
file into `down.sh`'s file list the way `up.sh` now does would introduce
a *new* bug instead of fixing an existing one: `docker compose down`
with no explicit service arguments stops and removes every service in
the merged config, not just the ones the caller asked to stop. `down.sh
media` would start also stopping the dashboard nobody asked to touch.
Concluded the two scripts need different treatment precisely because
one uses `--remove-orphans` and the other doesn't — the fix follows the
flag, not the script name.

Separately, note that plain `down.sh` (no args) falling back to
`state_list_enabled`/the legacy default will still leave the dashboard
running if `.state.yml` omits it, same root cause as the reported bug
but a much milder symptom (nothing crashes; the admin UI just doesn't
go down with the rest). Not fixing this here — it's the same frontend
issue, tracked separately, and unlike `up.sh` there's no automatic
deletion to prevent.

### `--remove-orphans`: kept

Considered removing it from `up.sh` entirely, since that would also
"fix" the symptom. Rejected: it exists to clean up containers from
packages a user has genuinely disabled, and dropping it trades a rare,
now-guarded crash for a guaranteed accumulation of real orphans on every
disable. The guard fixes the false positive (dashboard treated as an
orphan when it isn't) without giving up the true positive (an actually
disabled package's leftover container).

## Verification

No VM access for this task — the live reproduction described in the bug
report has **not** been re-run. Everything below was proven with a
scratch fixture repo and a fake `docker`, not the real testbed.

### File-list assembly, proven directly

Built a scratch fixture repo (`packages/core`, `packages/media`,
`packages/dashboard` with minimal `compose.yml`/`manifest.yml`, the
dashboard's carrying the real `${AURORA_SESSION_SECRET:?...}`
requirement) and copied the actual, unmodified `scripts/up.sh` +
`scripts/lib/*.sh` into it — no reimplementation of the logic under
test. A fake `docker` executable on `PATH` intercepts `network
inspect/create` (no-ops) and `compose ... pull/ps/config
--services/up -d`, logging every call's `-f` files and trailing service
arguments, and replicates real compose's hard failure on an unset
`${VAR:?message}` so a forced-but-uninstalled dashboard file would
genuinely blow up the same way it would for real.

This machine's only `bash` is 3.2.57 (macOS system default — no
Homebrew bash installed, and I don't have permission to install one),
which lacks `mapfile`/`readarray` outright, so `up.sh` cannot run
locally at all under it. Ran the whole harness inside a
`python:3.12-slim` container instead (Debian bash 5.2, `pip install
pyyaml` for `state.sh`/`manifest.sh`'s PyYAML backend) via Docker
Desktop, which is available on this machine independent of the aurora
testbed VM.

Six scenarios, 19 assertions, all passing against the fixed `up.sh`:

- **A** — dashboard enabled, host invocation: dashboard's compose file
  in the `-f` list, `up -d` gets no explicit service list (ordinary
  recreate semantics, unchanged from before this fix).
- **B** — dashboard enabled, self-launch marker set: dashboard's compose
  file present, `up -d` targets `jellyfin` but not `aurora` (existing
  guard, unchanged).
- **C** — dashboard installed but *not* in the enabled set, host
  invocation: dashboard's compose file forced into the `-f` list,
  `aurora` excluded from `up -d`'s targets, `jellyfin` still included.
- **D** — dashboard installed, not enabled, self-launch marker set —
  the exact shape of the reported bug (`LaunchService` running the
  wizard's Launch step without `dashboard` in `selectedPackages`):
  same result as C.
- **E** — dashboard neither enabled nor installed (no `.env`), host
  invocation: dashboard's compose file never appears in *any* docker
  call, `up.sh` exits 0.
- **F** — same as E with the self-launch marker set anyway: identical
  result, confirming the marker alone doesn't force inclusion without
  an installed dashboard.

**Negative control:** re-ran scenarios C and D against a copy of
`up.sh` taken straight from `HEAD` (i.e. before this fix). Both
confirmed the bug directly — the `up -d` call's `-f` list contained
only `packages/media/compose.yml`, with `packages/dashboard/compose.yml`
absent entirely, reproducing exactly the exposure the bug report
describes (the fake docker's `--remove-orphans`-equivalent has no
notion of "already running" to actually delete, but the missing `-f`
file is the mechanism the real bug depends on). This confirms the
harness distinguishes fixed from unfixed behaviour rather than passing
regardless of what `up.sh` does.

### Floors

- `bash -n` clean on every file the task named: `bootstrap.sh`,
  `scripts/*.sh`, `scripts/lib/*.sh`.
- `docker run --rm -v "$PWD":/mnt -w /mnt koalaman/shellcheck:stable -x
  -S style -e SC1091 bootstrap.sh scripts/*.sh scripts/lib/*.sh` —
  clean, exit 0 (no local shellcheck available; the stock
  `koalaman/shellcheck:stable` image has no shell, so globs were
  expanded on the host before invoking it, matching CI's actual set of
  files).

### What remains unproven without the VM

- The live repro from the bug report (complete the wizard on Debian,
  watch `aurora` survive) has not been re-run.
- Real `docker compose`'s actual `--remove-orphans` removal behaviour —
  the fake docker in this harness proves the `-f`/service-argument
  assembly precisely, but doesn't model container lifecycle, so it
  can't demonstrate a real container surviving a real `up -d
  --remove-orphans` call.
- Interaction with a real `.env` containing weak/default secrets and
  `rotate-secrets.sh --apply` mutating it mid-run (the fixture's
  dashboard secret was already strong, so rotation never fired in any
  scenario).
- Anything downstream of a successful `up -d` (health checks, Caddy
  routing, the dashboard's own onboarding-completion flow) — out of
  scope for this fix and untouched by it.
