# git

Self-hosted git via **Forgejo** (community fork of Gitea) plus a
**forgejo-runner** for CI.

## First-run

1. Copy `.env.example` to `.env`. Set `FORGEJO_ADMIN_USER`,
   `FORGEJO_ADMIN_EMAIL`, `FORGEJO_ADMIN_PASSWORD`.
2. `./scripts/up.sh core git`
3. Visit `https://git.$HOME_DOMAIN/` (or `http://<lan-ip>:3080/`).
   Sign in as the admin user from `.env`.

## Enabling CI (forgejo-runner)

The runner container starts on `up`, but stays unregistered until a
token is present. To register it:

1. In Forgejo, go to **Site Administration → Actions → Runners → Create
   new Runner**. Copy the token.
2. Put it in `packages/git/.env` as `FORGEJO_RUNNER_TOKEN=...`.
3. Recreate the container so it picks up the token:
   ```
   docker compose -p home <all -f flags> up -d --force-recreate forgejo-runner
   ```
   (Easiest: re-run `./scripts/up.sh <same args as before>`.)

## Cloning

- HTTPS: `https://git.$HOME_DOMAIN/<user>/<repo>.git`
- SSH:   `ssh://git@<hostname>:2222/<user>/<repo>.git`

Port 2222 (not 22) so Forgejo doesn't fight the host's sshd.

## Ports

See `manifest.yml`.
