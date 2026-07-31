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
| `./scripts/get-caddy-root-cert.sh`    | Extract root CA for client HTTPS trust.             |

## What's a package?

See [`docs/PACKAGE_CONTRACT.md`](docs/PACKAGE_CONTRACT.md).

Every `packages/<name>/` ships a `compose.yml`, `.env.example`,
`README.md`, and `manifest.yml` describing dependencies, ports,
categories, and post-install notes. `bootstrap.sh` and `scripts/*.sh`
use those manifests to resolve installs, pick packages in the TUI, and
render status output.

Current packages:

| Package | Category | What                                              |
|---------|----------|---------------------------------------------------|
| core    | core     | Caddy (HTTPS + reverse proxy) + Homepage          |
| privacy | privacy  | AdGuard Home (LAN DNS) + Gluetun (VPN sidecar)    |
| media   | media    | Sonarr, Radarr, Bazarr, Prowlarr, Jellyseerr, RDT |
| storage | storage  | Samba + MiniDLNA                                  |

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
docs/PACKAGE_CONTRACT.md  package schema
host/                     ansible for OS hardening (docker, ufw, ssh, fail2ban)
packages/<name>/          per-stack compose + manifest + .env.example
scripts/
  up.sh down.sh status.sh
  lib/                    reusable bash modules (log, prompt, manifest, state)
  seed-adguard.sh         legacy post-privacy hook
group_vars/               ansible vars (all.yml gitignored)
inventory.ini             ansible inventory (gitignored)
```
