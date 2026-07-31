# core

Always-on: Caddy (reverse proxy + local HTTPS) and Homepage (dashboard).

Every other package's vhost is served under `*.$HOME_DOMAIN`.

## First-run

1. Set `HOME_DOMAIN` in `.env` (default `home.local`).
2. `./scripts/up.sh core`
3. Install Caddy's root CA on client devices for HTTPS:
   `./scripts/get-caddy-root-cert.sh`

## Homepage

`homepage/config/*.yaml` is bind-mounted. Edit and restart the
container to reload; Homepage also watches for changes.
