# Aurora marketplace catalogue

This directory produces the **marketplace index** — the signed, versioned
catalogue of apps Aurora fetches at runtime, separately from the
dashboard's own release cadence. See
[`docs/MARKETPLACE_HOSTING_PLAN.md`](../docs/MARKETPLACE_HOSTING_PLAN.md)
for the design and the reasoning behind it.

## What's here

```
marketplace/
  schema/marketplace-v1.json   the JSON Schema every index validates against
  scripts/compose.py           walk packages/*/ -> one index.json
  scripts/validate.py          validate an index against the schema
  scripts/sign.py              detached Ed25519 signature + sha256
  keys/marketplace-pub.*       the PUBLIC release key (committed)
  keys/marketplace-dev.*.pem   the PRIVATE dev key (gitignored, never committed)
  dist/                        the composed + signed artifact (gitignored build output)
```

## The pipeline

```
compose.py  ──▶  dist/index.json          (unsigned catalogue)
validate.py ──▶  (checks it against schema/marketplace-v1.json)
sign.py     ──▶  dist/index.json.sig       (detached Ed25519 signature, base64)
                 dist/index.json.sha256     (bare-eye checksum)
```

Run it locally:

```sh
python3 marketplace/scripts/compose.py            # or --resolve-digests
python3 marketplace/scripts/validate.py
python3 marketplace/scripts/sign.py               # uses the dev key
```

In CI, [`.github/workflows/marketplace.yml`](../.github/workflows/marketplace.yml)
runs the same three steps on every change under `packages/` or
`marketplace/`, signs with the `MARKETPLACE_SIGNING_KEY` repository
secret, and publishes `index.json` + `.sig` + `.sha256` as a GitHub
Release asset tagged `marketplace-<index_version>`.

## Single source of truth

The composer reads the live `packages/*/manifest.yml` (plus each
package's `compose.yml`, `.env.example`, `caddy.snippet`, `README.md`)
rather than a duplicated `marketplace/<slug>/` tree. That is a deliberate
departure from Phase 0 of the plan, which kept both: one source cannot
drift from another that does not exist. The embedded-bodies question
(plan open-question 1) is answered the same way the plan recommends —
**embedded**, so the whole catalogue airgaps as a single artifact.

## Image digest pinning

Every image reference is recorded with its `sha256:` digest when the
composer can resolve one (`--resolve-digests`, needs registry access).
When it can't — no network, or an env-interpolated tag like
`${FOO:-bar}` — the digest is recorded as `null` and the app is flagged
`unpinned`. `validate.py` warns about unpinned apps; `--strict-pinning`
turns that into a failure for a release that must be fully pinned.

Digest pinning is the catalogue's core security promise: it turns "the
latest catalogue" into "every box that installs today gets exactly these
bytes". A malicious index entry cannot make Aurora pull an image the
operator didn't agree to on the consent screen.

## Keys

- **`keys/marketplace-pub.ed25519.b64`** — raw 32-byte public key, base64.
- **`keys/marketplace-pub.ed25519.pem`** — same key, PEM, for humans / `openssl`.
- The dashboard pins the X.509 SPKI form at
  `packages/dashboard/backend/src/main/resources/marketplace/marketplace-pub.ed25519.spki.b64`.
- **`keys/marketplace-dev.ed25519.pem`** — the PRIVATE key, **gitignored**.
  Regenerate for a real deployment; never commit a private key. The
  production key lives only in the `MARKETPLACE_SIGNING_KEY` CI secret.

### Rotating the key

Rotating the signing key is a **dashboard release**: generate a new
keypair, pin the new public SPKI into the backend resources, update the
`MARKETPLACE_SIGNING_KEY` secret, and ship a dashboard build. Boxes on
the old build keep verifying against the old key until they update — the
seed index and cache remain valid throughout.

## Offline / seed

The dashboard build embeds the current signed index as a **seed**
(`backend/src/main/resources/marketplace/index.seed.json` + `.sig`). A box
with no cache and no internet still renders the catalogue it shipped with,
verified against the same pinned key. Refresh the seed whenever the shape
of the catalogue changes materially:

```sh
python3 marketplace/scripts/compose.py && python3 marketplace/scripts/sign.py
cp marketplace/dist/index.json     packages/dashboard/backend/src/main/resources/marketplace/index.seed.json
cp marketplace/dist/index.json.sig packages/dashboard/backend/src/main/resources/marketplace/index.seed.json.sig
```
