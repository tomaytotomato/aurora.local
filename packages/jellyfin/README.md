# jellyfin

The media server. Sonarr and Radarr fetch the files; Jellyfin plays
them, to a browser, a smart-TV app, or a phone. Free, no account, no
paywalled features.

This is the default `media-player` variant. If Plex or Emby packages are
added later, the picker offers them as alternatives to this one.

## First-run

1. `./scripts/up.sh jellyfin`
2. Open `https://jellyfin.$DOMAIN/` and complete the setup wizard.
   Jellyfin has its own accounts; it is **not** behind Authelia (native
   clients can't follow a forward-auth redirect).
3. Add libraries pointing at:
   - `/data/tv` — the arr stack's TV root
   - `/data/movies` — the arr stack's film root
   - `/data/music`
   These are the same folders under `$MEDIA_ROOT`, mounted read-only.
4. In **Seerr** (`packages/media`), set the media server to this
   Jellyfin so requests reconcile against what you already own.

## Hardware transcoding

Software transcoding is CPU-heavy. The reference box's Intel iGPU does
QuickSync. To use it:

1. In `compose.yml`, uncomment the `devices:` and `group_add:` block
   under the `jellyfin` service. Set the GID to the host `render` group
   (`getent group render`).
2. `./scripts/up.sh jellyfin`
3. Jellyfin dashboard > **Playback > Transcoding**: set VAAPI or QSV,
   device `/dev/dri/renderD128`.

## Why not behind Authelia?

Jellyfin's TV and mobile apps authenticate against Jellyfin directly and
cannot complete an Authelia login redirect. Fronting it with forward-auth
breaks those clients. The `caddy.snippet` therefore proxies it without
`import authelia`. Keep it that way unless you only use the web UI.

## Environment

No `.env` is required: every value has a working default. To override,
create `packages/jellyfin/.env` with any of:

```
TZ=Europe/London
DOMAIN=aurora.local
# Must match packages/media so Jellyfin sees the same library folders:
MEDIA_ROOT=/home/bruce/media
JELLYFIN_PUBLISHED_URL=https://jellyfin.aurora.local
# Homepage widget API key (Jellyfin > Dashboard > API Keys), optional:
JELLYFIN_KEY=
```

## Ports

See `manifest.yml`.

## Integration

- `caddy.snippet` fronts the UI on `jellyfin.$DOMAIN`.
- `homepage.yml` adds a Jellyfin tile with a now-playing widget (needs a
  `JELLYFIN_KEY` API key from Jellyfin > Dashboard > API Keys).
