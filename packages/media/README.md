# media

The classic *arr stack + a debrid-first downloader plus optional
torrent client behind VPN.

- **Sonarr / Radarr / Bazarr / Prowlarr** — automation.
- **Seerr** — requests.
- **RDTClient** — Real-Debrid/AllDebrid/Premiumize downloader.
- **Flaresolverr** — Cloudflare bypass for Prowlarr.
- **SABnzbd** — Usenet.
- **qBittorrent** (opt-in `torrent` profile) — routes through
  `packages/privacy` gluetun.

## First-run

1. `./scripts/up.sh core privacy media` (torrent profile:
   `./scripts/up.sh --torrent core privacy media`).
2. Wire Prowlarr → Sonarr/Radarr from Prowlarr's UI.
3. Add download clients in Sonarr/Radarr.
4. Point Seerr at your Jellyfin/Plex.
