# Pinnable versions — progress log

Goal: a curated, machine-readable list of safe pinnable versions for every
image in `packages/*/compose.yml`, feeding the existing (but currently
unused) `pins.env` mechanism (`scripts/pin.sh`, `scripts/lib/render.sh`
`render_pins`), rather than inventing a parallel scheme. See
`docs/ROADMAP.md` "Real `pins.env` generation" — this is that item.

## Design decision

`pins.env` per package already exists as a consumption path:
`render_pins()` sources `packages/<pkg>/pins.env` into the environment,
and `scripts/pin.sh --apply` (not run by this task) would rewrite
`compose.yml` `image:` lines to `${IMAGE_<SVC>}`. That is the natural
home for this data — no new manifest field, no schema change, nothing
new for yamllint to check (`pins.env` is a shell env file, not YAML).

**Correction after starting (important):** `.gitignore` line 49 excludes
`packages/*/pins.env` outright — comment: "Written by `scripts/pin.sh
--apply`; sourced by `scripts/lib/render.sh`." It is host-local generated
state, like a lock file, and was never meant to be committed. Writing the
curated research straight into `pins.env` would have made it invisible to
git — the opposite of "the next person can re-derive it."

The repo already solves exactly this problem for `.env`: `.env` is
gitignored, `!.env.example` is explicitly kept and is the checked-in
template every package is contractually required to ship
(`docs/PACKAGE_CONTRACT.md`). This work mirrors that pattern one level
down: curated pins live in `packages/<pkg>/pins.env.example` (committed),
and the real `packages/<pkg>/pins.env` stays exactly what it already is —
gitignored, host-local, produced by `scripts/pin.sh`. No `.gitignore`
change was needed (the ignore pattern is `pins.env`, not `pins.env.*`, so
`.example` files were never matched). No new manifest field, no schema
change, nothing new for yamllint (still a shell env file).

Deviation from what `scripts/pin.sh --refresh` currently writes: that
script's `resolve_digest()` throws the tag away (`repo="${ref%%:*}"`),
writing `repo@sha256:digest` with no human-readable tag. That contradicts
`UnpinnedImageTagsRule`'s own stated ideal ("postgres:16@sha256:abc...:
tag for discoverability, digest for reproducibility"). This work writes
`repo:tag@sha256:digest` instead, and records provenance (checked date,
reasoning, multi-arch status) as comments above each `IMAGE_<SVC>` line.
`scripts/pin.sh` itself is not modified by this task (out of scope —
flagged for the owner as a follow-up).

Digests obtained with:
`docker buildx imagetools inspect --format '{{.Manifest.Digest}}' <ref>`
(never `docker manifest inspect --verbose` — gives per-platform digests
that don't match `RepoDigests`).

Compose files are NOT modified. `pins.env` files are additive data only.

## Inventory (from `grep -n image: packages/*/compose.yml`, service names
resolved the same way `scripts/pin.sh` does — `IMAGE_<SVC>` where `<SVC>`
is the compose service name upper-cased with `-` -> `_`)

| pkg | service | env var | current ref | status |
|---|---|---|---|---|
| ai | ollama-cpu | IMAGE_OLLAMA_CPU | ollama/ollama:latest | done -> 0.32.5 (multi-arch) |
| ai | ollama-gpu | IMAGE_OLLAMA_GPU | ollama/ollama:latest | done -> 0.32.5 (multi-arch) |
| ai | open-webui | IMAGE_OPEN_WEBUI | ghcr.io/open-webui/open-webui:main | done -> v0.11.0 (:main is explicitly not recommended by upstream; multi-arch) |
| backup | kopia | IMAGE_KOPIA | kopia/kopia:latest | done -> 0.23.1 (actively maintained; multi-arch) |
| core | caddy | IMAGE_CADDY | caddy:2-alpine | done -> 2.11.4-alpine (multi-arch incl. arm64) |
| dashboard | aurora | (exempt) | aurora-dashboard:${AURORA_VERSION:-0.1.0} | exempt — Aurora's own image, see UnpinnedImageTagsRule javadoc |
| dev | code-server | IMAGE_CODE_SERVER | lscr.io/linuxserver/code-server:latest | done -> 4.132.0 (multi-arch) |
| dev | postgres | IMAGE_POSTGRES | postgres:16-alpine | done -> 16.15-alpine3.24 (multi-arch) |
| dev | redis | IMAGE_REDIS | redis:7-alpine | done -> 7.4.10-alpine (multi-arch) |
| documents | paperless | IMAGE_PAPERLESS | ghcr.io/paperless-ngx/paperless-ngx:latest | done -> 3.0.5 (2.x->3.x breaking change noted; multi-arch) |
| documents | paperless-postgres | IMAGE_PAPERLESS_POSTGRES | docker.io/library/postgres:16-alpine | done -> 16.15-alpine3.24 (multi-arch) |
| documents | paperless-redis | IMAGE_PAPERLESS_REDIS | docker.io/library/redis:7-alpine | done -> 7.4.10-alpine (redis 8 licence note; multi-arch) |
| documents | paperless-gotenberg | IMAGE_PAPERLESS_GOTENBERG | docker.io/gotenberg/gotenberg:8 | done -> 8.30.0 (multi-arch) |
| documents | paperless-tika | IMAGE_PAPERLESS_TIKA | docker.io/apache/tika:latest | done -> 3.3.0.0 (actively maintained, not abandoned; multi-arch) |
| documents | stirling-pdf | IMAGE_STIRLING_PDF | docker.io/frooodle/s-pdf:latest | done -> 2.14.3 (repo mid-migration to stirlingtools/stirling-pdf; multi-arch) |
| filebrowser | filebrowser | IMAGE_FILEBROWSER | filebrowser/filebrowser:v2.31.2 | done -> v2.63.23, BUT project is winding down (archives 2026-09-01) |
| git | forgejo | IMAGE_FORGEJO | codeberg.org/forgejo/forgejo:1.21 | done -> 1.21.11-2 digest-froze; EOL since 2024-06, migration to v15/v16 flagged as separate project |
| git | forgejo-runner | IMAGE_FORGEJO_RUNNER | code.forgejo.org/forgejo/runner:3.5.1 | done -> left at 3.5.1 (must move with server, not ahead of it) |
| home-automation | homeassistant | IMAGE_HOMEASSISTANT | ghcr.io/home-assistant/home-assistant:stable | done -> 2026.8.1 (multi-arch) |
| home-automation | mosquitto | IMAGE_MOSQUITTO | eclipse-mosquitto:2 | done -> 2.1.2-alpine (multi-arch) |
| home-automation | zigbee2mqtt | IMAGE_ZIGBEE2MQTT | koenkk/zigbee2mqtt:latest | done -> 2.13.0 (multi-arch incl. arm64) |
| identity | authelia | IMAGE_AUTHELIA | authelia/authelia:latest | done -> 4.39.20 (multi-arch incl. arm64) |
| jellyfin | jellyfin | IMAGE_JELLYFIN | lscr.io/linuxserver/jellyfin:latest | done -> 10.11.11 (multi-arch) |
| media | sonarr | IMAGE_SONARR | lscr.io/linuxserver/sonarr:latest | done -> 4.0.19 (8d old, multi-arch) |
| media | radarr | IMAGE_RADARR | lscr.io/linuxserver/radarr:latest | done -> version-6.3.0.10514 (13d old, multi-arch) |
| media | prowlarr | IMAGE_PROWLARR | lscr.io/linuxserver/prowlarr:latest | done -> version-2.5.2.5491 (10d old, multi-arch) |
| media | bazarr | IMAGE_BAZARR | lscr.io/linuxserver/bazarr:latest | done -> 1.6.0 (4d old, multi-arch) |
| media | seerr | IMAGE_SEERR | ghcr.io/seerr-team/seerr:latest | done -> v3.4.1 (Jellyseerr/Overseerr merger, multi-arch) |
| media | flaresolverr | IMAGE_FLARESOLVERR | ghcr.io/flaresolverr/flaresolverr:latest | done -> v3.5.0 (active, not abandoned, multi-arch) |
| media | rdtclient | IMAGE_RDTCLIENT | rogerfar/rdtclient:latest | done -> 2.0.142 (GH Releases page stale, Docker tags active; multi-arch) |
| media | qbittorrent | IMAGE_QBITTORRENT | lscr.io/linuxserver/qbittorrent:latest | done -> 5.2.3 (5d old, multi-arch) |
| memos | memos | IMAGE_MEMOS | neosmemo/memos:stable | done -> 0.30.0 (multi-arch) |
| monitoring | prometheus | IMAGE_PROMETHEUS | prom/prometheus:latest | done -> v3.13.2 (multi-arch) |
| monitoring | grafana | IMAGE_GRAFANA | grafana/grafana:latest | done -> 13.1.3 (multi-arch) |
| monitoring | node-exporter | IMAGE_NODE_EXPORTER | prom/node-exporter:latest | done -> v1.12.1 (multi-arch) |
| monitoring | cadvisor | IMAGE_CADVISOR | gcr.io/cadvisor/cadvisor:latest | done -> v0.55.1; gcr.io registry deprecated/frozen, real latest (v0.60.5) only on ghcr.io |
| monitoring | uptime-kuma | IMAGE_UPTIME_KUMA | louislam/uptime-kuma:1 | done -> 1.23.17 (final 1.x; project now on 2.x) |
| notes | silverbullet | IMAGE_SILVERBULLET | ghcr.io/silverbulletmd/silverbullet:latest | done -> 2.10.0 (:latest already drifted from it; multi-arch) |
| photos | immich-server | (IMMICH_VERSION) | ghcr.io/immich-app/immich-server:${IMMICH_VERSION:-release} | done -> IMMICH_VERSION=v3.1.0 (takes effect without --apply; multi-arch) |
| photos | immich-ml | (IMMICH_VERSION) | ghcr.io/immich-app/immich-machine-learning:${IMMICH_VERSION:-release} | done -> matches v3.1.0 exactly (multi-arch) |
| photos | immich-redis | IMAGE_IMMICH_REDIS | docker.io/redis:6.2-alpine | done -> 6.2.23-alpine; Redis 6 is EOL, Immich now recommends valkey:8 |
| photos | immich-postgres | IMAGE_IMMICH_POSTGRES | docker.io/tensorchord/pgvecto-rs:pg14-v0.2.0 | done -> digest-froze deprecated image; Immich docs give exact VectorChord replacement |
| privacy | adguard | IMAGE_ADGUARD | adguard/adguardhome:latest | done -> v0.107.78 (security release, multi-arch incl. arm64) |
| privacy | gluetun | IMAGE_GLUETUN | qmcgaw/gluetun:latest | done -> v3.41.1 (v3.42 imminent per maintainer, re-check soon) |
| storage | samba | IMAGE_SAMBA | dperson/samba:latest | done -> NO SAFE PIN (abandoned ~5yr, digest-froze latest instead) |
| storage | minidlna | IMAGE_MINIDLNA | vladgh/minidlna:latest | done -> 1.3.10 (multi-arch incl. arm64) |

## Key findings from reading existing infrastructure before designing anything

1. **`packages/<pkg>/pins.env` is the real, functional mechanism.** `scripts/pin.sh`
   generates it (`--refresh`/`--apply`), `scripts/lib/render.sh` `render_pins()`
   sources it into the shell before `docker compose up`, and `docs/ARCHITECTURE.md`'s
   sequence diagram shows it in the boot path. This is where the curated data goes —
   not a new manifest field, not a new catalogue-level file.

2. **`scripts/pin.sh --refresh` throws the tag away.** `resolve_digest()` does
   `repo="${ref%%:*}"` and writes `repo@sha256:digest` with no human-readable tag,
   even though `UnpinnedImageTagsRule`'s own javadoc states the safe form is
   `postgres:16@sha256:abc123…` (tag *and* digest). This task's `pins.env` files
   keep the tag. `scripts/pin.sh` itself is not touched (out of scope; flagged as
   a follow-up for the owner).

3. **Bug found in `HardeningService.pinning()`** (packages/dashboard/backend/.../services/HardeningService.java:76):
   it checks `compose.repo().resolve("pins.env")` — a single file at the **repo
   root** — for existence/mtime to drive the dashboard's security-score "pinning"
   indicator. That is not where `scripts/pin.sh` or `render_pins()` ever write.
   The per-package files this task produces will make the CLI (`pin.sh --check`)
   and the runtime (`render_pins`) work correctly, but the dashboard's own
   hardening score will keep reporting `pinsFileExists: false` regardless,
   because it is looking in the wrong place. Confirmed by
   `HardeningControllerIntegrationTest` which literally writes/deletes a
   root-level `pins.env` fixture. **Not fixed here** — it's a Java change to a
   file other agents may be touching, and outside this task's brief. Flagged for
   the owner as the top thing a reviewer should check.

4. **Two images are already parameterised via `.env`, not hardcoded tags:**
   `packages/dashboard` (`AURORA_VERSION`, exempt — Aurora's own image) and
   `packages/photos` (`IMMICH_VERSION`, defaults to the floating `release` tag).
   For Immich, pinning today doesn't need `scripts/pin.sh --apply` at all —
   `render_pins` already exports whatever `pins.env` sets, and `IMMICH_VERSION`
   is a normal compose interpolation variable, so setting `IMMICH_VERSION=` in
   `packages/photos/pins.env` takes effect immediately, no compose.yml edit
   required. Recorded there rather than as `IMAGE_IMMICH_SERVER`.

5. **Digest method:** `docker buildx imagetools inspect --format '{{.Manifest.Digest}}' <ref>`
   for the digest; `docker buildx imagetools inspect <ref> | grep Platform` for
   multi-arch (confirmed working locally, e.g. `caddy:2-alpine` -> 6 platforms
   including `linux/amd64` and `linux/arm64/v8`). `docker manifest inspect --verbose`
   deliberately avoided per the task brief (per-platform digests, not the
   `RepoDigests` value).

## Status: complete

All 17 non-template, non-dashboard packages have a `pins.env.example`.
`dashboard` is exempt (Aurora's own image; see `UnpinnedImageTagsRule`
javadoc) and carries no file. Every tag recorded above was verified to
exist with `docker buildx imagetools inspect` before being written down;
none were guessed.

### No safe pin at all

- **`dperson/samba`** (storage) — no release in ~5 years, no semver
  tags, only stale arch-named tags. Digest-froze `:latest` rather than
  inventing a version. The owner should consider a maintained
  alternative image as a separate decision.

### Deprecated/frozen image or registry, current image kept but flagged

- **`gcr.io/cadvisor/cadvisor`** (monitoring) — registry frozen at
  v0.55.1 since the project moved to `ghcr.io/google/cadvisor` (now
  v0.60.5) around v0.53.0. Confirmed by probing gcr.io directly.
- **`docker.io/frooodle/s-pdf`** (documents) — mid-migration to
  `stirlingtools/stirling-pdf`; still receiving parallel pushes today
  (9 days old at check time) but will stop eventually.
- **`docker.io/tensorchord/pgvecto-rs`** (photos) — the exact image
  Immich's own docs say to replace with `ghcr.io/immich-app/postgres`
  + VectorChord. A database-engine migration, not a version bump.
- **`docker.io/redis:6.2-alpine`** (photos, immich-redis only) — Redis 6
  reached EOL 2025-08-31; Immich's own compose template has moved to
  `valkey/valkey:8`.
- **`filebrowser/filebrowser`** (filebrowser) — the project itself is
  winding down, archiving 2026-09-01, no releases after. Recommended
  the final release (v2.63.23) over the currently-pinned v2.31.2, but
  flagged the large jump for the operator to test.

### Breaking major-version cliffs — pinned to current major, not latest

- **`codeberg.org/forgejo/forgejo`** (git) — the single biggest finding
  in the task. `1.21` (semver-internal v6.0) has been dead since June
  2024; current stable is v16.0.2, nine major bumps on. Digest-froze
  `1.21.11-2` (no regression from today) and flagged the v15
  LTS/v16 migration as a deliberate, separate project — not performed
  here. Left `forgejo-runner` at its current `3.5.1` to match, since
  runner v8+ enforces workflow-schema validation the old server was
  never built to expect.
- **`louislam/uptime-kuma`** (monitoring) — `:1` resolves to 1.23.17,
  the final 1.x release; the project has moved on to an actively
  developed 2.x line. Pinned to 1.23.17 rather than 2.x.

### Not abandoned after all (checked, turned out fine)

- `apache/tika`, `kopia/kopia`, `ghcr.io/seerr-team/seerr`,
  `ghcr.io/flaresolverr/flaresolverr` and `rogerfar/rdtclient` all
  looked like plausible "ships only latest / abandoned" candidates
  going in. All five turned out to be actively maintained with a real
  version to pin — rdtclient's GitHub Releases page is misleadingly
  stale (shows 2023) even though its Docker tags are pushed monthly, so
  that one is worth a specific callout for whoever reviews this.

### Images with no multi-arch problem (all 40+ verified images support
both linux/amd64 and linux/arm64/v8, so the Optiplex/Pi split is not a
blocker anywhere in the catalogue)

### What a reviewer should check hardest

1. The Forgejo finding (`packages/git/pins.env.example`) — biggest
   scope, biggest risk if misread as "just bump the tag."
2. The `HardeningService.pinning()` root-`pins.env` bug — not fixed
   here, but it means adopting this data will not move the dashboard's
   own security score until that Java path is corrected separately.
3. `scripts/pin.sh --refresh`'s tag-discarding `resolve_digest()` — if
   the owner ever runs `--refresh` after adopting these `.example`
   files as real `pins.env`, it will overwrite the tag-carrying format
   with a bare-digest one, silently losing the human-readable tag this
   work added. The script needs a small change to agree with this data
   before the two are used together.
4. The Immich pair (photos) — two separate deprecated-image findings in
   one package, easy to skim past.
5. Anything marked "actively maintained after all" — worth a second
   look since it corrects an initial hypothesis rather than confirming
   one.
