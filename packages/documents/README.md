# documents (Paperless-ngx + Stirling-PDF)

Two complementary tools:

- **Paperless-ngx** — a searchable, tagged, OCR'd archive of every
  paper document that ever came into your house. Gotenberg handles
  office-file → PDF conversion; Tika extracts text from formats
  Tesseract can't.
- **Stirling-PDF** — a browser-based PDF Swiss army knife (split,
  merge, sign, redact, watermark, OCR, compress).

## First-run

1. `cp .env.example .env` and fill in:
   ```
   openssl rand -base64 48   # PAPERLESS_SECRET_KEY
   openssl rand -hex 24      # PAPERLESS_DB_PASSWORD
   ```
   Set `PAPERLESS_ADMIN_USER` / `PAPERLESS_ADMIN_PASSWORD`.

2. `./scripts/up.sh core documents`

3. Paperless: `https://paperless.$HOME_DOMAIN/` (or `http://<host>:8010`)
4. Stirling: `https://pdf.$HOME_DOMAIN/`     (or `http://<host>:8020`)

## Consumption folder

Drop PDFs / images into `data/documents/paperless/consume/` and
Paperless will OCR + ingest automatically. Handy targets:

- Samba share (see `packages/storage`) pointed at the consume dir.
- Scanner "Scan to Folder" over SMB.
- `rclone` mount from a cloud drive.

## OCR languages

Set `PAPERLESS_OCR_LANGUAGE` in `.env` to a space-separated list of
Tesseract language codes (e.g. `eng deu`). The default `eng` covers
US/UK English.

## Security notes

- **`PAPERLESS_SECRET_KEY` is a real secret**, not decoration. Keep it
  out of git (it already is via .env / .gitignore). Rotating it will
  log every user out but does not destroy documents.
- Stirling-PDF runs with `DOCKER_ENABLE_SECURITY=false` for a simple
  single-user setup. Front it with Authelia / Authentik (see
  `packages/identity`) if you expose it beyond LAN.

## Backups

Back up `data/documents/paperless/{data,media,export}` and
`data/documents/postgres`. Stirling-PDF is stateless — its config
directory is regenerable.

## Ports

- 8010 → Paperless-ngx
- 8020 → Stirling-PDF

(Everything else stays on `home_net`.)
