# Package contract

A **package** is a self-contained stack of docker-compose services that
`scripts/up.sh` can merge into the shared `home` project.

## Core vs non-core: the isolation boundary

There are two kinds of package, and they follow opposite rules. See
`docs/CORE_SHARED_SERVICES_PLAN.md` for the full reasoning.

**Core** (`packages/core`) is one tightly-integrated stack with shared
infrastructure: the reverse proxy + SSO edge (Caddy, Authelia), the mail
substrate (Stalwart), and the **shared datastore `core-db`** (one Postgres
instance; each core app gets its own database on it, schemas stay
app-owned). Core apps are pre-configured and share infrastructure because
they are already one blast radius — if Caddy or Authelia is down, the box
is down, so sharing a database costs nothing and buys one backup, one set
of secrets, one thing to tune.

**Non-core** packages are self-contained, isolated stacks. Each one:

- **MUST** own its datastore inside its own `compose.yml` (as `dev`,
  `documents`, and `photos` already do with their own Postgres/Redis).
- **MUST NOT** connect to `core-db`, a future `core-cache`, or any other
  package's services. The *only* sanctioned cross-stack dependency is the
  reverse-proxy + SSO edge (a `caddy.snippet` + `sso:` block), which is
  how every app is reached anyway.
- Is deployable, restartable, and **destroyable** as a unit: deleting the
  app deletes its data with it, and nothing else on the box notices.

This is **blast-radius isolation**: a non-core app corrupting or hammering
its database must never be able to take down auth, mail, or a sibling app.
The rule is enforced, not just documented — CI fails a non-core
`compose.yml` that references `core-db`/`core-cache` (see
`.github/workflows/ci.yml`, the `core-isolation` job), and
`CoreDbIsolationRule` raises a dashboard security finding if a non-core
container is found joined to core's datastore on a live box.

Every `packages/<name>/` directory MUST contain:

| File               | Required | Purpose                                                      |
|--------------------|----------|--------------------------------------------------------------|
| `manifest.yml`     | yes      | Metadata consumed by `bootstrap.sh` and `scripts/*.sh`.      |
| `compose.yml`      | yes      | Compose stack. Uses `name: home-<name>`, joins `aurora_net`.   |
| `.env.example`     | yes      | Every variable referenced by `compose.yml`, with comments.   |
| `README.md`        | yes      | Short human description + first-run notes.                   |
| `caddy.snippet`    | no       | Vhost fragments imported by `packages/core/caddy/Caddyfile`. |
| `pins.env`         | no       | Pinned image digests, written by `scripts/pin.sh`.           |
| `seed.sh`          | no       | Idempotent post-up hook run by `scripts/up.sh`.              |

## manifest.yml schema

```yaml
name: media                     # matches directory name
title: Media automation         # short human title
description: |                  # one paragraph, shown in the picker
  Sonarr/Radarr/Bazarr/Prowlarr + Seerr + RDTClient + optional
  qBittorrent-behind-VPN (opt-in via 'torrent' profile).

# Grouping in the interactive selector.
category: media                 # core|privacy|media|storage|backup|monitoring|productivity|dev|ai|identity|home-automation

# Upstream project, for the Source and Docs buttons on the app page.
# source_url is REQUIRED of every shipped package — a package without one
# renders a Source button that leads nowhere, and
# PackagesServiceTests.everyRealManifestNamesItsUpstreamSource fails the
# build over it. Name the package's headline service, not every image it
# runs. homepage_url is optional: for a few packages the repository IS the
# documentation, and inventing a homepage to fill the field would be worse
# than omitting the button.
source_url: https://github.com/Sonarr/Sonarr
homepage_url: https://sonarr.tv

# Hostname labels this package serves, without the domain. The preferred
# discovery path for MdnsAliasService, which otherwise grep-parses
# caddy.snippet for them.
vhosts: [sonarr, radarr]

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
  - SONARR_API_KEY              # optional list; blank ok on first run

# Values that exist somewhere else and can only be copied in — a VPN
# provider's key, another app's API key. scripts/rotate-secrets.sh never
# generates these: filling them with random bytes would look configured,
# destroy the "not set yet" signal the dashboard reads, and change on every
# run (which recreates the package's containers each time). NOT the same as
# required_env: core *requires* AUTHELIA_JWT_SECRET and Aurora *generates*
# it. Optional; a built-in pattern covers the obvious names.
external_env:
  - WIREGUARD_PRIVATE_KEY

# Post-install one-liners printed to the user.
post_install_notes: |
  Prowlarr indexers must be linked in Sonarr/Radarr Settings > Download Clients.
```

### Variants (interchangeable alternatives)

A category often has more than one good answer: several note apps, more
than one media player. Rather than bake one choice in, packages that do
the same job share a **variant group** and the picker offers the group
as a single choice.

```yaml
variant_group: notes            # packages with the same label are alternatives
variant_default: true           # exactly one per group is the recommended pick
```

Both fields are optional; a package with no `variant_group` is a
standalone stack as before. Example groups shipped today:

| Group          | Packages                        | Default        |
|----------------|---------------------------------|----------------|
| `notes`        | `notes` (SilverBullet), `memos` | SilverBullet   |
| `media-player` | `jellyfin`                      | Jellyfin       |

Variants are not mutually exclusive at runtime (you can enable both); the
grouping is a UI affordance so the picker presents "pick your notes app"
instead of a flat list.

### Other recognised fields

Beyond the core schema above, manifests may also declare `probe` (how the
control plane checks liveness), `subpackages` (user-facing children shown
under the package's dashboard row, each with its own probe), `warnings`
(pre-install advisories against the host resource snapshot), and
`requires.start_budget_seconds` (how long the launcher waits before
reporting a failure). See `packages/media/manifest.yml` for a full
worked example. All are validated by `.github/schema/manifest.schema.json`.

## Adding a new package

1. `mkdir packages/foo && cd packages/foo`
2. Copy `packages/_template/*` (see repo).
3. Fill in `manifest.yml`, write `compose.yml` + `.env.example`.
4. Optionally add `caddy.snippet` (imported automatically by core).
5. `./scripts/up.sh foo` to smoke-test.
6. Commit; bootstrap.sh will find it on next run.

### Gotcha: relative bind-mount paths in a multi-`-f` project

`scripts/up.sh` invokes `docker compose -p aurora -f packages/core/compose.yml -f packages/<pkg>/compose.yml ...`. Compose resolves relative bind-mount source paths against the **first** `-f` file's directory (i.e. `packages/core/`), **not** each file's own directory.

So inside `packages/<pkg>/compose.yml`, always write paths as:

```yaml
volumes:
  - ../<pkg>/config/foo.yml:/etc/foo/foo.yml:ro   # your OWN files
  - ../../data/<pkg>/state:/data                   # runtime data
```

Never `./config/foo.yml` — that resolves to `packages/core/config/foo.yml` and blows up at container start.
