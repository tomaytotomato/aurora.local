# Phase D handover — Authelia SSO + RBAC

**One-stop pickup for the fresh Ralph session.**

## What's already done

### Phase C (shadcn migration + all bonus primitives) — merged to `main`
- Every UI primitive under `src/components/ui/` migrated onto shadcn semantic tokens.
- Every legacy `--color-*` reference in `src/` and `main.css` retired.
- Bonus primitives shipped and battle-tested: Skeleton, Dialog, Select, Table, Toast+Toaster+axios-bridge, DropdownMenu.
- Baseline: `main @ c29f4fa`. 5/5 verify green.

### v0.3.x productionize — merged to `main`
- **mDNS alias service** (`MdnsAliasService`) — publishes `<label>.aurora.local` A-records via avahi for every enabled-package vhost. Live: `notes.aurora.local` resolves + Caddy routes to SilverBullet. `packages/dashboard/compose.yml` mounts `/var/run/dbus/system_bus_socket` + `/etc/machine-id` and runs with `security_opt: apparmor=unconfined` (see MdnsAliasService header + scratchpad followup).
- **Caddy `--watch`** — Caddy re-parses on any fsevent so new snippets go live in ~2s without a reload.
- **Settings → LAN aliases card** — live view + manual Reconcile button.
- **Axios → Toast bridge** — every silent 5xx / network failure now surfaces bottom-right (except `toast: false` opt-outs).

## What Phase D ships

**Goal:** Authelia SSO across every Aurora-managed service, with Aurora as the source of truth for users + roles (`admin | user | guest`), propagated to Authelia's `users_database.yml` on every change.

**Key architectural moves:**

1. **Aurora is the user directory.** New `role` column on the users table. All CRUD via `/api/users` (admin-role only). Aurora frontend gets a `/users` view for management.
2. **`AutheliaService` (backend)** rewrites `packages/identity/authelia/users_database.yml` on any user change. Argon2 hashes stay in Aurora's SQLite; the yaml file is a projection, not a source.
3. **Manifest `sso:` block.** Each package declares whether its vhost gets Authelia forward-auth (`sso.protect: true`), the minimum role needed (`sso.min_role`), and whether the service reads Remote-User headers (`sso.trusted_headers`). Snippet renderer honours it.
4. **Caddy forward-auth to Authelia** for every protected vhost. Session cookie scoped to `.{$DOMAIN}` so one login federates.
5. **Onboarding wizard grows one step**: "Single sign-on for services." Generates the three Authelia secrets, enables the identity package, seeds the admin user.
6. **Notes migrates first**, then Grafana / Paperless / Forgejo / Home-Assistant. Trusted-header auth where the service supports it; edge-gate everything else.

**The authoritative task spec is `RALPH_TASK_D_AUTHELIA.md` in the repo root.** Reference document; do not edit checklist boxes there — the loop-owned copy under `.ralph/` is what Ralph mutates iter by iter.

## Repo state pickup

**Baseline:** `main @ c29f4fa`.

**Kick-off:**

```
git worktree add -b feat/d-authelia /home/bruce/aurora-d-wt main
cd /home/bruce/aurora-d-wt

# Bootstrap the frontend deps once (same trick as Phase C iter-1) so
# vue-tsc + vitest can resolve locally in the verify script:
cd packages/dashboard/frontend
docker run --rm -v "$PWD":/w -w /w node:22-alpine sh -c 'npm ci'
cd -

# Baseline verify — must be 5/5 before Ralph starts:
bash scripts/verify-v03-overnight.sh
# Expected: 357 backend / 169 vitest / vue-tsc clean / docker check clean

# Start Ralph:
# (feed .ralph/RALPH_TASK_D_AUTHELIA.md.md to Ralph via the same
#  ralph_start invocation pattern Phase C used)
```

## Scratchpad followups still open at Phase D start

These are NOT Phase D scope but worth knowing:
- **Card `padded` default fix** (Vue-boolean-coerce quirk from C7 iter-9) — visual shift on PackageDetail overview cards. `withDefaults({ padded: true })`, standalone commit.
- **Aurora `apparmor=unconfined`** — mDNS depended on it. Ship a purpose-built profile that unlocks only D-Bus method calls to `org.freedesktop.Avahi` + the docker.sock methods aurora already uses. Deferred because Ubuntu AppArmor profiles are their own project.
- **Backfill `vhosts:` into every package `manifest.yml`** so `MdnsAliasService` can stop grep-parsing `caddy.snippet`. Overlaps with D5's manifest schema growth — plausibly bundled into a Phase D commit.
- **Caddy hot-reload alternatives** — currently on `--watch`. If it ever proves too aggressive, see scratchpad for LaunchService-driven and inotifywait-sidecar options.
- **HTTPS reach from the wizard's Done page** — Caddy root cert download button. Overlaps with Phase D: SSO doesn't work without HTTPS trust chain on client devices.

## Ground rules Phase D inherits from Phase C

- One item per commit, prefix `aurora:`.
- Every commit: `bash scripts/verify-v03-overnight.sh` stays 5/5.
- Push after every commit to `origin feat/d-authelia`.
- Do NOT touch the live worktree `/home/bruce/aurora.local` (Bruce runs the real box from there).
- Do NOT touch `.state.yml`, `packages/*/.env`, or `~/.aurora/`.
- Do NOT rebuild or restart docker containers — Bruce owns the post-merge rebuild.
- Preserve UX_SPEC copy contracts (§4 empty state, §5 error state).
- **New for Phase D:** any endpoint mutating users MUST verify `role == admin` on the caller's session. Guard tested in unit tests, not just wired via Spring Security config.

## Live-box context (for post-merge testing)

- Aurora runs at `http://127.0.0.1:8090` and `http(s)://aurora.local` on the LAN (`192.168.0.110`).
- Health probe: `curl http://127.0.0.1:8090/api/health` → `{"db":true,"status":"ok","docker":"29.6.2"}`.
- Live rebuild path: `cd /home/bruce/aurora.local/packages/dashboard && docker compose build && docker compose up -d`.
- Identity package needs manual first-enable + secrets. Wizard should handle it in D10 but until then, `openssl rand -hex 32` × 3 into `packages/identity/.env`.

## Contact / decisions Bruce still owes the loop

None at kick-off. If a fork appears mid-loop (e.g. "should we support LDAP as a source of truth alongside SQLite?"), Ralph writes to `DECISION_NEEDED.md` in the worktree root and pauses.
