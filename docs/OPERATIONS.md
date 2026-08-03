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

## Dependencies

| Tool       | Used by                                                 | Optional?      |
|------------|---------------------------------------------------------|----------------|
| `docker`   | doctor, health, pin                                     | no             |
| `curl`     | health (HTTP probes)                                    | probes skipped |
| `openssl`  | rotate-secrets (rand-hex)                               | no             |
| `tar`      | backup                                                  | no             |
| `rclone`   | backup (only if `RCLONE_REMOTE` is set)                 | yes            |
| `ss`       | doctor (port audit)                                     | yes            |
| `avahi`    | doctor (mDNS check)                                     | yes            |
| `jsonschema` + `pyyaml` | CI manifest validation                    | CI only        |

None require root; `backup.sh` will `sudo` retry only if plain `tar`
fails on a root-owned bind mount (e.g. adguard config).
