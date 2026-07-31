# backup

Kopia-based backups with a web UI, encrypted at rest, deduplicated.

## Repository

**Default:** local filesystem at `data/backup/repository/` on this
host. Useful for testing but does not protect you from disk loss —
plan to swap to a remote target as soon as you're happy.

**Remote (recommended):** point Kopia at Backblaze B2, S3, R2, GCS,
Azure, SFTP or WebDAV. Two ways to configure:

1. **From the UI** (easiest): open `https://backup.$HOME_DOMAIN/`,
   click **Reconnect** → pick provider, paste creds.
2. **Auto-connect on start**: set `KOPIA_REPOSITORY` in `.env` to a
   full CLI arg string (see `.env.example` for examples for B2/S3/SFTP).

## What to back up

Kopia sees the whole host at `/data` (read-only). Snapshot sources
worth adding on day one:

| Path (inside container)           | Why                             |
|-----------------------------------|---------------------------------|
| `/data/home/<user>/home.local/group_vars/all.yml` | machine identity |
| `/data/home/<user>/home.local/packages/*/.env`    | all secrets      |
| `/data/home/<user>/home.local/data/*/config`      | per-app configs  |
| `/data/home/<user>/home.local/data/caddy`         | root CA & certs  |
| `/data/etc`                                       | host config      |

**EXCLUDED** by the default policy (`policies/default-policy.json`):

- `**/media/*` (movies/TV/downloads — reproducible, huge)
- `**/data/*/cache`
- `**/data/*/logs`
- `**/node_modules`, `**/.git/objects`
- `**/.cache`, `**/.local/share/Trash`

## First-run

1. `cp .env.example .env`; fill in `KOPIA_UI_PASSWORD` and
   `KOPIA_PASSWORD` (write it down — losing it means losing the repo).
2. `./scripts/up.sh backup`
3. Open `https://backup.$HOME_DOMAIN/`. If unconfigured, click
   **Create Repository** → **Filesystem** → path `/repository`,
   encryption password = `KOPIA_PASSWORD`.
4. Add snapshot sources from the list above.
5. Import `policies/default-policy.json` as a starting policy for the
   global default.
6. Set a schedule (nightly is a good start).

## Ports

See `manifest.yml`.

## Integration

- `caddy.snippet` fronts the UI on `backup.$HOME_DOMAIN` with a
  second basic-auth layer (defence in depth over Kopia's own auth).
- `homepage.yml` adds a Kopia tile with status widget.
