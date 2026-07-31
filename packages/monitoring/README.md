# monitoring

Prometheus + Grafana + node_exporter + cAdvisor + Uptime-Kuma.

## First-run

1. `cp .env.example .env` and set `GRAFANA_ADMIN_PASSWORD`.
2. `./scripts/up.sh monitoring`
3. Grafana at `https://grafana.$DOMAIN/` — the Prometheus
   datasource is auto-provisioned, so you can `+ Import` dashboard IDs
   straight away. Recommended starting kit:
   - **1860** — Node Exporter Full
   - **14282** — cAdvisor
   - **18283** — Kuma summary (if you export Kuma → Prom)
4. Uptime-Kuma at `https://uptime.$DOMAIN/` — set an admin
   password on the first-run wizard, then add monitors for each of
   your other services.

## Adding scrape targets

Edit `prometheus/prometheus.yml`. A few examples are commented in for
adguard, gluetun, caddy, uptime-kuma. Reload live with:

```
docker exec prometheus killall -HUP prometheus
```

## Persistent dashboards

Drop `*.json` (exported from Grafana) under
`grafana/provisioning/dashboards/`. Grafana rescans every 30s and
files there re-appear on a fresh volume.

## Ports

See `manifest.yml`.

## Integration

- `caddy.snippet` fronts grafana / prometheus / uptime on subdomains.
- `homepage.yml` fragment adds a "Monitoring" services group.
- Prometheus and cAdvisor URLs need no config beyond docker networking
  — everything scrapes over `aurora_net`.
