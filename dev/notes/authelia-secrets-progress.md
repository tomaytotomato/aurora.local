# Authelia secret rotation not reaching the running container

## The bug

`configuration.yml`'s secrets are fine as written — `identity_validation.
reset_password.jwt_secret` and `storage.encryption_key` both use Authelia's
own Go-template filter (`{{ env "AUTHELIA_JWT_SECRET" }}` etc, enabled via
`X_AUTHELIA_CONFIG_FILTERS=template` in `compose.yml`), and
`AUTHELIA_SESSION_SECRET` is picked up by Authelia's built-in env-var
secrets convention without needing a template reference at all. None of
that is broken.

The break is one layer down, in how the value that template resolves to
gets into the container in the first place. `compose.yml`'s `environment:`
block does:

```
- AUTHELIA_JWT_SECRET=${AUTHELIA_JWT_SECRET}
```

Docker Compose only evaluates `${AUTHELIA_JWT_SECRET}` once, at
container-*create* time, against whatever's in the invoking shell's
environment (or a project-directory `.env`, which this repo deliberately
routes around — see below). Once the container exists, that value is
baked in. `docker compose restart` / `docker restart authelia` (the
exact command `users_database.example.yml`'s own comment suggests) reuses
the container byte-for-byte — it does **not** re-read `${AUTHELIA_JWT_
SECRET}`, so it cannot pick up a new value.

`scripts/up.sh` gets this right for a fresh bring-up: it seeds
`packages/identity/.env`, runs `rotate-secrets.sh --apply` to fill any
blank/weak secret, then explicitly does
`set -a; . "$ef"; set +a` for every package `.env` *before* invoking
`docker compose ... up`, so the shell that calls compose already has the
real secret exported. Verified this with the real Docker Compose CLI
against a scratch copy of `packages/identity/compose.yml` (no VM needed —
`docker compose config` doesn't start anything): exporting the three
`AUTHELIA_*` vars beforehand resolves them correctly in the rendered
config; not exporting them resolves to `""`.

The gap is what happens *after* that first bring-up. `scripts/
rotate-secrets.sh --apply` — the only rotation path that's actually wired
today — rewrites `packages/identity/.env` on disk and stops. Nothing
recreates the running `authelia` container, so if you rotate a secret
while the stack is already up, the container keeps the *old* secret
indefinitely; you'd only notice the drift by diffing the file against
what Authelia is actually validating sessions with.

(`IdentitySecretsService.rotateSecrets()` — the Java-side rotation the
identity package's own README documents as `POST /api/identity/secrets/
rotate` — has the same "writes .env, never recreates the container"
shape, but it's moot in practice: no controller anywhere wires that
method to an HTTP route. It's fully unit-tested dead code today. Flagging
this as a separate, smaller finding rather than fixing it in this pass —
wiring a new admin-mutating, session-invalidating endpoint is more surface
than "make the one rotation path that's actually reachable work
correctly".)

## Fix

`scripts/rotate-secrets.sh`: after applying a rotation to a package's
`.env`, check whether that package has containers running under the
`aurora` compose project (`docker compose -p aurora -f <pkg>/compose.yml
ps -q`). If so, source the just-rewritten `.env` and run `docker compose
-p aurora -f <pkg>/compose.yml up -d --force-recreate`, scoped to that one
package's compose file (no `--remove-orphans`, so it can't touch anything
else). No-op with a warning if Docker isn't available, or if the package
isn't up yet (nothing to recreate on a box that hasn't been brought up —
the normal `up.sh` sourcing path already covers that case correctly).

Also fixed, in the same block: `--apply` used to print `diff -u
"$envf.bak" "$envf"`, which puts the freshly-rotated secret value in
plain text on stdout — exactly the kind of leak rotation exists to avoid.
Replaced with a list of the key *names* that changed, never values.

## What this doesn't cover

- `IdentitySecretsService.rotateSecrets()` is still unreachable from the
  dashboard UI (see above) — same root cause (write-then-nothing), but a
  new endpoint is a bigger, separately-reviewable piece of work.
- Authelia's own config schema is unverified against the real binary — no
  container run available here. Verified the compose-level variable
  interpolation with `docker compose config` (real Docker Desktop CLI,
  not the Lima testbed) instead.
- `docs/DASHBOARD_BRIEF.md` documents a planned `POST /api/packages/
  {name}/restart` as `docker compose restart <containers>` — per the
  above, `restart` alone would never be sufficient for secret rotation
  specifically; any future wrapper needs to recreate, not restart, for
  packages whose `.env` just changed.

## Progress

- [x] Traced the chain: `IdentitySecretsService` → `packages/identity/
      .env` → `compose.yml` interpolation → `configuration.yml`'s
      Go-template placeholders → Authelia's own template filter.
- [x] Reproduced the interpolation gap directly with `docker compose
      config` against a scratch copy of `compose.yml` (created and torn
      down outside the repo tree).
- [x] Fixed `scripts/rotate-secrets.sh` to recreate a running package's
      containers after applying a rotation, scoped to that package only.
- [x] Fixed the pre-existing secret-value leak in `--apply`'s diff output.
- [x] `shellcheck -x -S style -e SC1091` clean via the `koalaman/
      shellcheck:stable` image.
- [x] Backend integration coverage for `IdentitySecretsService` (real
      Spring context, real SQLite `audit_event`, `AuroraIntegrationTest`
      harness): `IdentitySecretsServiceIntegrationTest`, 8 tests.
- [x] `mvn test` green — 692/692 (684 existing + 8 new).
- [x] Documentation correction: `packages/identity/README.md`'s "Secrets
      rotation" section no longer claims a `POST /api/identity/secrets/
      rotate` endpoint exists — it doesn't, `rotateSecrets()` is
      unit/integration-tested but not wired to any controller.
