# photos (Immich)

Self-hosted photo/video backup with real mobile apps, timeline view,
face recognition and CLIP-based semantic search.

## First-run

1. `cp .env.example .env`, then generate secrets:
   ```
   openssl rand -hex 32   # JWT_SECRET
   openssl rand -hex 24   # DB_PASSWORD
   ```
2. Optionally point `UPLOAD_LOCATION` at bulk storage (e.g.
   `/mnt/media/photos`) — the library grows fast.
3. `./scripts/up.sh core photos`
4. Visit `https://photos.$HOME_DOMAIN/` and register. **The first
   registered account is the admin.**
5. Install the Immich app on your phone (App Store / F-Droid / Play)
   and point it at the same URL to enable auto-backup.

## Mobile app

- iOS: https://apps.apple.com/app/immich/id1613945652
- Android (Play): https://play.google.com/store/apps/details?id=app.alextran.immich
- Android (F-Droid): https://f-droid.org/packages/app.alextran.immich/

## Machine learning

`immich-ml` handles CLIP embeddings + face detection. The first pass
over an existing library can take hours on CPU; be patient. If you
have an NVIDIA GPU, swap the ML image for the CUDA variant (see
Immich docs).

## Backups

Two things to back up:

1. `../../data/photos/postgres` — the metadata database (small).
2. `$UPLOAD_LOCATION` — the actual originals (huge).

Restic or Kopia against both, ideally to an offsite target.

## Ports

Only 2283 is exposed; the rest of the stack is on `home_net` only.
