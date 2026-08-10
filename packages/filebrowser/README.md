# Files (FileBrowser)

A file manager that runs in the browser, over the shared storage pool.

## Why this exists

Samba (the `storage` package) is the right tool for real file access: it
mounts as a drive, it is fast, and every desktop understands it. It is
bad at exactly one thing, which is looking at a single file from a device
you have not set up — a phone, a work laptop, someone else's machine.
CasaOS ships a file manager for this reason and it is the one feature of
theirs worth copying.

## What it can reach

Everything under `/mnt/storage`, read and write. That includes other
apps' data directories if they live in the pool.

This is why the vhost is behind Authelia rather than relying on
FileBrowser's own login: anyone who can sign in to Aurora can move or
delete anything it can see, so it should be no easier to reach than the
dashboard itself.

Its own database (users, shares, settings) lives under
`data/filebrowser` and is what the `backup:` block protects. The files it
manages belong to the pool and are covered by the pool's own backup.

## First run

1. `https://files.<your-domain>/`, signing in through Authelia first.
2. FileBrowser's own default account is `admin` / `admin`. Change it
   straight away under Settings → User Management. It is behind Authelia,
   so this is a second lock rather than the only one, but leaving a
   default password anywhere is how these things go wrong.
3. If the box has no mergerfs pool, edit the `/srv` bind mount in
   `compose.yml` to point at whatever you do want browsable.

## Not included

Sharing links to the outside world. FileBrowser supports them; Aurora
does not encourage them, because a public link out of the storage pool
bypasses every other control on the box. If you want that, turn it on
knowing what it does.
