# core

Always-on: Caddy (reverse proxy + local HTTPS) and Homepage (dashboard).

Every other package's vhost is served under `*.$DOMAIN`.

## First-run

1. Set `DOMAIN` in `.env` (default `aurora.local`).
2. `./scripts/up.sh core`
3. Install Caddy's root CA on client devices for HTTPS:
   `./scripts/get-caddy-root-cert.sh`

## Homepage

`homepage/config/*.yaml` is bind-mounted. Edit and restart the
container to reload; Homepage also watches for changes.
