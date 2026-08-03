# git

Self-hosted git via **Forgejo** (community fork of Gitea) plus a
**forgejo-runner** for CI.

## Auth

Forgejo honors `Remote-User` / `Remote-Email` / `Remote-Name`
headers when Aurora manages SSO (see `sso:` in `manifest.yml`).
Signing into Aurora auto-provisions a matching Forgejo account on
first visit; repos are still per-user, so SSO removes the second
login page without touching Forgejo's ownership model. Public
repos remain public per Forgejo's own ACL.

The seeded `FORGEJO_ADMIN_USER` / `FORGEJO_ADMIN_PASSWORD` stay as
the emergency-access super-admin when Authelia is down. Git
clients that push via HTTPS still auth against Forgejo's own
credentials or personal access tokens — the reverse-proxy header
path applies to browser sessions only.

When SSO is disabled, sign in with the seeded admin from `.env`
— no change from pre-Phase-D behaviour.

## First-run

1. Copy `.env.example` to `.env`. Set `FORGEJO_ADMIN_USER`,
   `FORGEJO_ADMIN_EMAIL`, `FORGEJO_ADMIN_PASSWORD`.
2. `./scripts/up.sh core git`
3. Visit `https://git.$DOMAIN/` (or `http://<lan-ip>:3080/`).
   Sign in as the admin user from `.env`.

## Enabling CI (forgejo-runner)

The runner container starts on `up`, but stays unregistered until a
token is present. To register it:

1. In Forgejo, go to **Site Administration → Actions → Runners → Create
   new Runner**. Copy the token.
2. Put it in `packages/git/.env` as `FORGEJO_RUNNER_TOKEN=...`.
3. Recreate the container so it picks up the token:
   ```
   docker compose -p aurora <all -f flags> up -d --force-recreate forgejo-runner
   ```
   (Easiest: re-run `./scripts/up.sh <same args as before>`.)

## Cloning

- HTTPS: `https://git.$DOMAIN/<user>/<repo>.git`
- SSH:   `ssh://git@<hostname>:2222/<user>/<repo>.git`

Port 2222 (not 22) so Forgejo doesn't fight the host's sshd.

## Ports

See `manifest.yml`.
