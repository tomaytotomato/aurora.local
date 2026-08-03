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
