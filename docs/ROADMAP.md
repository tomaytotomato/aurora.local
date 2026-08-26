# Roadmap

What is wanted but not being built yet, and why. Kept so that a deferral
is a decision with a reason attached rather than something that quietly
got forgotten.

This is not a backlog of everything imaginable. Items here have been
asked for specifically and deferred deliberately.

## Deferred packages

### LibreChat

A self-hosted front end for talking to LLMs, wanted alongside or instead
of the existing `ai` package (Ollama plus open-webui).

**Deferred because it is not a single container.** LibreChat expects
MongoDB, and in most deployments Meilisearch for conversation search, so
it is a small stack rather than one image. That makes it the largest of
the packages currently wanted, and it lands on the same day the alpha is
going onto physical hardware.

What it needs when picked up:

- A multi-service `compose.yml` (app, Mongo, optionally Meilisearch) with
  the persistence living under `../../data/librechat/` per the existing
  convention.
- A decision on whether it replaces `ai`, sits beside it, or becomes a
  `variants:` alternative within the same category. The catalogue already
  supports interchangeable alternatives.
- API keys for hosted providers are secrets, so this wants the sops and
  age encryption work that is still owed, or it ships with credentials in
  a plain `.env`.
- An `sso:` block. LibreChat has its own auth, so the question is whether
  Authelia gates the front door only or whether trusted headers are wired
  through.

Resource note: with Mongo alongside it, this is not a small package. The
`resources:` block should be honest about that before it goes in the
catalogue.

### draw.io

A single `jgraph/drawio` container behind Caddy. Genuinely small: one
image, no persistence worth speaking of (diagrams live wherever you save
them), an `sso:` block, and a vhost.

Deferred only on timing. New packages are new install risk, and it was
raised on the day the alpha goes onto physical hardware. Worth picking up
once the box is proven.

### pi.dev agentic tools

Wanted, and currently underspecified. The repo gitignores
`.pi-subagents/`, so something of this shape is already in use locally,
but what a package would actually contain has not been established.

Before this can be scoped, three things need answering:

- What runs as a service, as opposed to what is a local developer tool?
  Only the former belongs in the catalogue.
- Does it need to reach the Docker socket or the repo? If so it inherits
  the same privilege questions the dashboard has, and the same answers
  should apply rather than a second set.
- Is it single-user or does it sit behind Authelia with the rest?

Left deliberately vague here rather than guessed at.

## Removed packages

### git (Forgejo)

Removed from the catalogue on 2026-08-15. `packages/git` pinned
`codeberg.org/forgejo/forgejo:1.21`, which turned out to be dead: no
`1.21.x` tag has been published since 1.21.11-2 on 2024-06-13. Forgejo
changed its versioning scheme in 2024 (the old `1.21` line became
semver `v6.0`, and `v7.0` onward is the current fork-proper numbering),
so the gap between what was pinned and current stable (v16.0.2) is nine
major versions, each of which may carry breaking changes under
Forgejo's own semver contract. Two years with no security fix on a
package that hosts source code was judged too big a risk to carry
forward, so the package is gone rather than patched in place.

Removed: `packages/git/` (compose, manifest, caddy snippet, homepage
fragment, README, `.env.example`, `pins.env.example`), the `git`
row from the root README's package table, the Forgejo node from the
layered-view diagram in `docs/ARCHITECTURE.md`, and every prose mention
of Forgejo in `packages/identity/README.md`
+ `packages/identity/caddy.snippet` (it was one of the three services
using Authelia's trusted-header auth, alongside Grafana and Paperless).
Dashboard mock fixtures (`packages.ts`, `proxy.ts`, `backup.ts`,
`hardening.ts`) had their Forgejo/`git`-package entries removed or
re-pointed at a still-real package so the demo data doesn't reference
something that no longer exists. No other package's manifest named
`git` in `depends_on` or `recommends`, so no dangling dependency was
left behind.

Deliberately left alone at the time: the Phase D history docs, which
recorded what Phase D actually did (Forgejo was a real target then);
rewriting history docs to erase a since-removed package would have
misrepresented what happened.
Also left: a handful of prose mentions of "Forgejo" in backend/frontend
code comments (`SsoBlock.java`, `AutheliaCaddySnippetInvariantsTests.java`,
a backend test fixture `caddy.snippet`, `OnboardingSso.vue`) that list
it alongside Grafana/Paperless as an example of trusted-header auth —
these are inert prose, not manifest or dependency declarations, and
sit in files under active work by other agents on this branch set.

**A box that already has `git` installed** keeps running exactly as
before — removing a package from the catalogue does not touch a
running box. Forgejo and its runner keep serving on `:3080`/`:2222`
under the operator's own `docker compose` project, and `.state.yml`
still lists `git` as enabled. What breaks: the next `./scripts/up.sh`
or `./scripts/down.sh` (with no explicit package args, or any
invocation that includes `git`) calls `manifest_resolve_deps` /
checks for `packages/git/compose.yml`, and both die immediately —
`scripts/lib/manifest.sh` and `down.sh` check the file exists before
anything else runs, rather than skipping a package they can't find.
Every other enabled package on that box stops being manageable through
either script until the dangling `git` entry is cleared from
`.state.yml`. Awkwardly, the normal cleanup path (`bootstrap.sh remove
git`) itself calls `down.sh git`, which needs the very
`packages/git/compose.yml` that pulling this change deletes — so the
one command designed to remove a package cleanly cannot run once the
package's files are gone. An existing operator needs to sequence this
by hand: run `bootstrap.sh remove git` (or `./scripts/down.sh git`)
**before** pulling a repo state where `packages/git/` no longer
exists, so it can still stop the containers and clear `.state.yml`
properly. Anyone who has already pulled past that point needs to stop
the `forgejo` / `forgejo-runner` containers directly with `docker rm
-f`, and hand-edit `.state.yml` to drop the `git` line, before
`up.sh`/`down.sh` will work again. No automated migration is provided
here — a `remove`/re-`add`-under-a-new-name flow does not exist yet
for a package whose replacement hasn't been chosen, and the data under
`data/git/` is left untouched either way, ready to migrate once a
replacement is picked.

**Needs a replacement chosen before this can be re-added.** Candidates
worth a proper look when that happens: Gitea (Forgejo's upstream,
still actively released and tagged), a newer Forgejo pin if the
project decides the fork is still the right call, or moving git
hosting off-box entirely (GitHub/Codeberg-hosted, no local package).
Not evaluated here — this entry exists so nobody re-adds the same dead
pin without knowing why it was pulled.

## Research: replacing `dperson/samba` in `packages/storage`

`storage` is one of the three mandatory core packages, so its image
choice sits on every install. `dperson/samba:latest` has no release
in roughly five years and, unlike Forgejo, no version tags at all —
only `latest` and a handful of stale architecture-named tags — so it
cannot be pinned even to a frozen version the way `git`'s Forgejo pin
was. Earlier version-pinning research already flagged this
as "no safe pin at all" and recommended the owner consider a
maintained alternative as a separate decision. This is that research.
**No change has been made to `packages/storage/compose.yml`** — this
is evidence for a decision, not a migration.

### Candidates checked

Two actively maintained images stood out. Both confirmed today with
`docker buildx imagetools inspect` (not taken on trust):

**`ghcr.io/servercontainers/samba`** — Samba on Alpine, from the
ServerContainers project (669 stars, 75 forks). Commit history runs
into July 2026, so it is being kept current. Version tags follow an
`a<avahi-ver>-s<samba-ver>-r<revision>` scheme, e.g.
`a3.24.1-s4.23.8-r0` (Samba 4.23.8, Avahi 3.24.1, revision 0) —
confirmed to exist and resolve to digest
`sha256:9c629b0b9261ba04289275479f67f6bdaadd6ed18e90631e1ed451749ea69d18`,
published across `linux/amd64`, `linux/arm64`, `linux/arm/v7`, and
`linux/arm/v6`. Three such tags have shipped in the last three months
(`a3.23.4-s4.22.10-r0` → `a3.24.0-s4.23.8-r0` → `a3.24.1-s4.23.8-r0`),
so roughly monthly cadence. Configuration is environment-variable
driven: `ACCOUNT_<user>` / `UID_<user>` for accounts,
`SAMBA_VOLUME_CONFIG_<name>` for each share (a semicolon-delimited
string, same shape of idea as `dperson`'s `-s` flag but as an env var
instead of a CLI arg), `SAMBA_GLOBAL_CONFIG_<key>` for `smb.conf`
globals. It also bundles Avahi (zeroconf/Time Machine) and wsdd2
(Windows network discovery) as optional services, toggled off with
`AVAHI_DISABLE=true` / `WSDD2_DISABLE=true` — worth noting because
Aurora's host provisioning already runs its own Avahi at the OS level
(`R8` in the Ansible role list, `docs/ARCHITECTURE.md` L2), so adopting
this image without disabling its bundled Avahi would run two mDNS
responders on the same box.

**`crazymax/samba`** (mirrored at `ghcr.io/crazy-max/samba`) — from
the same maintainer as several widely-used GitHub Actions
(`docker/setup-buildx-action` etc.), 630 stars, 61 forks. Tags track
the upstream Samba version directly and cleanly: `4.23.8` (29 days
old), `4.22.8` (3 months old), `4.21.4` (about a year old), with
history back to `4.13.8`. Confirmed `4.23.8` resolves to digest
`sha256:b37f7af97c773eddb593537f64da6389e5ee6695bcecf44f3ba1a8a6bcf34125`
across `linux/amd64`, `linux/arm64`, `linux/arm/v7`, `linux/arm/v6`,
and `linux/ppc64le` (one more platform than the box needs, no harm).
Configuration is a mounted `/data/config.yml` (YAML, with
`${VARIABLE-default}`-style interpolation) rather than environment
variables — a bigger structural change from `dperson`'s CLI-flag
style than `servercontainers`' env-var scheme, since it introduces a
config file Aurora would need to template and mount rather than
values it can keep passing through `compose.yml`'s `environment:`
block the way every other package in this catalogue already works.

Neither is a drop-in: both replace `dperson`'s single `-u`/`-s`
command-line scheme, and neither maps 1:1 onto today's
`SAMBA_USER`/`SAMBA_PASS` env vars in `packages/storage/.env.example`.
`servercontainers/samba` is the closer fit to how every other package
in this catalogue is already configured (values in `environment:`,
nothing new to mount or render), so it is the one worth prototyping
first; `crazymax/samba`'s plain-Samba-version tags are easier to read
at a glance and worth keeping as the fallback if the YAML config file
turns out to be less trouble than expected.

### Is Samba still the right protocol?

Worth asking, since `storage` also carries MiniDLNA and this is a
home server, not an enterprise file server. SMB is kept: it is still
the only LAN file-sharing protocol every target device (Windows,
macOS, Linux, and phones) can mount natively without extra client
software, and MiniDLNA already covers the smart-TV/console case SMB
doesn't reach. NFS would be lighter on Linux/macOS but makes Windows
worse, not better, and something like Syncthing solves a different
problem (folder sync, not a mounted network drive on demand). The
`filebrowser` package already covers the "just want a web UI to grab
a file" case, so it isn't a substitute for a mounted share either.
Nothing here argues for dropping SMB — only for picking a container
image that is still receiving security fixes.

### What a switch would actually cost

Small in code, real in verification. `packages/storage/compose.yml`
is one service (`samba`), one `command:` block building the user and
a single `media` share, and two env vars (`SAMBA_USER`, `SAMBA_PASS`).
Swapping the image means: rewriting that one service's `environment:`
block for the new scheme, deciding what to do with the bundled
Avahi/wsdd2 services either candidate brings, and testing that
existing SMB clients (Windows Explorer, macOS Finder, phones) can
still mount the share and read/write it under the new image before
calling it done — this is a mandatory core package, so a regression
here is not a quiet one. That test is real effort even though the
diff is small, which is exactly why this is deferred to a deliberate
decision rather than folded into an unrelated change.

## Decided against

### Self-hosted Obsidian

Raised and rejected. Obsidian is a desktop application, so hosting it
means one of two awkward things: running CouchDB purely as a sync backend
for the LiveSync plugin, or running the real app inside a container behind
a web desktop, which is a remote desktop in a browser tab and poor on a
phone.

The catalogue already has `notes` (SilverBullet), which is a web-native
markdown notebook, needs no client install, and already carries an `sso:`
block. That is the answer for now. Revisit only if something SilverBullet
genuinely cannot do turns up.

## Owed infrastructure, unchanged

Recorded previously and still outstanding, listed here so it is in one
place:

- `docker-socket-proxy` in front of the dashboard. It still mounts
  `docker.sock:rw` at `packages/dashboard/compose.yml`.
- Real `pins.env` generation.
- sops and age for `.env` encryption. LibreChat above depends on this.
- Dump hooks behind the `backup:` manifest blocks. The blocks exist for
  photos and documents; the hooks they describe do not.

## Backend domains with no implementation

87 of 100 specified endpoints have a controller. The remainder, all
hidden behind capability flags so they do not surface as broken pages:

| Domain | Endpoints |
|---|---|
| backup | 6 |
| custom stacks | 5 |
| per-app protection | 2 |

## Explicitly not planned

Multi-host, Swarm or Kubernetes management; PaaS-style git-push deploys;
a third-party app store replacing the curated catalogue; rebuilding
Homepage's widget grid inside the admin UI.
