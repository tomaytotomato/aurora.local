# Authelia can't start at all on a fresh install

## The bug

Clean install with `identity` enabled: `authelia` restart-loops.
`docker logs authelia`:

```
level=warning msg="Configuration: configuration key 'jwt_secret' is deprecated in
  4.38.0 and has been replaced by 'identity_validation.reset_password.jwt_secret':
  this has not been automatically mapped for you because the replacement key also
  exists and you will need to adjust your configuration to remove this message"
level=error msg="Configuration: identity_validation: reset_password: option
  'jwt_secret' is required when the reset password functionality isn't disabled"
level=fatal msg="Can't continue due to the errors loading the configuration"
```

This is a different bug from PR #23 (secret rotation not reaching a running
container — fixed already, on `main`). This one hits a container's very first
create, with real, non-empty secrets already in `packages/identity/.env`
(confirmed on the testbed: `docker inspect authelia` showed a proper 48-char hex
value for `AUTHELIA_JWT_SECRET`, not blank).

## Root cause

Authelia has *two independent* mechanisms that both act on the same
`AUTHELIA_JWT_SECRET` environment variable:

1. **Our own templating.** `compose.yml` sets `X_AUTHELIA_CONFIG_FILTERS=template`
   and `configuration.yml` reads the secret explicitly via
   `identity_validation.reset_password.jwt_secret: '{{ env "AUTHELIA_JWT_SECRET" }}'`
   — Authelia's Go-template config filter, applied to the file text before YAML
   parsing.
2. **Authelia's own built-in "environment secret" convention.** A small, fixed
   list of legacy env var names (`AUTHELIA_JWT_SECRET`, `AUTHELIA_SESSION_SECRET`,
   `AUTHELIA_STORAGE_ENCRYPTION_KEY`, a few others) are auto-mapped by Authelia
   itself onto configuration keys, with no template filter needed at all.
   `AUTHELIA_JWT_SECRET`'s auto-mapping target is the **deprecated top-level**
   `jwt_secret` key (that mapping predates `identity_validation` existing).

Both mechanisms are live at once, on the same env var, for the same secret.
Authelia detects the new key already has a value (from our template) and — per
the warning — declines to auto-copy the legacy value into it, which is by
itself harmless. But something in that same code path also fails to keep the
value our own template rendered: the standalone reproduction below proves the
new key ends up unset, and validation then fails with "option 'jwt_secret' is
required".

Proved with three standalone `docker run` reproductions against a scratch copy
of `configuration.yml` on the testbed (same image, same real secret values,
no compose/Aurora involved):

- Original file, as shipped → crashes with the exact log above.
- File with the `identity_validation.reset_password.jwt_secret` block removed
  entirely (relying solely on Authelia's own auto-mapping) → starts clean,
  no warning, no error.
- Original block kept, but the env var renamed to something outside
  Authelia's fixed legacy-secret list (`AURORA_IDENTITY_JWT_SECRET` instead of
  `AUTHELIA_JWT_SECRET`) → starts clean, **no warning at all** (not even the
  deprecation notice), because Authelia no longer recognises the var name as
  one of its own legacy secrets and there's only one mechanism acting on the
  value.

`storage.encryption_key` does **not** have this problem, checked directly:
`AUTHELIA_STORAGE_ENCRYPTION_KEY`'s auto-mapping target is `storage.
encryption_key` itself — the *current*, non-deprecated key, so both
mechanisms agree on the same destination and there's nothing to collide.
No warning or error about it appeared in any reproduction. The task brief's
note that storage.encryption_key "was also reported as required in an
earlier observation" refers to the older bug (commit b7895fd) where the key
was missing from `configuration.yml` outright — already fixed, unrelated to
today's crash, and confirmed not recurring.

## Fix

Keep the explicit `identity_validation.reset_password.jwt_secret` block (it's
the forward-looking, non-deprecated config shape, and the file's own comment
explains why it's written explicitly rather than left to chance: an Authelia
upgrade shouldn't silently start denying password-reset requests). Instead,
stop handing Authelia the exact env var name it treats specially: `compose.
yml` now passes the jwt secret to the container as `AURORA_IDENTITY_JWT_SECRET`
(still sourced from `AUTHELIA_JWT_SECRET` in `packages/identity/.env` — the
external contract, `.env.example`, `manifest.yml`'s `required_env`, and
`IdentitySecretsService` are all unchanged), and `configuration.yml`'s
template reads that new name instead.

Session secret and storage encryption key are untouched — they don't have
this collision, and renaming them for symmetry would just be more surface
for no benefit.

Also fixed in passing: `AutheliaConfigurationInvariantsTests.
snapshot_matches_source` computed its relative path one directory short
(`../../packages/...` from `packages/dashboard/backend` lands on `packages/
packages/...`, which never exists), so the drift check has always silently
skipped, even outside the documented docker-sandbox exemption. Real drift
had already crept in: the test's classpath snapshot of `configuration.yml`
was missing the `storage.encryption_key` line entirely. Fixed the path,
resynced the snapshot, confirmed the test now genuinely runs and catches
drift (it failed correctly against the un-synced snapshot before I fixed it,
then passed once synced).

## Verification (testbed)

- `./dev/testbed/up.sh destroy` then a clean `AURORA_TESTBED_PACKAGES="core
  dashboard identity" ./dev/testbed/up.sh install`.
- `authelia` reached `Up ... (healthy)`, no restart loop.
- `docker logs authelia` clean: no fatal, no deprecation warning, no secret
  value anywhere in the log.
- Caddy in front of it answers (checked the actual HTTP endpoint, not just
  container state).
- `scripts/rotate-secrets.sh --apply` still rotates and recreates the running
  `authelia` container afterwards (PR #23's fix intact — checked with a
  before/after secret hash, no plaintext ever printed).
- Backend suite: `mvn test` in `packages/dashboard/backend`, Java 25 — full
  709+ (see final count in the commit) green.
- `shellcheck -x -S style -e SC1091` clean on `bootstrap.sh scripts/*.sh
  scripts/lib/*.sh` (no shell touched by this fix, ran anyway as a baseline
  check).
