# Ralph — Phase D shadcn Authelia SSO + RBAC migration log

**Branch:** `feat/d-authelia`
**Baseline:** `main` @ `697fb5b` (Phase D task spec + handover doc merged first).
**Task:** `/home/bruce/aurora-d-wt/RALPH_TASK_D_AUTHELIA.md` (authoritative spec, 16 items D0–D16).
**Handover:** `/home/bruce/aurora-d-wt/PHASE_D_HANDOVER.md`.

## Executive summary

Turn Aurora into the source of truth for user identity across every managed service. Aurora's SQLite users table gains a `role` column (`admin | user | guest`), a new `AutheliaService` projects the users into `packages/identity/authelia/users_database.yml` on every CRUD, and Caddy forward-auth gates every vhost whose manifest declares `sso.protect: true`. Notes migrates first as the pilot; then Grafana, Paperless, Forgejo, Home-Assistant.

Phase D inherits every Phase C primitive (Skeleton, Dialog, Select, Table, Toast, DropdownMenu, axios→Toast bridge) — the new `/users` view is basically a "compose the primitives from Phase C" exercise. No new shadcn components needed.

**Phase D specific guardrail** carried over from the task spec: any endpoint that mutates users MUST verify `role == admin` on the caller's session, and that guard MUST be unit-tested (not just wired via Spring Security config).

---

### iter-1 (2026-08-03) — D0 bootstrap

**Item:** D0 — worktree bootstrap + baseline verify.

**What happened.**
- Worktree created at `/home/bruce/aurora-d-wt` from `main @ 697fb5b`, branch `feat/d-authelia`.
- `npm ci` inside `packages/dashboard/frontend` via docker-run `node:22-alpine` — hydrated 258 packages so `vue-tsc` + `vitest` resolve locally instead of npx-installing mismatched versions inside the verify script.
- Baseline verify **5/5 green**:
  - 1 commit on `feat/d-authelia` since `697fb5b` (the shared parent commit — no branch-local commits yet).
  - Backend: `Tests run: 357, Failures: 0, Errors: 0, Skipped: 0` (docker-run maven).
  - Frontend typecheck: `vue-tsc --noEmit exit 0`.
  - Vitest: `Test Files 19 passed (19), Tests 169 passed (169)`.
  - Dockerfile: `docker build --check: no warnings`.
- Created this log file with the executive summary + iter-1 entry.

**No source changes this iter.** Bootstrap is meta-work; the first code commit lands in iter-2 (D1).

**Next.** D1 — users table `role` column + Flyway migration + backfill (existing admin → `admin`). Backend record change + one integration test that reads the migrated column.

### iter-2 (2026-08-03) — D1 users table role column

**Item:** D1 — `admin_user.role` column (`admin | user | guest`) + Flyway V3 migration + backfill.

**What changed.**
- **V3 migration** (`packages/dashboard/backend/src/main/resources/db/migration/V3__admin_user_role.sql`):
  - `ALTER TABLE admin_user ADD COLUMN role TEXT NOT NULL DEFAULT 'user'` — new rows default to the lowest-privileged tier so a caller that forgets to specify a role can't accidentally create an admin.
  - Backfills every existing row to `admin` (v0.1/v0.2 always had one wizard-created admin).
  - Two `BEFORE INSERT / BEFORE UPDATE` triggers enforce the enum values — SQLite doesn't support adding CHECK constraints after `CREATE TABLE`.
  - Index `idx_admin_user_role` on the new column for the future `/api/users?role=admin` filter.
- **`domain/Role.java`** — enum with `GUEST < USER < ADMIN` ordering + `wireName()` / `fromWireName()` for the DB + REST JSON contract + `isAtLeast(other)` helper for policy checks. Doc-commented so future refactors don't accidentally reorder or flip the wire form.
- **`domain/AdminUser`** grows a `role` field.
- **`AdminUserRepo`** —
  - `RowMapper` picks up the new column with a defensive `.orElse(Role.USER)` fallback (DB triggers are still authoritative; the fallback exists only for defensive reads).
  - `create(..., Role)` new signature; the 3-arg overload without a role delegates to `create(..., Role.ADMIN)` so pre-Phase-D callers (OnboardingService wizard path, E2E reset flow) keep working with no churn.
  - `countByRole(Role)` backs the future "must keep at least one admin" invariant.
  - `updateRole(id, Role)` + `findAll()` for the incoming `/api/users` endpoint.
- **App config** (`application.yml`) — `spring.sql.init.schema-locations` extended to include V3. The project ships `spring.flyway.enabled: false` because Flyway 13 community dropped the SQLite dialect; `spring.sql.init` is the substitute and needs explicit file listing (no globs supported in Spring Boot 4). Comment updated so a future V4 lands in the same list.

**Tests (16 new).**
- `domain/RoleTests` — 6 tests: wire-format lowercase, case-insensitive parse, unknown-value rejection, `isAtLeast` privilege ladder, enum declaration order (belt-and-braces so a future PR reordering the enum triggers a screaming failure before Authelia group mapping goes weird).
- `persistence/AdminUserRepoRoleTests` — 10 tests, hand-rolled `SingleConnectionDataSource(':memory:')` + manual V1→V3 SQL replay (Spring Boot 4's `@SpringBootTest` DB path is broken by a pre-existing bean-override collision noted in `HealthControllerTests`). Covers:
  - V3 shape: role column exists with DDL default `user`, index created, backfill logic (round-trips the pre-V3 row insertion + re-runs the backfill).
  - CRUD: `create(..., Role)` persists role; 3-arg backward-compat overload defaults to ADMIN; `updateRole` flips values; `findAll` orders by id; `countByRole` per-tier.
  - Trigger enforcement: bypassing the enum with raw JDBC `INSERT ... role='superuser'` throws `DataAccessException` with `invalid role` message (Spring translates SQLite trigger errors to `UncategorizedSQLException`, not `DataIntegrityViolationException` — assertion tightened to the shared base).
- Fixed two pre-existing tests (`WeakAdminPasswordRuleTests`, `CurrentUserServiceTests`) that constructed `AdminUser` with the old 5-field record — added `Role.ADMIN` as the sixth arg.

**Manifest / callers left untouched.** `AuthService`, `AuthController`, `OnboardingService`, `CurrentUserService` all still compile — they don't yet read `.role()`. That's D8/D9 scope (user management API + gating). This iter is deliberately schema-only.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 373 tests (357 → 373, +16). Vitest 169 unchanged. vue-tsc clean. Dockerfile clean.

**Next.** D2 — `AutheliaService` that projects `AdminUserRepo.findAll()` into `packages/identity/authelia/users_database.yml`, atomic write, hooked into CRUD.
