# notes (SilverBullet)

[SilverBullet](https://silverbullet.md/) is a hackable, markdown-native
personal knowledge system. Everything is a `.md` file in a folder
("space") — no database, no lock-in.

## First-run

### Option A — with Aurora SSO (recommended)

If you ticked "Single sign-on for services" in the Aurora onboarding
wizard, everything is already wired:

1. Aurora blanked `SB_USER` + `SB_PASSWORD` in `packages/notes/.env`
   so SilverBullet runs auth-less internally.
2. Caddy fronts `notes.$DOMAIN` with Authelia forward-auth.
3. `./scripts/up.sh core identity notes`
4. Sign into Aurora at `https://$DOMAIN/`. Click Notes. You land on
   SilverBullet without a second login.

Aurora's role model (admin / user / guest) gates access. The `notes`
package requires at least `user` (see `manifest.yml` → `sso.min_role`).

### Option B — standalone (no Aurora SSO)

1. `cp .env.example .env` and set `SB_USER` / `SB_PASSWORD` to a real
   value.
2. `./scripts/up.sh core notes`
3. Visit `https://notes.$DOMAIN/` and log in with those credentials.

SilverBullet's built-in single-user auth is the only wall in this mode.

## Storage

Notes live at `data/notes/silverbullet/`. Point `git init` at it and
commit — SilverBullet is happy to share the directory with git.
Alternatively rsync/restic it to your backup target.

## Mobile

There's no first-class native app. The web UI is a PWA:

- iOS Safari → Share → Add to Home Screen.
- Android Chrome → menu → Install app.

Both give you an offline-capable install with a splash screen.

## Ports

SilverBullet listens internally on 3000 but is published on **3030**
to avoid collision with AdGuard's :3000 first-run wizard.

## Extending

Plugs (extensions) are installed via the `PLUGS` page inside the app;
they're just URLs to `.plug.js` files. Ecosystem lives at
https://silverbullet.md/PLUGS.
