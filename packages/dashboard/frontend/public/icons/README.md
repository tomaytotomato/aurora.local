# App icons

Per-app logos rendered on the marketplace and Installed cards. A package
opts in with an `icon:` slug in its `manifest.yml`; the dashboard resolves
that to `/icons/<slug>.svg` (see `packageIconUrl` in `api/packages.ts`).
A package with no `icon`, or a slug with no file here, falls back to a
category-initial tile, so a missing logo never breaks a card.

They are bundled rather than fetched at runtime because the box runs on a
LAN and is often offline.

Most logos come from the [dashboard-icons](https://github.com/homarr-labs/dashboard-icons)
collection; each logo is the trademark of its respective project. Two are
local: `aurora.svg` (Aurora's own mark, from `favicon.svg`) and `dev.svg`
(a plain code glyph for the dev sandbox).

SilverBullet (`notes`) and Samba (`storage`) have no logo in the set, so
they intentionally use the fallback tile.
