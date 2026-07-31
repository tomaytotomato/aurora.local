# Package contract

A **package** is a self-contained stack of docker-compose services that
`scripts/up.sh` can merge into the shared `home` project.

Every `packages/<name>/` directory MUST contain:

| File               | Required | Purpose                                                      |
|--------------------|----------|--------------------------------------------------------------|
| `manifest.yml`     | yes      | Metadata consumed by `bootstrap.sh` and `scripts/*.sh`.      |
| `compose.yml`      | yes      | Compose stack. Uses `name: home-<name>`, joins `home_net`.   |
| `.env.example`     | yes      | Every variable referenced by `compose.yml`, with comments.   |
| `README.md`        | yes      | Short human description + first-run notes.                   |
| `caddy.snippet`    | no       | Vhost fragments imported by `packages/core/caddy/Caddyfile`. |
| `homepage.yml`     | no       | Homepage services-group fragment.                            |
| `seed.sh`          | no       | Idempotent post-up hook run by `scripts/up.sh`.              |

## manifest.yml schema

```yaml
name: media                     # matches directory name
title: Media automation         # short human title
description: |                  # one paragraph, shown in the picker
  Sonarr/Radarr/Bazarr/Prowlarr + Jellyseerr + RDTClient + optional
  qBittorrent-behind-VPN (opt-in via 'torrent' profile).

# Grouping in the interactive selector.
category: media                 # core | privacy | media | storage | monitoring | productivity | dev | ai | identity

# Hard dependencies on other packages. bootstrap.sh will refuse to
# enable this one unless every dep is also selected.
depends_on:
  - core                        # always implicit; list anyway for clarity

# Soft: services this package expects but can survive without.
recommends:
  - privacy                     # for qbittorrent-through-vpn

# Compose profiles this package understands.
profiles:
  torrent:
    description: Enable qBittorrent behind gluetun (requires privacy).
    requires_packages: [privacy]

# Ports exposed on the host (informational; drives firewall docs).
ports:
  - {port: 8080, proto: tcp, description: qBittorrent WebUI, profile: torrent}
  - {port: 6881, proto: tcp, description: qBittorrent BT,    profile: torrent}

# Host prerequisites. bootstrap.sh checks these before enabling.
requires:
  min_ram_mb: 2048
  min_disk_gb: 10               # for /data, not media_root
  host_roles: []                # extra ansible host roles to apply

# Files that must exist and be edited before first `up`. bootstrap.sh
# will refuse to start until the user has filled these in (heuristic:
# any KEY=  with empty RHS is treated as unset).
required_env:
  - HOMEPAGE_VAR_SONARR_KEY     # optional list; blank ok on first run

# Post-install one-liners printed to the user.
post_install_notes: |
  Prowlarr indexers must be linked in Sonarr/Radarr Settings > Download Clients.
```

## Adding a new package

1. `mkdir packages/foo && cd packages/foo`
2. Copy `packages/_template/*` (see repo).
3. Fill in `manifest.yml`, write `compose.yml` + `.env.example`.
4. Optionally add `caddy.snippet` (imported automatically by core).
5. `./scripts/up.sh foo` to smoke-test.
6. Commit; bootstrap.sh will find it on next run.
