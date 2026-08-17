# Admin password recovery — progress log

Branch `feat/admin-password-recovery`. Problem: no supported way to
recover a lost dashboard admin password; the only route so far was
`docker cp`-ing the SQLite file out, hashing with a throwaway
`httpd:2-alpine` container, and editing the row with Python's stdlib.

## 2026-08-17 — slice 1: backend CLI entry point + tests

### Design decisions

**Where it lives:** `scripts/reset-admin-password.sh`, alongside
`rotate-secrets.sh`. An operator already reaches for `scripts/*.sh` for
day-2 tasks; a `bootstrap.sh` subcommand would mix a one-off recovery
action into the installer's surface for no benefit. (Shell script comes
in slice 2 — this slice is the backend half it will call into.)

**Where the real work happens:** not in the shell script. Added
`com.tomaytotomato.aurora.cli.ResetAdminPasswordCli`, dispatched from a
plain argument check in `AuroraApplication.main()` — if `args[0]` is
`reset-admin-password`, it runs that and exits, bypassing
`SpringApplication.run()` entirely. Reasons this beats the alternatives
considered:

- **A helper container running `sqlite3` directly** (the `httpd:2-alpine`
  trick that caused this ticket) can write *a* row, but can't produce a
  hash `AuthService` will accept without hand-copying its BCrypt cost
  constant into a second place that will drift.
- **A full Spring Boot context in CLI mode** (`ApplicationRunner` bean)
  would reuse the app's own wiring, but `DockerEventService` connects to
  `docker.sock` from `@PostConstruct` at startup — a recovery tool that
  depends on `docker.sock`, D-Bus, and `/host/proc` all being mountable
  and healthy is a worse recovery tool than one that needs only the JVM
  and the SQLite file.
- **What was built instead:** `ResetAdminPasswordCli.openRepo()` builds
  a bare `DriverManagerDataSource` pointed at `AURORA_DB_PATH`, wraps it
  in a `JdbcTemplate`, and constructs the *actual* `AdminUserRepo` and
  `AuthService` classes from `com.tomaytotomato.aurora` — same SQL, same
  column mapping, same `BCryptPasswordEncoder(12)` cost as the running
  app, zero duplicated logic. No Spring context, no docker.sock, no
  D-Bus. Runs, does one thing, exits.

**Subcommands:** `list` (id/username/role/created, never a hash — solves
the forgotten-username case and lets an operator pick which admin to
reset when more than one exists) and `reset <username>` (reads the new
password as one line from stdin, never an argument or env var, so it
never appears in `ps` output).

**Multiple admins / last-admin invariant:** a password reset never
touches `role` or row count, so `UsersService`'s "can't demote/delete
the last admin" guard is simply not in scope — it only guards role
changes and deletes. `list` surfaces every admin so the operator picks
the right username; no new invariant needed.

**Zero users at all:** deliberately out of scope. `AdminUserRepo` empty
means the app's own onboarding wizard creates the first admin on next
visit — `list` says this in plain words rather than pretending to
handle a case the app already handles better.

**Restart after reset:** confirmed not needed by reading
`AuthController.login()` → `AuthService.authenticate()` →
`AdminUserRepo.findByUsername()` — every login re-queries the DB, no
cache anywhere in the JVM. Read `CurrentUserService` too (session
handling was off-limits to touch, not to read) to confirm role/session
lookups are equally uncached. Existing sessions stay valid until
logout/expiry, which is normal password-change behaviour, not something
this tool needs to work around.

**Never logs a secret:** `ResetAdminPasswordCli` only ever prints
usernames, roles, ids, and pass/fail text. Read `rotate-secrets.sh`'s
"suggested replacements" preview first specifically to see the leak
shape to avoid (it prints freshly-generated secrets to whatever
terminal/log captures its stdout) — not touching that file, per the
brief, but the new code is written the opposite way on purpose.

### What was written

- `packages/dashboard/backend/.../cli/ResetAdminPasswordCli.java` — the
  CLI logic (`list`, `reset`, dispatch, error handling incl. a plain
  message instead of a stack trace on "database is locked").
- `AuroraApplication.java` — one `if` before `SpringApplication.run()`.
- `ResetAdminPasswordCliTests.java` — runs the real V1/V3 migration SQL
  against a scratch SQLite file in a JUnit `@TempDir` (same shape
  `AdminUserRepoRoleTests` uses, but a file rather than `:memory:`
  because `openRepo()` opens a fresh `DataSource` per call — an
  in-memory DB would go blank between the migration connection and the
  CLI's own). Covers: a hash written by `reset()` verifies against a
  **fresh, independently-constructed** `BCryptPasswordEncoder(12)` (not
  borrowed from `AuthService` — the point is proving compatibility, not
  proving the two calls agree with themselves); the hash string starts
  `$2a$12$` as a belt-and-braces cost check; plaintext never appears in
  captured stdout/stderr; unknown username / short password rejected
  without touching the row; `list` never prints a hash; multiple admins
  all show up; full `run()` dispatch (usage, unknown subcommand, missing
  username).

`mvn test`: **759/759 passing** (full suite, Java 25 via
`temurin-25.jdk`) — no regressions, 12 new tests.

## 2026-08-17 — slice 2: shell script + docs

`scripts/reset-admin-password.sh`: `list` / `<username>` / `-h`.
Resolves the container in two steps — `docker exec` if `aurora` is
running, else a `--volumes-from aurora` helper container if it exists
stopped — and documents the manual `docker run -v aurora_data:/data`
fallback in `docs/OPERATIONS.md` for the case where the container's
been `docker rm`'d outright (not automated: at that point you also need
to know the image tag, and guessing wrong silently creates a *second*
`aurora_data`-shaped volume rather than failing loudly, which felt like
the wrong trade for an edge case this rare).

Password is always read via `read -rs` (hidden, interactive) and piped
to the container over stdin — confirmed via `shellcheck -x -S style -e
SC1091` (koalaman/shellcheck:stable, clean) that no password-bearing
variable is ever passed as an argument or interpolated into a command
Docker would echo.

Documented in `docs/OPERATIONS.md`: new table row, full section
matching the existing doctor/health/backup/pin/rotate-secrets style,
plus the authorisation-model and restart-not-needed explanations
written out in full (not just cross-referenced) since this is exactly
where an operator in the dark would look first.

### What was proved and how

- The hash `ResetAdminPasswordCli.reset()` writes verifies against a
  `BCryptPasswordEncoder` built independently at cost 12 — the exact
  cost `AuthService.BCRYPT_COST` uses (read, not modified).
- The CLI's list/reset/dispatch logic against a real on-disk SQLite
  file created fresh per test, migrated with the actual V1/V3 SQL.
- No plaintext password or hash ever reaches stdout/stderr (asserted in
  tests, not just claimed).
- Shell: `bash -n` and `shellcheck -x -S style -e SC1091` clean across
  `bootstrap.sh scripts/*.sh scripts/lib/*.sh`, including the new file.
- Full backend suite green: 759/759.

### What remains unproven without a live box

- `docker exec -i aurora java -jar /app/aurora.jar reset-admin-password
  ...` against the *actual* running container — no VM available in this
  worktree (explicitly off-limits; another agent has it). The command
  shape is right (matches the Dockerfile's `ENTRYPOINT` and the
  compose `container_name: aurora`), but never executed against a real
  container.
- The `--volumes-from`-based stopped-container path, likewise.
- Real-world SQLite lock contention: whether a `docker exec`'d reset
  actually collides with the running app's Hikari connection (pool size
  1) under load, versus the brief, un-contended case tested here. The
  code treats a locked-database error as a "try again" rather than
  retrying automatically — untested whether that's the right call under
  real concurrent traffic.
- Whether `docker inspect -f '{{.State.Running}}'` and
  `{{.Config.Image}}` behave identically across the Docker versions
  aurora.local actually targets (only exercised against whatever
  `docker` this sandbox has).
