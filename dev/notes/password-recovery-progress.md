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
