# dev

Browser-based **VS Code (code-server)** plus **Postgres 16** and
**Redis 7** for general dev work / prototyping.

## First-run

1. Copy `.env.example` to `.env`. Set `CODE_SERVER_PASSWORD`,
   `POSTGRES_PASSWORD`, `REDIS_PASSWORD`.
2. `./scripts/up.sh core dev`
3. Access:
   - code-server: `https://code.$HOME_DOMAIN/`
   - Postgres (from host): `psql -h localhost -p 15432 -U $POSTGRES_USER $POSTGRES_DB`
   - Redis (from host): `redis-cli -h localhost -p 16379 -a $REDIS_PASSWORD`

## Editing this repo from the browser

`~/home.local` is bind-mounted into code-server at
`/workspace/home.local`. Open that folder and you can edit the entire
repo from anywhere on the LAN.

**UID caveat.** code-server runs as UID 1000 by default (via
`PUID=1000` / `PGID=1000`). If your host user is *not* 1000, override
`PUID` and `PGID` in `.env` before first-up, or files created in the
container will be owned by the wrong host user.

## Connecting other packages to Postgres/Redis

Both are on `home_net`, so any other package's container can reach:

- `postgres:5432` — internal Postgres port (not the remapped 15432)
- `redis:6379`   — internal Redis port (not 16379)

Example compose snippet in another package:

```yaml
environment:
  - DATABASE_URL=postgres://dev:${POSTGRES_PASSWORD}@postgres:5432/dev
  - REDIS_URL=redis://:${REDIS_PASSWORD}@redis:6379/0
```

## Ports

Host ports are remapped so they don't collide with locally-installed
Postgres / Redis. See `manifest.yml`.
