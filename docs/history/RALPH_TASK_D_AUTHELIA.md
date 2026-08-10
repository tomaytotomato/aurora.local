# Phase D — Authelia SSO + RBAC

**Baseline:** `main` @ `c29f4fa` (post-Phase-C shadcn migration + mDNS alias service + caddy --watch).
**Worktree:** `/home/bruce/aurora-d-wt` (branch `feat/d-authelia`).
**Migration log:** `logs/ralph-authelia-migration.md` inside the worktree — append per-iter entries.

## Goal

Ship real single sign-on across every Aurora-managed service, backed by Authelia, with users + roles managed centrally in Aurora and propagated to Authelia's on-disk config. After this phase Bruce logs into Aurora once and every downstream service (Notes, Grafana, Paperless, etc.) trusts that session — no more per-service passwords, no more `admin / <random>` in every package `.env`.

## Non-goals (this phase)

- **OIDC/OAuth flows.** Authelia supports OIDC providers but we'll gate on forward-auth first; OIDC is a follow-up if a specific service demands it (Nextcloud, GitLab).
- **Email 2FA / password reset via email.** Requires SMTP setup; owner action, not shippable in a Ralph loop. Config schema will accept it, wiring stays TODO with a clear seam.
- **External user directories** (LDAP, SAML). File-backed `users_database.yml` is Authelia's canonical mode for homelab and matches the "Aurora is the source of truth" story.
- **Multi-tenancy.** Single Aurora box, single Authelia instance. Roles yes, org boundaries no.

## Checklist

- [ ] **D0.** Worktree bootstrap + baseline verify 5/5 (mirror Phase C iter-1).
- [ ] **D1.** Aurora users table gets a `role` column (`admin | user | guest`), migration + backfill (existing admin → `admin`), backend + Flyway + test.
- [ ] **D2.** `AutheliaService` (backend) — reads Aurora users, renders `packages/identity/authelia/users_database.yml` with argon2 hashes, group mapping (admin/user/guest → Authelia groups), atomic write. Hooked into user CRUD so any change re-renders immediately. Unit tests + integration test.
- [ ] **D3.** `packages/identity/` config bootstrap — Aurora generates the three secrets (`AUTHELIA_JWT_SECRET`, `AUTHELIA_SESSION_SECRET`, `AUTHELIA_STORAGE_ENCRYPTION_KEY`) at first `identity`-enable via `openssl rand -hex 32`, writes into `packages/identity/.env`. Adds a Vault-of-secrets audit row so operators can see when they were rotated.
- [ ] **D4.** `packages/identity/authelia/configuration.yml` finalisation — access-control rules driven from `packages/*/manifest.yml`'s new `sso:` block (see D5), session cookie scoped to `.{$DOMAIN}` so it federates across subdomains, redirection URL back to the requesting service.
- [ ] **D5.** Manifest `sso:` block spec — each package's `manifest.yml` declares:
  ```yaml
  sso:
    protect: true            # gate the vhost behind Authelia forward-auth
    min_role: user           # admin | user | guest (default: user)
    trusted_headers: false   # true when the service reads Remote-User / Remote-Groups
  ```
  Notes, Grafana, Paperless, Git (Forgejo), Home-Automation get this filled in.
- [ ] **D6.** Caddy snippet renderer patched to conditionally emit `import authelia` inside the `http://` + `https://` vhost blocks when `sso.protect: true`. Existing `packages/*/caddy.snippet` files rewritten to reference a common `authelia` snippet defined once in `packages/core/caddy/Caddyfile`.
- [ ] **D7.** `packages/identity/caddy.snippet` finalised — Authelia login vhost (`auth.{$DOMAIN}`) + the reusable `(authelia)` forward-auth snippet.
- [ ] **D8.** Aurora user management API — `GET/POST/PUT/DELETE /api/users` (admin-role only), password rotation endpoint, "invalidate all sessions" endpoint (must also invalidate Authelia sessions via its API or by nuking the session store).
- [ ] **D9.** Aurora frontend User management view — `/users` route (admin-only, sidebar link gated by role), Table (from C10.4) of users with CRUD, Dialog (from C10.2) for create/edit, DropdownMenu (from C10.7) for row actions, Toast (from C10.5) on success/failure. Reuses everything Phase C shipped.
- [ ] **D10.** Onboarding wizard — new step "Single sign-on for services" between Admin and Packages. Turns on the identity package + generates Authelia secrets + explains what happens. Skippable for advanced users who want to hand-wire Authelia.
- [ ] **D11.** Migrate `packages/notes` (SilverBullet) as the pilot — declare `sso: protect: true`, remove SB_USER/SB_PASSWORD prompts (still set to a random unusable value so SilverBullet's own auth is neutralised but not disabled), verify end-to-end: log into Aurora, click Notes from dashboard, land in SilverBullet without a second login.
- [ ] **D12.** Migrate Grafana + Paperless + Forgejo (Git) + Home-Automation to the same pattern with trusted-header auth where supported (Grafana `auth.proxy`, Paperless `PAPERLESS_ENABLE_HTTP_REMOTE_USER=true`, Forgejo `ReverseProxyAuthentication`). Fall back to "gated at the edge" for services that don't do trusted headers.
- [ ] **D13.** Frontend session-boundary polish — a "Sign out" from Aurora now also destroys the Authelia session; a "Sign out" from any downstream service redirects to Authelia logout then back to Aurora.
- [ ] **D14.** Audit rows — every user CRUD, every role change, every "propagated to Authelia" event surfaces in the audit log with a diff JSON blob.
- [ ] **D15.** Tests — backend user CRUD + role guard + Authelia propagator + Flyway migration; frontend User management view + role-gated sidebar + create/edit Dialogs; integration test that sends a real HTTP request to Authelia running in `docker compose --profile e2e`.
- [x] **D16.** Docs — `packages/identity/README.md` rewritten from hand-edited-yaml walkthrough to Aurora-managed-projection contract (Path A wizard flow + Path B existing-box flow, roles + group cascade table, manifest sso: block spec, session boundary, secrets rotation, emergency access, full Phase D audit-trail table, threat model). `docs/DASHBOARD_BRIEF.md` §7 gains /api/users CRUD + /api/onboarding/sso endpoints; §8 split into 8.1 (Aurora's own auth) + 8.2 (SSO across the box) covering roles, propagation, session boundary, trusted-header hardening, role guard, emergency access. §5 domain model gains Role + SsoBlock rows.

## Ground rules

- One item per commit, prefix `aurora:`.
- Every commit: `bash scripts/verify-v03-overnight.sh` stays 5/5.
- Push after every commit to `origin feat/d-authelia`.
- Do NOT touch `/home/bruce/aurora.local` (live worktree), `.state.yml`, `packages/*/.env`, or `~/.aurora/`.
- Do NOT rebuild/restart docker containers — Bruce owns post-merge rebuild.
- Preserve UX_SPEC §4 (empty state) + §5 (error state) copy contracts.
- **New guardrail (Phase D specific):** any endpoint that mutates users must require `role == admin` on the caller's session; test the guard, don't just assume Spring Security config catches it.

## Verification

Command: `bash scripts/verify-v03-overnight.sh`
Working directory: `/home/bruce/aurora-d-wt`
Environment: none (docker-run for maven + node).
Expected: 5/5 checks pass. Backend ≥ 357 tests (starting from post-D baseline). Vitest grows as new views + composables land.

Baseline (iter-1, to be recorded):
- N commits on feat/d-authelia since c29f4fa
- Backend: 357 tests, 0/0/0
- vue-tsc --noEmit exit 0
- vitest: 19 files, 169 tests passed
- docker build --check: no warnings

## Final Verification

- Exact monitor-rerunnable command: `bash scripts/verify-v03-overnight.sh`
- Working directory: `/home/bruce/aurora-d-wt`
- Required preserved artifacts:
  - `packages/dashboard/frontend/node_modules/` (bootstrapped via `npm ci` in iter-1)
  - `packages/dashboard/frontend/package-lock.json`
  - `packages/dashboard/backend/pom.xml` + backend sources
  - `packages/dashboard/Dockerfile`
  - `packages/identity/authelia/` (Authelia config directory)
- Result: **COMPLETE** — 2026-08-03 iter-18. `bash scripts/verify-v03-overnight.sh` in `/home/bruce/aurora-d-wt` (fresh shell, no env vars) → 5/5 green. Backend 479 tests / 0 failures / 0 errors / 0 skipped. Vitest 21 files / 183 tests passed. vue-tsc --noEmit exit 0. Dockerfile static check no warnings. 131 commits on `feat/d-authelia` since baseline `f9c4406`; HEAD `3e3af91` (D16 docs). All D0-D16 checklist items shipped, `logs/ralph-authelia-migration.md` complete.

## Threat-model notes

- Authelia's `users_database.yml` contains argon2-hashed passwords. Same protection posture as Aurora's SQLite users table.
- Session cookie is `Secure` + `HttpOnly` + `SameSite=Lax`, scoped to `.{$DOMAIN}` so `notes.aurora.local` inherits the auth from `aurora.local`.
- Forward-auth adds ~2ms per request (Authelia is a fast Go server). Cache TTL of session inside Authelia is 1h.
- Compromised Aurora backend can rotate users_database.yml and lock everyone out. Same threat surface as compromised aurora already gives (docker.sock, repo rw). Explicit non-goal to harden this further this phase.

## Iteration notes

### iter-1 (to be filled)
- Worktree bootstrap.
- `npm ci` in `packages/dashboard/frontend`.
- Confirm baseline verify 5/5.
- Create `logs/ralph-authelia-migration.md` with executive summary + iter-1 entry.
- Next: D1 — users table `role` column + Flyway migration.
