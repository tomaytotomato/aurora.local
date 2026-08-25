# aurora.local

Opinionated and simple home server setup to allow you to get the basics up and running.

This project is a formalisation of the last year of me tinkering around with a home server.

It has a list of applications and configurations that have worked for me, and hopefully will work for you.

If you are wanting something more advanced there are many other alternatives out there like TrueNas, ZimaOS or ProxMox.

## Screenshots



## Quick start

Aurora installs in **two phases**:

**Phase 1 — base setup** (one command, no questions asked). On a fresh box:

```
curl -fsSL https://raw.githubusercontent.com/tomaytotomato/aurora.local/main/bootstrap.sh | bash
```

It auto-detects everything it needs (hostname, user, LAN), prepares the
host (Docker, firewall, mDNS, etc.), and brings up just the reverse proxy,
single sign-on, and the Aurora dashboard. No prompts, no package picker.

**Phase 2 — onboarding** (in the browser). When Phase 1 finishes, open
Aurora and the first-run wizard walks you through the admin account,
domain, DNS and TLS, then drops you on the dashboard — where you install
media, storage, and everything else from the catalogue at your own pace.

```
http://<hostname>.local/     or     http://aurora.local/
```

Domain defaults to `aurora.local`. To override the first-run bring-up set
(mainly for CI/power users):

```
ENABLE_PACKAGES="core privacy media storage" bash bootstrap.sh
```

## Commands

| Command                               | What it does                                        |
|---------------------------------------|-----------------------------------------------------|
| `./bootstrap.sh`                      | Base setup (non-interactive): core + dashboard.     |
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
use those manifests to resolve installs and render status output; the
dashboard catalogue uses them to install packages after onboarding.

Current packages:

| Package         | Category         | What                                                                       |
|-----------------|------------------|----------------------------------------------------------------------------|
| core            | core             | Caddy (HTTPS + reverse proxy) + Authelia SSO/2FA. Aurora (`packages/dashboard`) is the dashboard |
| privacy         | privacy          | AdGuard Home (LAN DNS) + Gluetun (VPN sidecar)                             |
| media           | media            | Sonarr, Radarr, Bazarr, Prowlarr, Seerr, RDTClient, SABnzbd, qBittorrent |
| storage         | storage          | Samba + MiniDLNA                                                           |
| monitoring      | monitoring       | Prometheus + Grafana + node_exporter + cAdvisor + Uptime-Kuma              |
| backup          | storage          | Kopia (dedup backup with Web UI)                                           |
| photos          | productivity     | Immich                                                                     |
| documents       | productivity     | Paperless-ngx + Stirling-PDF                                               |
| notes           | productivity     | SilverBullet                                                               |
| dev             | dev              | code-server + Postgres 16 + Redis 7                                        |
| ai              | ai               | Ollama + Open-WebUI (CPU default, `--gpu` opt-in NVIDIA)                   |
| home-automation | home-automation  | Home Assistant + Mosquitto + Zigbee2MQTT (`--zigbee`)                      |

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
  pins.env                (optional) image digests, written by scripts/pin.sh
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
