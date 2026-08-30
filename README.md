# aurora.local

Opinionated and simple home server setup to allow you to get the basics up and running.

**Start here:** [`ESSENCE.md`](ESSENCE.md) — what this is, who it is for, and the
two principles (zero terminal, honest state) that outrank everything else in
this repo, including this README.

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

Current packages (generated from the manifests by
`./scripts/gen-package-table.py`; CI checks it is current):

<!-- package-table:start -->
| Package | Category | What |
|---------|----------|------|
| `ai` | ai | Local AI (Ollama + Open-WebUI) |
| `backup` | backup | Backup (Kopia) |
| `bulwark` | productivity | Bulwark (webmail) |
| `core` | core | Core (dashboard + reverse proxy) |
| `dashboard` | core | Aurora — admin dashboard |
| `dev` | dev | Dev sandbox (code-server + Postgres + Redis) |
| `documents` | productivity | Documents (Paperless-ngx + Stirling-PDF) |
| `filebrowser` | storage | Files (FileBrowser) |
| `home-automation` | home-automation | Home automation (Home Assistant + MQTT + Zigbee2MQTT) |
| `jellyfin` | media | Media server (Jellyfin) |
| `media` | media | Media automation (*arr + requests + downloaders) |
| `memos` | productivity | Notes (Memos) |
| `monitoring` | monitoring | Monitoring (Prometheus + Grafana + Uptime-Kuma) |
| `notes` | productivity | Notes (SilverBullet) |
| `photos` | productivity | Photos (Immich) |
| `privacy` | privacy | Privacy (LAN DNS + VPN) |
| `roundcube` | productivity | Roundcube (webmail) |
| `snappymail` | productivity | SnappyMail (webmail) |
| `storage` | storage | LAN file sharing (SMB + DLNA) |
<!-- package-table:end -->

Adding a new one is a copy of `packages/_template/` and a
`./bootstrap.sh add <name>` away.

## Containers & releases

Aurora itself (the Vue frontend and Spring Boot backend) is one container.
The frontend is compiled and baked into the backend jar at build time, so a
single JVM serves the API and the dashboard on `:8090`; Caddy fronts it at
the apex domain. Everything else is an off-the-shelf image pulled from its
own registry.

The dashboard image is published to
[`ghcr.io/tomaytotomato/aurora`](https://github.com/tomaytotomato/aurora.local/pkgs/container/aurora),
built and pushed by CI (`.github/workflows/ci.yml`) only when the test
suites are green:

| Trigger              | Tags published                         |
|----------------------|----------------------------------------|
| push to `main`       | `edge`, `sha-<short>`                   |
| release tag `vX.Y.Z` | `X.Y.Z`, `X.Y`, `latest`               |

Images are multi-arch (`linux/amd64` and `linux/arm64`), so the same tag
runs on an Intel box or an ARM board. A released box pulls the pinned
`AURORA_VERSION` tag (`packages/dashboard/.env`, default `0.1.0`); it only
moves when you bump that value, so an upgrade is a deliberate act. To cut a
release, tag it: `git tag v0.1.0 && git push origin v0.1.0`. Dev, e2e and
the Lima testbed build from source instead, so hacking on the dashboard
never depends on the registry.

## State

`.state.yml` (gitignored) at the repo root records the current
`hostname`, `domain`, and `enabled` package list. `add` / `remove`
update this so `up.sh` / `down.sh` / `status.sh` always know the true
set.

## Layout

```
bootstrap.sh        installer entrypoint (base setup + add/remove/status)
packages/<name>/    per-stack compose + manifest + .env.example + README
  dashboard/        Aurora itself: Spring Boot backend + Vue frontend
scripts/            up/down/status/doctor/health/backup + lib/
host/               Ansible for OS hardening (Docker, firewall, mDNS, ...)
docs/               architecture, operations, package contract, diagrams
.github/            CI and the manifest schema
```

The full tree and how the pieces fit is in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Docs

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how the box is put together, with diagrams in [`docs/diagrams/`](docs/diagrams/).
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md) — the operator handbook: backup, pin, health and secret-rotation cadence.
- [`docs/PACKAGE_CONTRACT.md`](docs/PACKAGE_CONTRACT.md) — what a package manifest may declare.
- [`docs/ALPHA_RUNBOOK.md`](docs/ALPHA_RUNBOOK.md) — bringing a fresh box online, step by step.
- [`docs/SPLIT_TUNNEL.md`](docs/SPLIT_TUNNEL.md) — the privacy package's VPN split-tunnel setup.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — what is done and what is next.
