# notes (SilverBullet)

[SilverBullet](https://silverbullet.md/) is a hackable, markdown-native
personal knowledge system. Everything is a `.md` file in a folder
("space") — no database, no lock-in.

## First-run

1. `cp .env.example .env` and set `SB_USER` / `SB_PASSWORD`.
2. `./scripts/up.sh core notes`
3. Visit `https://notes.$DOMAIN/` and log in.

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
