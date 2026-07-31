# home.local

Turn any fresh Debian/Ubuntu box into a self-hosted server: reverse
proxy + dashboard + LAN DNS + VPN + media automation + file sharing +
whatever else you enable. Everything runs in Docker, orchestrated
under a single `home` compose project.

## Quick start

On a fresh box:

```
curl -fsSL https://raw.githubusercontent.com/tomaytotomato/home.local/main/bootstrap.sh | bash
```

You'll get an interactive TUI: hostname, domain, timezone, LAN CIDR,
then a package picker. Or drive it headless:

```
ENABLE_PACKAGES="core privacy media storage" \
  HOME_DOMAIN=home.local \
  bash bootstrap.sh
```

## Commands

| Command                               | What it does                                        |
|---------------------------------------|-----------------------------------------------------|
| `./bootstrap.sh`                      | Full install (interactive if TTY, else env-driven). |
| `./bootstrap.sh list`                 | List every available package.                       |
| `./bootstrap.sh status`               | Host + container health + declared ports.           |
| `./bootstrap.sh add <pkg>`            | Enable and start a package (resolves deps).         |
| `./bootstrap.sh remove <pkg>`         | Stop and disable (`core` cannot be removed).        |
| `./scripts/up.sh [<pkg>...]`          | (Re)start the current or given set.                 |
| `./scripts/down.sh [<pkg>...]`        | Stop packages; volumes preserved.                   |
| `./scripts/status.sh`                 | Same as `bootstrap.sh status`.                      |
| `./scripts/doctor.sh`                 | Pre-flight sanity: docker, network, RAM, DNS, etc.  |
| `./scripts/health.sh`                 | Per-container health + HTTP probe each vhost.       |
| `./scripts/backup.sh`                 | Snapshot configs + secrets into `~/backups/`.       |
| `./scripts/pin.sh --check`            | Report image-digest drift vs `packages/*/pins.env`. |
| `./scripts/rotate-secrets.sh`         | Find weak/empty secrets in every `packages/*/.env`. |
| `./scripts/get-caddy-root-cert.sh`    | Extract root CA for client HTTPS trust.             |

Profile flags on `up.sh`: `--torrent` (media qBittorrent behind
gluetun), `--zigbee` (home-automation Zigbee2MQTT), `--gpu` (ai
Ollama with NVIDIA passthrough).

## What's a package?

See [`docs/PACKAGE_CONTRACT.md`](docs/PACKAGE_CONTRACT.md).

Every `packages/<name>/` ships a `compose.yml`, `.env.example`,
`README.md`, and `manifest.yml` describing dependencies, ports,
categories, and post-install notes. `bootstrap.sh` and `scripts/*.sh`
use those manifests to resolve installs, pick packages in the TUI, and
render status output.

Current packages:

| Package         | Category         | What                                                                       |
|-----------------|------------------|----------------------------------------------------------------------------|
| core            | core             | Caddy (HTTPS + reverse proxy) + Homepage dashboard                         |
| privacy         | privacy          | AdGuard Home (LAN DNS) + Gluetun (VPN sidecar)                             |
| media           | media            | Sonarr, Radarr, Bazarr, Prowlarr, Jellyseerr, RDTClient, SABnzbd, qBittorrent |
| storage         | storage          | Samba + MiniDLNA                                                           |
| monitoring      | monitoring       | Prometheus + Grafana + node_exporter + cAdvisor + Uptime-Kuma              |
| backup          | storage          | Kopia (dedup backup with Web UI)                                           |
| photos          | productivity     | Immich                                                                     |
| documents       | productivity     | Paperless-ngx + Stirling-PDF                                               |
| notes           | productivity     | SilverBullet                                                               |
| git             | dev              | Forgejo + forgejo-runner CI                                                |
| dev             | dev              | code-server + Postgres 16 + Redis 7                                        |
| ai              | ai               | Ollama + Open-WebUI (CPU default, `--gpu` opt-in NVIDIA)                   |
| home-automation | home-automation  | Home Assistant + Mosquitto + Zigbee2MQTT (`--zigbee`)                      |
| identity        | identity         | Authelia SSO + 2FA (forward-auth for other packages)                       |

Adding a new one is a copy of `packages/_template/` and a
`./bootstrap.sh add <name>` away.

## State

`.state.yml` (gitignored) at the repo root records the current
`hostname`, `domain`, and `enabled` package list. `add` / `remove`
update this so `up.sh` / `down.sh` / `status.sh` always know the true
set.

## Layout

```
bootstrap.sh              installer entrypoint
docs/
  PACKAGE_CONTRACT.md     package schema
  OPERATIONS.md           operator handbook (backup/pin/health cadence)
host/                     ansible for OS hardening
  roles/
    common, docker, firewall, ssh-hardening, fail2ban
    swap-file, storage-mount, avahi, unattended-upgrades, caddy-trust
packages/<name>/          per-stack compose + manifest + fragments
  compose.yml, manifest.yml, .env.example, README.md
  caddy.snippet           (optional) vhost fragments merged into Caddy
  homepage.yml            (optional) dashboard tiles merged into Homepage
  seed.sh                 (optional) idempotent post-up hook
scripts/
  up.sh down.sh status.sh doctor.sh health.sh
  backup.sh pin.sh rotate-secrets.sh
  lib/                    log, prompt, manifest, state, render, ops
group_vars/all.yml        ansible vars (gitignored)
inventory.ini             ansible inventory (gitignored)
.state.yml                installer state (gitignored)
.github/
  workflows/ci.yml        shellcheck + yamllint + ansible-lint + compose + schema
  schema/manifest.schema.json
```
