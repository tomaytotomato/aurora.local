# Operations handbook

Day-2 tooling for a running aurora.local box. Every script here is
idempotent, safe to run repeatedly, and lives under `scripts/`.

| Script                     | Purpose                                                | Cadence           |
|----------------------------|--------------------------------------------------------|-------------------|
| `scripts/doctor.sh`        | Pre-flight sanity: docker, RAM, swap, ports, DNS, cert | Ad-hoc / post-install |
| `scripts/health.sh`        | Live container + HTTP health of enabled packages       | Every 5 min (cron) |
| `scripts/backup.sh`        | Snapshot config + small state to tarball, prune old    | Daily             |
| `scripts/pin.sh`           | Report / refresh / apply image-digest pins             | Weekly (`--check`) |
| `scripts/rotate-secrets.sh`| Find weak `.env` values, rotate on `--apply`           | Quarterly         |
| `scripts/reset-admin-password.sh` | Recover a lost dashboard admin username/password | Ad-hoc / break-glass |

Every script accepts `-h` / `--help`.

## ⚠️ Safety: don't `rm -rf data/` while containers are running

Docker bind-mounts are path-resolved, not inode-pinned. If you delete
`data/` (or any `data/<pkg>/` subdirectory) while its container is
running, the container will keep serving from in-memory state — but
the on-disk state is gone, and the **next restart wipes the app**
(fresh install, all settings lost).

- **Safe order** for a hard reset: `./scripts/down.sh` **first**, then
  `rm -rf data/<pkg>`, then `./scripts/up.sh <pkg>`.
- `./scripts/doctor.sh` includes a `bind-mount integrity` check that
  fails loudly if any running container's bind source has vanished
  from disk. Run doctor before restarting anything if you suspect the
  filesystem was disturbed — recovery is only possible **before** the
  container restarts, via `docker cp <container>:<path> /somewhere/`.
- `./scripts/backup.sh` (see below) snapshots the small, stateful
  config directories under `data/`; run it as a cron job so a
  recoverable copy always exists.

## doctor

```
./scripts/doctor.sh
./scripts/doctor.sh --quiet   # only failures + summary
```

Non-zero exit on any critical failure (missing docker, no compose
plugin, low disk, running as root, etc.). Warnings are informational.

## health

Reads `.state.yml` (written by the installer) to know which packages
are enabled; falls back to the historical `core privacy media storage`
set. Non-zero exit if any container is unhealthy or any vhost returns
5xx.

Cron example (five minutes, log rotated by `logrotate`):
```
*/5 * * * *  cd $HOME/aurora.local && ./scripts/health.sh --no-http >> /var/log/aurora.local/health.log 2>&1
```

## backup

Writes `~/backups/aurora.local/aurora.local-<host>-<UTC>.tar.gz`. Explicit
about what it does NOT capture: bulk media, transcodes, caches, logs.
For those, use restic/borg against `$MEDIA_ROOT` separately.

```
./scripts/backup.sh                        # local only
RCLONE_REMOTE=b2:my-bucket ./scripts/backup.sh
KEEP=30 ./scripts/backup.sh                # override retention
./scripts/backup.sh --dry-run              # list what'd be packed
```

Systemd timer example:
```
# /etc/systemd/system/aurora-backup.service
[Unit]
Description=aurora.local config backup
[Service]
Type=oneshot
User=%i
WorkingDirectory=/home/%i/aurora.local
Environment=RCLONE_REMOTE=b2:aurora-backups
ExecStart=/home/%i/aurora.local/scripts/backup.sh

# /etc/systemd/system/aurora-backup.timer
[Unit]
Description=Daily aurora.local config backup
[Timer]
OnCalendar=*-*-* 03:15:00
Persistent=true
[Install]
WantedBy=timers.target
```

## pin

Three modes:

```
./scripts/pin.sh              # --check: report drift vs pins.env, exit 2 on drift
./scripts/pin.sh --refresh    # re-resolve tags to current digests, write pins.env
./scripts/pin.sh --apply      # rewrite compose.yml image: refs to use pinned digests
```

Typical workflow: run `--check` from cron weekly, open a PR titled
"update pins" when it exits 2, review the drift, run `--refresh
--apply`, commit.

Weekly cron example:
```
15 4 * * 1  cd $HOME/aurora.local && ./scripts/pin.sh --check | mail -s 'aurora.local pin drift' me@example.com
```

## rotate-secrets

Best-effort weakness heuristic — never rotates keys named `*_USER`,
`TZ`, `DOMAIN`, and other non-secret settings.

```
./scripts/rotate-secrets.sh          # report only
./scripts/rotate-secrets.sh --apply   # rewrite in place (.env.bak saved)
```

Run after any suspected credential leak, or quarterly as hygiene.

## reset-admin-password

There is no "forgot password" flow in the dashboard itself — v0.1 is a
single-box admin plane with no email provider to send a reset link
through. If the owner loses the admin username or password set during
onboarding, this script is the supported way back in, run from a shell
on the box.

```
./scripts/reset-admin-password.sh list        # show every user: id, username, role
./scripts/reset-admin-password.sh <username>   # interactively set a new password
```

`list` prints every row in `admin_user` (id, username, role, created)
and nothing else — no password hashes. Run it first if the *username*
is what's forgotten, or if more than one admin exists and you need to
see which one to reset; resetting a password never changes role or
count, so it never runs into the "keep at least one admin" rule that
guards role changes and deletes elsewhere in the app. If the table is
completely empty, this isn't a recovery case at all: the onboarding
wizard creates the first admin from scratch the next time anyone visits
the dashboard, and `list` says so instead of printing nothing.

**Why there's no old-password prompt.** Anyone who can run this script
already has a shell on the box, which means `docker exec` into the
container and read access to every `.env` file under `packages/*/` —
none of that is gated on the dashboard's own login. Asking for the
password you're trying to recover, in order to prove you're allowed to
recover it, would be theatre. Shell access to the box **is** the
authorisation, the same assumption `rotate-secrets.sh` and `backup.sh`
already make. What the script does *not* do is expose this any other
way: the reset logic lives inside `aurora.jar` and is dispatched from a
plain argument check in `AuroraApplication.main()`, before Spring Boot
(and therefore every HTTP route, including the onboarding wizard) has
started. There is no controller and no endpoint — nothing on the
dashboard's frontend or API can reach it.

**How it reaches the database.** `/data/aurora.db` lives on the named
Docker volume `aurora_data`, not a bind mount, and the aurora image is
a bare JRE with no `sqlite3` binary. The script runs
`java -jar aurora.jar reset-admin-password` inside the container
instead — the exact JDBC driver, repo class, and BCrypt cost the app
itself uses for login, so the hash it writes is guaranteed to verify on
the next login attempt. It tries, in order:

1. **Container running** — `docker exec -i aurora java -jar ...`.
2. **Container stopped but not removed** — a short-lived helper
   container from the same image, `--volumes-from aurora`, so it reaches
   the same `/data` without needing to know the volume name or image
   tag by hand.

If the container has been `docker rm`'d entirely, neither applies. Fall
back to running the jar directly against the volume:

```
docker run --rm -i -v aurora_data:/data -e AURORA_DB_PATH=/data/aurora.db \
  aurora-dashboard:<version from packages/dashboard/.env, default 0.1.0> \
  reset-admin-password list
```

**No restart needed afterwards.** Login re-reads the password hash from
the database on every attempt — nothing caches it in the JVM — so the
very next login sees the new password immediately. An already-logged-in
session stays valid until it's logged out or expires, same as any other
password change.

The new password is always read from an interactive, hidden prompt and
piped to the container over stdin — never as a command-line argument
(visible in `ps`) and never printed back. If a run reports "database is
locked", the running app was mid-request at that exact moment; wait a
moment and try again.

## Dependencies

| Tool       | Used by                                                 | Optional?      |
|------------|---------------------------------------------------------|----------------|
| `docker`   | doctor, health, pin, reset-admin-password               | no             |
| `curl`     | health (HTTP probes)                                    | probes skipped |
| `openssl`  | rotate-secrets (rand-hex)                               | no             |
| `tar`      | backup                                                  | no             |
| `rclone`   | backup (only if `RCLONE_REMOTE` is set)                 | yes            |
| `ss`       | doctor (port audit)                                     | yes            |
| `avahi`    | doctor (mDNS check)                                     | yes            |
| `jsonschema` + `pyyaml` | CI manifest validation                    | CI only        |

None require root; `backup.sh` will `sudo` retry only if plain `tar`
fails on a root-owned bind mount (e.g. adguard config).
