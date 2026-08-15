# Authelia can't start at all on a fresh install

## The bug (as reported)

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

It turned out to be the first of **three** stacked bugs, each hidden behind
the last. Fixing one just let the next one reach Authelia's startup checks.
All three are fixed and verified end to end on the testbed below.

## Bug 1 — the deprecated jwt_secret key collides with our own template

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
the warning — declines to auto-copy the legacy value into it. But something in
that same code path also fails to keep the value our own template rendered:
the new key ends up unset, and validation fails with "option 'jwt_secret' is
required" — even though a real, non-empty secret is set.

Proved with standalone `docker run` reproductions against a scratch copy of
`configuration.yml` on the testbed (same image, same real secret values, no
compose/Aurora involved):

- Original file, as shipped → crashes with the exact log above.
- File with the `identity_validation.reset_password.jwt_secret` block removed
  entirely (relying solely on Authelia's own auto-mapping) → starts clean, no
  warning, no error.
- Original block kept, but the env var renamed to something outside
  Authelia's fixed legacy-secret list (`AURORA_IDENTITY_JWT_SECRET` instead of
  `AUTHELIA_JWT_SECRET`) → starts clean, **no warning at all** (not even the
  deprecation notice), because Authelia no longer recognises the var name as
  one of its own legacy secrets and there's only one mechanism acting on it.

`storage.encryption_key` does **not** have this problem, checked directly:
`AUTHELIA_STORAGE_ENCRYPTION_KEY`'s auto-mapping target is `storage.
encryption_key` itself — the *current*, non-deprecated key, so both
mechanisms agree on the same destination and there's nothing to collide.
No warning or error about it appeared in any reproduction. The task brief's
note that storage.encryption_key "was also reported as required in an
earlier observation" refers to the older bug (commit b7895fd) where the key
was missing from `configuration.yml` outright — already fixed, unrelated to
this crash, and confirmed not recurring.

**Fix.** Kept the explicit `identity_validation.reset_password.jwt_secret`
block (it's the forward-looking, non-deprecated config shape, and guards
against a future Authelia upgrade silently disabling password reset).
Renamed only the container-facing env var to `AURORA_IDENTITY_JWT_SECRET`.
`packages/identity/.env`, `.env.example`, `manifest.yml`'s `required_env`
and `IdentitySecretsService` are all unchanged — the secret is still called
`AUTHELIA_JWT_SECRET` everywhere outside the container.

Also had to drop `compose.yml`'s `env_file: ../identity/.env` entirely:
confirmed with `docker compose config` that env_file forwards the literal
`AUTHELIA_JWT_SECRET` key straight from `.env` regardless of what the
`environment:` block renames it to, which would have silently reintroduced
the exact collision. The SMTP vars env_file existed for (so they're absent,
not empty, unless uncommented) now use Compose's bare shell-passthrough
form (`- AUTHELIA_NOTIFIER_SMTP_HOST`, no `=value`), confirmed with a
`docker exec authelia env` check to behave identically: present only if
actually exported in the invoking shell, genuinely absent otherwise. up.sh
already sources `packages/identity/.env` into its own shell before calling
compose, so this needed no change to up.sh itself.

Also fixed in passing: `AutheliaConfigurationInvariantsTests.
snapshot_matches_source` computed its relative path one directory short
(`../../packages/...` from `packages/dashboard/backend` lands on
`packages/packages/...`, which never exists), so the drift check has
always silently skipped, even outside the documented docker-sandbox
exemption. Real drift had already crept in: the classpath snapshot of
`configuration.yml` was missing the `storage.encryption_key` line entirely.
Fixed the path, resynced the snapshot, confirmed the test now genuinely
fails against an un-synced snapshot and passes once synced.

## Bug 2 — the users projector overwrites a bootable seed with an empty one

Fixing bug 1 let Authelia's *own* boot get further, and it immediately hit
a second, previously-hidden failure:

```
level=error msg="Error occurred running a startup check" error="error reading
  the authentication database: could not validate the schema: users: non zero
  value required" provider=user
level=fatal msg="One or more providers had fatal failures performing startup
  checks..."
```

`identity` is now mandatory, so its container comes up on every fresh install
before the onboarding wizard has created any admin. Aurora's users table is
empty at that point. `AutheliaService.reconcile()` runs unconditionally on
`ApplicationReadyEvent` and projects `users.findAll()` straight onto disk —
with zero users, that's `users: {}`. Authelia's file-based auth backend
refuses to start at all on an empty users block; this isn't the graceful
"nobody can log in" the old `renderYaml_empty_user_set_is_still_valid_yaml`
test comment assumed, it's a fatal crash loop.

This never surfaced before bug 1 was fixed, because Authelia never got past
config validation for long enough for anyone to notice what it did with the
users file.

**Fix.** `AutheliaService.reconcile()` now skips the write entirely when
`users.findAll()` is empty, logging instead, and leaves whatever's already
on disk alone. Updated/added tests in `AutheliaServiceTests` for the new
guard (no write, no audit row, an existing seed file survives untouched)
and fixed two write-failure tests that had relied on an empty list to reach
the write path, which the new guard now short-circuits before it gets
there.

## Bug 3 — the seed file's placeholder password isn't a real hash

With bug 2 fixed, Authelia now actually loads the seed file
`render_identity_seed` (scripts/lib/render.sh) copies from
`users_database.example.yml` on first install — and immediately hit a
third failure:

```
level=error msg="Error occurred running a startup check" error="error decoding
  the authentication database: error occurred decoding the password hash for
  'admin': argon2 decode error: provided encoded hash has an invalid format"
  provider=user
```

The example file's password field was literally the text
`$argon2id$v=19$m=65536,t=3,p=4$REPLACE_ME_WITH_REAL_HASH` — never valid
argon2 data, always going to fail Authelia's own decode check the moment it
got read. It never surfaced before because `AutheliaService`'s unconditional
overwrite (bug 2) always won the race and replaced it with `users: {}`
before Authelia's restart loop got far enough to parse this file's contents.

**Fix.** Generated a real argon2id hash of the literal password "changeme"
using the documented `authelia crypto hash generate argon2` command against
the real image, and used that in place of the placeholder text. Nobody
should treat this as a real login — the file's header says so explicitly —
it exists purely so Authelia has something schema-valid and decodable to
boot from until the onboarding wizard creates a real admin, at which point
`AutheliaService.reconcile()` projects over this file for good.

## The chown lines

```
chown: /config/configuration.yml: Read-only file system
chown: /config/users_database.example.yml: Read-only file system
chown: /config: Read-only file system
```

Confirmed benign, not assumed: these come from the official `authelia/
authelia` image's entrypoint trying to `chown` `/config` to the configured
PUID/PGID on every boot. `compose.yml` mounts that path `:ro` deliberately
(the static config lives in git; Authelia never needs to write to it, only
read `configuration.yml` and the seed file — `/data` is the separate,
writable mount for the SQLite DB and notifications). The chown fails, logs
this, and the entrypoint carries on regardless — present in every log
capture across all three bugs above, including the final clean run where
the container reaches `healthy`. Did not make the mount writable; that
would trade a harmless log line for a real weakening of the read-only
guarantee on a file that's checked into git.

## A finding outside this fix's scope

While proving rotation still works (below), noticed `scripts/
rotate-secrets.sh --apply` prints the newly-generated secret value in its
"suggested replacements" preview — that block runs unconditionally before
the `if (( APPLY ))` branch, so `--apply` mode logs the new secret to
stdout even though the per-key "applied; keys changed: ..." line correctly
omits values. Pre-existing behaviour, not introduced by this fix, and nothing
in this bug's own diagnosis or fix logs a secret anywhwere. Worth a follow-up
ticket — flagging rather than fixing here since it's unrelated to the
fresh-install boot crash this work is about.

## Verification (testbed, fully clean each time)

- `./dev/testbed/up.sh destroy` then a clean `AURORA_TESTBED_PACKAGES="core
  dashboard identity" ./dev/testbed/up.sh install`, repeated after each of
  the three fixes above and once more at the end for a completely clean
  final run with no manual intervention in between.
- Final run: `caddy`, `aurora` and `authelia` all reach `Up ... (healthy)`.
  No restart loop.
- `docker logs authelia` on the final clean run:

  ```
  time="…" level=info msg="Authelia v4.39.20 is starting"
  time="…" level=info msg="Log severity set to info"
  time="…" level=info msg="Storage schema is being checked for updates"
  time="…" level=info msg="Storage schema migration from 0 to 24 is being attempted"
  time="…" level=info msg="Storage schema migration from 0 to 24 is complete"
  time="…" level=info msg="Startup complete"
  time="…" level=info msg="Listening for non-TLS connections on '[::]:9091' path '/'" server=main service=server
  time="…" level=info msg="Watching file for changes" file=/data/users_database.yml service=watcher watcher=users
  chown: /config/configuration.yml: Read-only file system
  chown: /config/users_database.example.yml: Read-only file system
  chown: /config: Read-only file system
  chown: /config: Read-only file system
  ```

  No fatal, no error, no deprecation warning, no secret value anywhere.
- Confirmed via `docker exec authelia env` that the container never sees a
  literal `AUTHELIA_JWT_SECRET` variable at all (only the renamed
  `AURORA_IDENTITY_JWT_SECRET`), and that the SMTP passthrough vars are
  genuinely absent, not present-empty.
- Served for real, not just "container is up": `curl --resolve
  auth.aurora.local:443:127.0.0.1 https://auth.aurora.local/api/health`
  through the real Caddy container returns `{"status":"OK"}`, HTTP 200.
- Rotation (PR #23's fix) still works: weakened `AUTHELIA_SESSION_SECRET`
  in `packages/identity/.env` on the VM, ran `scripts/rotate-secrets.sh
  --apply`, confirmed via `docker inspect authelia --format
  '{{.State.StartedAt}}'` that the container was genuinely recreated
  (new start timestamp), and that it reached `healthy` again afterwards.
- Backend suite: `mvn test` in `packages/dashboard/backend`, Java 25 —
  721 tests, 0 failures, 0 errors.
- `shellcheck -x -S style -e SC1091` clean on `bootstrap.sh scripts/*.sh
  scripts/lib/*.sh` via the `koalaman/shellcheck:stable` image (no shell
  script touched by this fix; ran anyway as the required baseline check).
