# memos

Memos is a lightweight self-hosted note stream: fast capture, tags,
Markdown, full-text search, on SQLite. It is the lighter alternative to
SilverBullet in the `notes` variant group; run either, or both.

## First-run

1. `./scripts/up.sh memos`
2. Open `https://memos.$DOMAIN/`. The first account you create is the
   admin. Memos has its own accounts; it is not behind Authelia.
3. Data and the SQLite database live under `../../data/memos`.

## Choosing between the notes variants

| | SilverBullet (`notes`) | Memos (`memos`) |
|---|---|---|
| Model | Wiki/PKM, plain `.md` files on disk | Memo stream, SQLite |
| Best for | Structured, linked knowledge | Quick capture, journalling |
| Extensible | Plug ecosystem | Simpler, focused |

## Ports

See `manifest.yml`.

## Integration

- `caddy.snippet` fronts the UI on `memos.$DOMAIN`.
- `homepage.yml` adds a Memos tile.
