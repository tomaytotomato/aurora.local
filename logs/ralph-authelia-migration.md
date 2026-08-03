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

### iter-3 (2026-08-03) — D2 AutheliaService projector

**Item:** D2 — `AutheliaService` projects Aurora users → `data/identity/authelia/users_database.yml`, atomic write, event-hooked reconcile.

**Design decisions.**
- **On-disk projection over Authelia API**. Authelia's file-based auth backend uses `watch: true` (see `packages/identity/authelia/configuration.yml`), so a rename-in-place triggers automatic in-container reload. Aurora just owns the file — no HTTP round-trips, no Authelia internal-API knowledge, same pattern as MdnsAliasService owning its avahi-publish subprocess fleet.
- **Cascading group membership** for the Role → Authelia groups mapping:
  - `ADMIN` → `[admins, users, guests]`
  - `USER` → `[users, guests]`
  - `GUEST` → `[guests]`
  - Lets Authelia ACL rules use `subject: group:users` to mean "user or admin" without repeating the list. Consumed in D4's `configuration.yml` finalisation.
- **Atomic write** via `Files.createTempFile()` in the same directory + `Files.move(tmp, target, ATOMIC_MOVE + REPLACE_EXISTING)`. Authelia's file watcher fires on inotify; a partial write would 500 login attempts for the beat between truncate and last flush. Tmp file is cleaned up on failure.
- **Idempotent rendering**: byte-for-byte identical output for the same user set. Pinned by a test — if a future change accidentally introduces a timestamp inside the yaml, we'd flap Authelia's watcher every reconcile.
- **Reconcile cadence**: on `ApplicationReadyEvent` + on every `UserChangedEvent` (see below) + every 5 minutes as a drift guard (someone hand-edits `users_database.yml` → gets fixed on next tick).

**New event: `UserChangedEvent`.**
- Payload-free record — projector re-reads the full users list each fire so a race between two mutations resolves cleanly and the yaml always matches the DB at projection time.
- Reason slot constants: `create / update / role-change / password-rotate / delete / startup / reconcile` — log-scrutable and grep-friendly.
- D8 controllers will publish it after every user CRUD. D2 just defines the event + the listener; there are no publishers yet.

**Defensive reconcile — critical fix mid-iter.** Initial version caught `IOException` only. When `AuroraApplicationTests.contextLoads()` ran, the boot-time `ApplicationReadyEvent` fired the reconcile BEFORE `spring.sql.init` had populated the schema (Spring Boot 4 timing quirk we already know about from D1). `users.findAll()` threw a `DataAccessException`, escaped `reconcile()`, aborted the context. Fixed by catching broadly (`Exception`) with an explicit doc comment explaining why — Aurora must not crash at startup because Authelia's projector hit a transient DB hiccup. Pattern matches how `MdnsAliasService` handles missing prerequisites.

**Tests — 13 new** (`services/AutheliaServiceTests`):
- **Groups mapping**: 3 tests covering the ADMIN/USER/GUEST cascade.
- **`renderYaml` pure function**: parseable via SnakeYAML round-trip; displayname is title-cased first char; email defaults to `<username>@aurora.local`; banner comment ("REGENERATED automatically") present so operators know not to hand-edit; empty user set still produces valid YAML (Authelia tolerates empty `users:` → fail-closed).
- **`atomicWrite`**: no `.tmp` left behind on success; replaces existing target atomically.
- **End-to-end `reconcile`**: writes to the repo-relative path (`{repo}/data/identity/authelia/users_database.yml`); creates parent directories on first run; updates `lastWriteAt` + clears `lastError`; idempotent second run produces byte-identical output; write failure sets `lastError` sentinel + returns `-1` without throwing.

Tests are hermetic — no Spring context, `@TempDir` per-test, `AdminUserRepo` mocked. Every temp-file/rename path is exercised for real, so Authelia's watcher behaviour on the live box will match what the test observed.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 386 tests (373 → 386, +13). Vitest 169 unchanged. vue-tsc clean. Dockerfile clean.

**Next.** D3 — `packages/identity/` secrets bootstrap. Generate the three Authelia secrets (`AUTHELIA_JWT_SECRET`, `AUTHELIA_SESSION_SECRET`, `AUTHELIA_STORAGE_ENCRYPTION_KEY`) via `openssl rand -hex 32` on first `identity`-enable, write to `packages/identity/.env`, emit an audit row when they rotate.

### iter-4 (2026-08-03) — D3 identity secrets bootstrap

**Item:** D3 — `packages/identity/` secrets bootstrap. Generate `AUTHELIA_JWT_SECRET`, `AUTHELIA_SESSION_SECRET`, `AUTHELIA_STORAGE_ENCRYPTION_KEY` (3× `openssl rand -hex 32`) on first identity-enable + audit rows on every generation / rotation.

**Design decisions.**
- **Managed the file, not the env vars.** Authelia reads the three keys from `packages/identity/.env` via docker-compose interpolation, so Aurora owns the file. Same pattern as the existing DOMAIN-in-packages/core/.env mutation in `OnboardingService.upsertCoreEnvDomain` — read → replace-or-append → write with owner-only perms.
- **Bootstrap only when identity is enabled.** `state.readState().enabled().contains("identity")`. Sarah who never enables SSO doesn't get useless keys cluttering an .env file she never opens.
- **`ensureSecrets()` is idempotent.** Only generates keys that are missing or blank. Second call is byte-identical no-op; audit row only fires on the first (or subsequent partial-fill) invocation.
- **`rotateSecrets(actingUserId)` is unconditional.** Regenerates all three, writes an audit row with the acting user id. Sessions in flight all invalidate; users bounce to Authelia login next request. Real intent required at the call site (D8 endpoint will guard with admin-role).
- **Comments + non-managed keys preserved on mutation.** The `.env.example` layout (SMTP block, TZ, DOMAIN) survives — only the three `AUTHELIA_*_SECRET=...` lines are rewritten. Pinned by a test.
- **Fail-closed** at startup. Same pattern as AutheliaService — catch broadly in `onReady()`, log, don't crash the dashboard because we couldn't touch one .env at boot.
- **Threat guard**: audit diff carries key *names* only, never the hex values themselves. Pinned by a test that reads the generated secret then asserts Mockito's captured diff string doesn't contain it.

**Constants.**
- `MANAGED_KEYS = ["AUTHELIA_JWT_SECRET", "AUTHELIA_SESSION_SECRET", "AUTHELIA_STORAGE_ENCRYPTION_KEY"]` — order matters for stable audit-log JSON.
- `SECRET_BYTES = 32` — Authelia's documented minimum (256-bit).
- Generation: `SecureRandom` → `byte[32]` → hex-encoded → matches `openssl rand -hex 32` output.

**Tests — 14 new** (`services/IdentitySecretsServiceTests`).
- **`identityEnabled`**: 3 tests (enabled, disabled, `enabled: null`).
- **`ensureSecrets`**: creates file from `.env.example`, generates all three; preserves comments + non-managed keys; idempotent second call; partial generation (only fills blanks); audit row only when a key was generated.
- **`rotateSecrets`**: regenerates every key even when present; audit row carries acting user id + `identity.secrets.rotate` action + `rotated_keys` diff; **never leaks secret values into audit diff** (real threat guard).
- **Secret shape**: 64 lowercase hex chars per call; 100 calls yield 100 distinct values (RNG not wired to a constant seed).
- **Perms**: `.env` written 0600 on POSIX filesystems; test gracefully skips when running on a non-POSIX host.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 400 tests (386 → 400, +14). Vitest 169 unchanged. vue-tsc clean. Dockerfile clean.

**Next.** D4 — `packages/identity/authelia/configuration.yml` finalisation: access-control rules driven from the manifest `sso:` block (see D5), session cookie scoped to `.{$DOMAIN}` so it federates across subdomains, redirection URL back to the requesting service.

### iter-5 (2026-08-03) — D4 Authelia configuration.yml finalisation

**Item:** D4 — `packages/identity/authelia/configuration.yml` finalisation. Access-control rules that respect the D2 role → groups cascade, session cookie scoped to the apex `.{$DOMAIN}` for federation across every subdomain.

**What changed in `configuration.yml`.**
- **Apex is now `bypass`** (was `one_factor`). Aurora runs its own username+password flow with server-side sessions at the apex domain. Letting Authelia gate the apex too would have the two auth systems fight for every request — chicken-and-egg on the login form itself. Bypass at Authelia, gate at Aurora.
- **auth.{DOMAIN} stays `bypass`** so the login portal is reachable pre-auth.
- **`*.{DOMAIN}` default remains `two_factor`.** Per-package overrides (via manifest `sso.min_role`) will layer on top when D6 emits the per-vhost matcher.
- **New `identity_validation` block** — Authelia 4.38+ refuses to boot without it for reset-password flows. Explicit `jwt_secret: '{{ env "AUTHELIA_JWT_SECRET" }}'` so an image upgrade doesn't silently start denying reset requests.
- **New `totp:` block** with 6-digit / 30-second / 1-skew defaults. Matches every mainstream authenticator app (Google Authenticator, Aegis, 1Password).
- **New `webauthn:` block** enabling passkeys. Recommended second factor over TOTP — no shared secret, phishing-resistant, works with platform authenticators (Touch ID / Face ID / Windows Hello) and hardware keys (YubiKey).
- **Session inactivity/expiration bumped** to 15m / 8h (was 5m / 1h). Homelab feel — Sarah shouldn't have to re-auth every time she gets distracted at her desk.
- **Full comment block** explaining the Aurora role → Authelia group cascade (`ADMIN → [admins, users, guests]`, `USER → [users, guests]`, `GUEST → [guests]`) so an operator peeking at the file understands what `subject: group:users` will mean once D6 emits per-package rules.
- **Cross-references** to the Java service names (`AutheliaService`, `IdentitySecretsService`) so a bug hunter can jump from yaml → Java in one grep.

**Tests — 9 new** (`identity/AutheliaConfigurationInvariantsTests`).
- `default_policy: deny` (fail-closed for unmapped subdomains).
- Auth portal + apex both `bypass` (login flows don't deadlock).
- Wildcard `*.DOMAIN` falls back to `two_factor`.
- Session cookie scope covers every subdomain (SSO federation invariant).
- `authentication_backend.file.watch: true` (Authelia reloads when Aurora's projector writes).
- argon2id parameters match Aurora's own hashing defaults so cross-side hash verification stays consistent.
- `identity_validation` block present with the JWT secret env reference.
- Managed secrets never appear literally in the file (repo-history threat guard).
- `snapshot_matches_source` — drift check between the test-resource copy and the source file (runs only when the sibling `packages/identity/` tree is visible; the verify script's maven container mounts only `packages/dashboard/backend`, so this drift check silently passes there, and the other 8 invariants still enforce the shape).

**Verify-script quirk documented.** `scripts/verify-v03-overnight.sh` mounts only `packages/dashboard/backend/` into `/app` for the maven container, so tests can't reach `../../packages/identity/authelia/configuration.yml`. Fixed by shipping a byte-for-byte snapshot at `src/test/resources/identity/configuration.yml` with a README explaining the sync rule + the drift-check test above.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 409 tests (400 → 409, +9). Vitest 169 unchanged. vue-tsc clean. Dockerfile clean.

**Next.** D5 — manifest `sso:` block schema (`protect`, `min_role`, `trusted_headers`) + fill in for Notes / Grafana / Paperless / Forgejo / Home-Automation. Backend parser + validator.

### iter-6 (2026-08-03) — D5 manifest sso: block

**Item:** D5 — manifest `sso:` block spec + fill in for Notes / Grafana / Paperless / Forgejo / Home-Automation.

**Schema.**
```yaml
sso:
  protect: true            # gate the vhost behind Authelia forward-auth
  min_role: user           # admin | user | guest (default: user)
  trusted_headers: false   # true when the service reads Remote-User / Remote-Groups
```

Absent block = `SsoBlock.DISABLED` (Authelia stays out of the request path). Design choices:

- **Package-level, not per-vhost.** Some packages ship multiple vhosts (monitoring: grafana + prometheus + uptime; documents: paperless + stirling-pdf). For now every vhost of a package inherits the same block. If a future need surfaces where one vhost wants trusted-header auth and another wants edge-gate only, we grow to a `vhosts: [{label, sso}]` shape in a later phase. Not premature.
- **Lenient bool coercion.** yaml authors write `true / "true" / yes / on / 1` interchangeably; parser accepts any. Same for `false`.
- **Unknown `min_role` values fall back to USER**, never crash. A fat-fingered manifest shouldn't lock every service into admin-only OR expose every service to guest. Middle tier is the safe default.
- **Unknown keys ignored silently** for forward-compat. A Phase E manifest that adds `sso.oauth_client: authelia` won't break a Phase D backend parsing it.

**Domain + parser.**
- New `domain/SsoBlock` record with `DISABLED` constant + `fromManifest(Object)` static parser.
- `domain/Package` grows an `sso: SsoBlock` 14th field.
- `PackagesService.parseManifest()` reads `m.get("sso")` via `SsoBlock.fromManifest()` — never null, always a valid SsoBlock.
- Three test-file call sites updated (`ServicesControllerTests`, `LaunchServiceBudgetHeaderTests`, `PackageStartBudgetTests`) pass `SsoBlock.DISABLED` since none of them exercise SSO logic.

**Manifest fills.** Every block ships a comment explaining the intent so an operator maintaining the package understands what Aurora is doing to their vhost.

| Package | protect | min_role | trusted_headers | Rationale |
|---|---|---|---|---|
| notes (SilverBullet) | true | user | false | SB_USER stays; Authelia gates at the edge. |
| monitoring (Grafana/Prometheus/Uptime) | true | user | true | Grafana natively supports auth.proxy (Remote-User / Remote-Groups); Prometheus + Uptime get edge-gated. |
| documents (Paperless/Stirling-PDF) | true | user | true | Paperless-ngx has `PAPERLESS_ENABLE_HTTP_REMOTE_USER=true` — auto-provisions the account on first visit. Household members can read receipts. |
| git (Forgejo) | true | user | true | Forgejo `ReverseProxyAuthentication` — auto-provisions on first visit. Public repos still public via Forgejo's own ACL. |
| home-automation (HA/z2m) | true | user | false | HA has a first-class auth system + trusted_networks; the trusted-header path is fiddly. Edge-gate + let HA handle inner session. z2m has no auth so Authelia is its only wall. |

Design note: **min_role: user** across the board for the first pass. Bruce is currently the only admin; multi-user with GUEST tier lands after there's an actual "share a Grafana dashboard with a house-guest" use case. Easy to bump packages to `min_role: admin` in a follow-up manifest edit; hard to undo a demoted access surface once someone's been visiting.

**Tests — 9 new** (`domain/SsoBlockTests`):
- Absent block → DISABLED default; non-Map input returns DISABLED (fail-safe).
- `DISABLED` field values.
- Full block parses every field.
- `min_role` defaults to USER when missing or unknown (both branches).
- `protect` coerces `true/"true"/yes/on/1` truthy set + `false/"false"/no/off/0` falsy set.
- `trusted_headers` uses the same coercer.
- Unknown keys ignored for forward-compat (Phase E-friendly).
- `min_role` case-insensitive.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 418 tests (409 → 418, +9). Vitest 169 unchanged. vue-tsc clean. Dockerfile clean.

**Next.** D6 — patch the Caddy snippet renderer (scripts/lib/render.sh) to emit `import authelia` inside `http(s)://` blocks when `sso.protect: true`. Also emit a per-vhost matcher for `min_role` group filtering.

### iter-7 (2026-08-03) — D6 Caddy snippet renderer

**Item:** D6 — patch the Caddy snippet renderer to conditionally emit `import authelia` when the manifest declares `sso.protect: true`.

**Design decision — new backend service, keep bash as bootstrap.** The existing `scripts/lib/render.sh` runs at `scripts/up.sh` time (before Aurora is up) and does a raw file copy — that's the bootstrap seed. Once Aurora is running, ownership transfers to `CaddySnippetService`, which knows the manifest sso block and injects `import authelia` accordingly. Caddy's `--watch` picks up the atomic rename within ~2s. Same architectural pattern as AutheliaService owning `users_database.yml` and IdentitySecretsService owning `.env`.

**Alternative (rejected):** teach `render.sh` bash to parse `manifest.sh sso.protect` and sed-inject. Would work, but sed-injecting bracket-scoped block content in bash is fragile and duplicating the logic between bash + Java would drift. One place owning the render is worth the "aurora needs to be up" soft dependency.

**Render shape.** For a package with `sso.protect: true`:
- Banner comment stamped at the top ("regenerated by Aurora … do not hand-edit").
- `sso: protect=true min_role=user trusted_headers=false` line so a debug session doesn't have to cross-reference the manifest.
- Every `http(s)://<label>.{$DOMAIN} {` header gets an `import authelia` line injected on the next line, at the block's inner indent.
- Everything else passes through verbatim.

For a non-protected package, the banner still lands (auditable trail), but no injection.

**What Aurora does NOT emit** at D6:
- `min_role` gating (belongs in Authelia's `access_control.rules` — D12 / grafana-specific once trusted-header services need it).
- `copy_headers` for trusted-header services — that's D12 territory once specific services get migrated.
- The reusable `(authelia)` snippet itself — that lives in `packages/identity/caddy.snippet` (D7).

**Prune policy.** Every reconcile lists `data/caddy/snippets/*.caddy` and deletes any file whose package is no longer enabled (or gone entirely). Prevents "stale snippet for a package Bruce disabled last week" ghosts.

**Fail-closed at boot.** Catch broadly in `onReady`, log, don't crash the dashboard. Snippet writes only fail on genuine filesystem trouble (permissions, disk full); the drift-guard at 60s retries.

**Tests — 13 new** (`services/CaddySnippetServiceTests`).
- **`render()` pure function**: injects on protect=true (twice — one per http:// and https:// header); passes through when disabled; banner stamps sso details; skips commented-out vhost lines; preserves indent style; **doesn't grow file by a trailing newline on every pass** (guards against the flap-reload trap Caddy's watcher would otherwise hit).
- **End-to-end `reconcile()`**: writes only for enabled packages; output contains `import authelia`; prunes stale snippets on disable; prunes stray files from previous installs; skips packages without a caddy.snippet; idempotent byte-identical on second pass; updates `lastReconcileAt` + clears `lastError`.

All tests hermetic — `@TempDir` per-test, `PackagesService` mocked, no Spring context, atomic-rename exercised for real.

**Verify.** `bash scripts/verify-v03-overnight.sh` → 5/5 green. Backend 431 tests (418 → 431, +13). Vitest 169 unchanged. vue-tsc clean. Dockerfile clean.

**Next.** D7 — finalise `packages/identity/caddy.snippet`. The existing file already has the `(authelia)` reusable snippet + auth.{DOMAIN} vhost. D7 confirms the `copy_headers` list matches every service Aurora will trust (Remote-User / Remote-Groups / Remote-Email / Remote-Name) + adds a `trust_forward` reciprocal for the case where Caddy runs behind another proxy.
