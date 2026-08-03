# dashboard — Aurora admin plane

Vue 3.5 + Spring Boot 4 single-container dashboard. Ships alongside the
other `packages/`; enabled the same way (`./bootstrap.sh add dashboard`).

## What it does (v0.1)

- **First-run wizard** — 9-step onboarding: welcome → admin → domain →
  packages → secrets → DNS → TLS → review → done.
- **Package list** — one screen for the whole `packages/` tree with
  status, category filter, enable/disable.
- **Package detail** — overview + `.env` editor + logs tail (M2).
- **System / health** — hostname, uptime, disk, memory, docker version,
  container list with health pills, live docker-event feed via SSE.
- **Auth** — password-only for v0.1; WebAuthn passkeys in v0.2.

## What it isn't

- Not a Homepage replacement. Homepage stays at `aurora.local`.
  Aurora lives at `admin.aurora.local`.
- Not multi-user. One admin per box.
- Not a metrics stack. `packages/monitoring` (Prometheus + Grafana)
  is the answer for that.

## Build + run

```sh
./bootstrap.sh add dashboard
```

The dashboard's own compose builds the image the first time it's
brought up (multi-stage: Node → Maven → Temurin JRE). Subsequent starts
reuse the cached image.

Local iteration for the dashboard code itself:

```sh
# backend (host needs Java 25)
cd packages/dashboard/backend
./mvnw spring-boot:run

# frontend (host needs Node 22)
cd packages/dashboard/frontend
npm install
npm run dev  # proxies /api → localhost:8090
```

## Layout

```
packages/dashboard/
  Dockerfile         multi-stage build
  compose.yml        aurora service, bind mounts, aurora_data volume
  manifest.yml       aurora.local package contract
  .env.example       AURORA_SESSION_SECRET (must be replaced)
  caddy.snippet      admin.$DOMAIN vhost
  homepage.yml       one tile on Homepage
  backend/           Spring Boot 4 (Java 25)
  frontend/          Vue 3.5 + Vite 8 + shadcn-vue
```

## Contract with the rest of aurora.local

- Reads `.state.yml`, every `packages/*/manifest.yml`, every
  `packages/*/.env` and `.env.example`.
- Writes `.state.yml` and `packages/*/.env` (never `packages/*/.env.example`).
- Never rewrites `compose.yml` — it shells out to
  `bootstrap.sh add/remove` for that.
- Reads `/var/run/docker.sock` via docker-java (list, inspect, events,
  stats).
- Reads `/proc` (bind-mounted read-only) for host metrics.

## Version

v0.1 — MVP scaffold. See [DASHBOARD_BRIEF.md](../../docs/DASHBOARD_BRIEF.md)
for the full roadmap (M1 through M5).
