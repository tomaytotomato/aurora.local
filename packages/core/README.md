# core

Always-on: Caddy, the reverse proxy and local HTTPS terminator.

Every other package's vhost is served under `*.$DOMAIN`. The apex
`$DOMAIN` itself reverse-proxies to `packages/dashboard` (Aurora) —
Aurora is the dashboard; there is no separate tile-grid app to run
alongside it.

## First-run

1. Set `DOMAIN` in `.env` (default `aurora.local`).
2. `./scripts/up.sh core`
3. Install Caddy's root CA on client devices for HTTPS:
   `./scripts/get-caddy-root-cert.sh`
