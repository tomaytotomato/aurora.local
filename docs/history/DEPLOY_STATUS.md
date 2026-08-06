# Deploy status — 2026-08-01

Live aurora container refreshed onto the latest `rename/aurora` build and
onboarding state reset so the wizard replays from `/onboarding/welcome`.

## What changed

- Rebuilt `aurora-dashboard:0.1.0` from source (commits `e2cc5ff` P1, `349c1c1` P2, `dc28c45` P3 now live).
- Recreated the `aurora` container via `docker compose up -d aurora`.
- Reset `.state.yml` — backup saved to `.state.yml.bak.1785594847`.
- Cleared `admin_user` + `onboarding.*` rows in `/data/aurora.db`
  (SQLite volume `aurora-dashboard_aurora_data`). Backup at
  `/data/aurora.db.bak.<ts>` inside the volume.

## Live checks (from inside the container — no host port publish)

```
$ docker exec aurora wget -qO- http://127.0.0.1:8090/api/health
{"db":true,"status":"ok","docker":"29.6.2"}

$ docker exec aurora wget -qO- http://127.0.0.1:8090/api/onboarding/status
{"bootstrap_mode":true,"complete":false,"step":"welcome"}
```

Copy-leak scan: index.html shell has zero hits for `(SSH|sudo |docker |bash |\./scripts/)`.
The Vue SPA bundle is served from inside `aurora.jar`; runtime copy assertions
are covered by the `no-cli-instructions.spec.ts` E2E suite (all green as of iter-3).

## Image + container refs

- Image ID: `sha256:c20f20f27f91eaf4adf541e154e6546b989967c2c69bb19a71980248c66532e8` (pre-rebuild handle) → rebuilt manifest `sha256:99c2375f5618bca6a511262ee4ab47f3733a588a6ef9c119fe4e1cbf04b139c8`
- Container recreated: 2026-08-01T14:32:42Z
- Health: `healthy`

## Access

The dashboard compose does not publish `8090:8090` on the host and no Caddy
reverse proxy is running on this box right now. Container IP is `172.18.0.2`
on the `aurora-dashboard_aurora_net` bridge. To reach it from your browser:

```
docker compose -f packages/dashboard/compose.yml stop aurora
# then edit packages/dashboard/compose.yml to add:
#   ports: ["8090:8090"]
docker compose -f packages/dashboard/compose.yml up -d aurora
```

…or bring up the site's Caddy fragment. Either way this is a follow-up nit
(pre-existing to this chain).

**Bruce: once port 8090 is reachable, refresh `aurora.local` (or `localhost:8090`) in your browser to start the wizard fresh.**

## Working-tree note

`.state.yml` is tracked in git and now differs from HEAD (no `installed_at`).
This is intentional — it's the reset. It is NOT committed by this chain.
