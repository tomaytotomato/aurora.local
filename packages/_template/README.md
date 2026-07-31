# TEMPLATE package

Short description of what this package provides.

## First-run

1. Copy the template dir: `cp -r packages/_template packages/<name>`
   then rename `<name>` throughout (`manifest.yml` at minimum).
2. Copy `.env.example` to `.env` and fill in required values.
3. `./scripts/up.sh <name>`
4. Access via `https://<name>.$HOME_DOMAIN/`.

## Ports

See `manifest.yml`.

## Notes

Anything package-specific worth documenting.
