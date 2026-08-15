# identity

Authelia SSO + 2FA (TOTP / WebAuthn) fronting Caddy.

**Phase D changed this package.** Aurora (the dashboard) is now the source of truth for users + roles across every SSO-protected service on this box. This package's `users_database.yml` is a projection of Aurora's SQLite users table, not a file you hand-edit. Secrets, Caddy snippets, and per-package `.env` blanks are all managed by Aurora too. This README describes the runtime shape; the wiring is documented in `docs/DASHBOARD_BRIEF.md` §7-8.

## First-run

### Path A — through the Aurora wizard (recommended)

Authelia is core infrastructure, not an optional app — `identity` is
locked on in **Step 4 of 9 — Pick your packages**, alongside `core` and
`storage`, the same way Caddy is. There's no separate SSO step to opt
into any more; once you confirm your package selection, Aurora:

- adds `identity` to the enabled packages list (already there, since
  it's mandatory, but this is idempotent);
- generates `AUTHELIA_JWT_SECRET`, `AUTHELIA_SESSION_SECRET`,
  `AUTHELIA_STORAGE_ENCRYPTION_KEY` (32 random bytes each) and writes
  them to `packages/identity/.env` with 0600 perms;
- blanks any `disable_env` keys in other packages (e.g. `SB_USER` in
  `packages/notes/.env`) so services run internal-auth-less behind
  Authelia's forward-auth;
- renders `data/caddy/snippets/<pkg>.caddy` with `import authelia`
  injected inside every vhost whose manifest declares
  `sso.protect: true`.

Continue through the wizard. On **Done**, `./scripts/up.sh` brings
Authelia + Caddy + your selected packages up together.

### Path B — re-running the secrets/snippet setup by hand

Identity can't be disabled from the wizard or the Packages page any
more, but if secrets ever need regenerating (e.g. after a manual
`.state.yml` edit), the same step Path A runs automatically can still
be triggered directly: `POST /api/onboarding/sso {"enable": true}`
during onboarding, or from the Aurora dashboard once it's up.
`./scripts/up.sh` (or Aurora's restart controls) brings Authelia up
afterwards.

### First TOTP enrolment

Visit `https://auth.$DOMAIN/` and log in with your Aurora admin username +
password. Authelia will prompt for TOTP enrolment on first login.

If SMTP is not configured, the enrolment link is written to
`data/identity/authelia/notification.txt`. Grep it out and paste it into
your browser.

## Managing users

Every user + role lives in Aurora's SQLite database. Aurora projects the
list into `data/identity/authelia/users_database.yml` on every change +
every 5 minutes as a drift guard. **Do not hand-edit that file** — the next
projection will overwrite it. The file's own banner comment says the same.

- **Dashboard**: sign in as admin → sidebar → **Users** → Add / Change
  role / Rotate password / Delete.
- **API** (admin-role required):
  - `GET    /api/users`
  - `GET    /api/users/{id}`
  - `POST   /api/users` — body `{ username, password, role, tz? }`
  - `PUT    /api/users/{id}` — body `{ role?, password? }`
  - `DELETE /api/users/{id}`

Aurora enforces the "at least one admin" invariant. Demoting or deleting
the last admin returns 422.

## Roles

| Aurora role | Authelia groups              | Typical use                                                        |
|-------------|------------------------------|--------------------------------------------------------------------|
| `admin`     | `admins`, `users`, `guests`  | Full control. Can create/delete users, rotate secrets.             |
| `user`      | `users`, `guests`            | Standard authenticated identity. Homestead members.                |
| `guest`     | `guests`                     | Read-mostly. Reserved for shared surfaces (Grafana view-only).     |

The cascade means an Authelia access-control rule with `subject:
'group:users'` matches both `user` AND `admin`. See
`AutheliaService.groupsFor()` for the Java-side truth.

## Protecting a package (`manifest.yml` `sso:` block)

Every package can declare an `sso:` block that Aurora reads on
enable/render:

```yaml
sso:
  protect: true             # gate the vhost behind Authelia forward-auth
  min_role: user            # admin | user | guest (default: user)
  trusted_headers: false    # true when service reads Remote-User headers
  disable_env: [SB_USER]    # keys to blank in the package .env when SSO is on
```

- `protect: true` → Aurora emits `import authelia` inside every
  `http(s)://<label>.{$DOMAIN}` vhost block for the package. Handled by
  `CaddySnippetService` (see D6).
- `trusted_headers: true` → service reads `Remote-User` / `Remote-Groups`
  / `Remote-Email` from Caddy's forward-auth response and auto-provisions
  the account. Grafana + Paperless + Forgejo do this today.
- `disable_env` → Aurora blanks these keys in `packages/<name>/.env`
  when SSO enable fires. Prevents services like SilverBullet from
  showing a second login page after Authelia already granted access.

## Access control (`configuration.yml`)

Baseline rules keyed off the group cascade above:

| Domain                | Policy       |
|-----------------------|--------------|
| `auth.$DOMAIN`        | bypass       |
| `$DOMAIN` (apex)      | bypass       |
| `*.$DOMAIN` (default) | two_factor   |

The apex `bypass` is important — Aurora runs its own username +
password + session cookie flow at `$DOMAIN`. Letting Authelia gate it
too would fight Aurora on every request to the login form. Per-package
`min_role` rules are emitted by Caddy at request time via the Remote-Groups
matcher (see the reusable `(authelia)` snippet in `caddy.snippet`).

## Session boundary

- Aurora's session cookie (`aurora.session`) is scoped to the apex domain.
- Authelia's session cookie (`authelia_session`) is scoped to
  `.{$DOMAIN}` so it federates across `*.aurora.local`.
- **Aurora logout bounces through Authelia's `/logout`** so the shared
  cookie is cleared server-side (D13). Otherwise a shared-computer
  next-user could walk into `notes.aurora.local` without a login prompt.
- Grafana / Paperless / Forgejo sign-outs redirect to Authelia's
  `/logout` too (env vars in their compose files).

## Secrets rotation

`POST /api/identity/secrets/rotate` (admin-role only, wired via the
`IdentitySecretsService`) regenerates all three Authelia secrets +
records a `identity.secrets.rotate` audit row. Sessions in flight all
invalidate; users bounce to Authelia login on their next request.

## Emergency access

Every service Aurora migrated in D12 keeps its **local super-admin** as
an emergency-access fallback for when Authelia is down:

- **Grafana**: `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` in
  `packages/monitoring/.env`.
- **Paperless**: `PAPERLESS_ADMIN_USER` / `PAPERLESS_ADMIN_PASSWORD` in
  `packages/documents/.env`.
- **Forgejo**: `FORGEJO_ADMIN_USER` / `FORGEJO_ADMIN_PASSWORD` in
  `packages/git/.env`.
- **Home Assistant**: HA's own account model always applies. Authelia
  edge-gates HA but doesn't replace its inner auth.

Direct-container access to Grafana etc. bypasses Caddy (and therefore
Authelia), so these credentials still work when the whole SSO surface is
down. Store them somewhere the emergency-recovery playbook can reach.

## Audit trail

Every action Phase D introduced writes an audit row (searchable via
Settings → Recent activity, or `GET /api/audit/events`):

| Action                       | Emitted by                                |
|------------------------------|-------------------------------------------|
| `users.create`               | `UsersService.create`                     |
| `users.role-change`          | `UsersService.updateRole`                 |
| `users.password-rotate`      | `UsersService.rotatePassword`             |
| `users.delete`               | `UsersService.delete`                     |
| `identity.secrets.bootstrap` | `IdentitySecretsService.ensureSecrets`    |
| `identity.secrets.rotate`    | `IdentitySecretsService.rotateSecrets`    |
| `onboarding.sso.enable`      | `OnboardingController.setSso`             |
| `onboarding.sso.skip`        | `OnboardingController.setSso`             |
| `sso.env.neutralise`         | `IdentitySecretsService.neutraliseServiceEnv` |
| `authelia.users.projected`   | `AutheliaService.reconcile` (user-driven only) |

Password hashes never appear in audit diffs (verified by unit tests).
The audit log is the single source of truth for "what happened when"
across the SSO surface.

## Threat model / non-goals

- File-backed users database means whoever can read
  `data/identity/authelia/users_database.yml` can lift the argon2/bcrypt
  hashes. Same protection posture as Aurora's SQLite users table.
- No LDAP / SAML / OIDC provider mode yet. File backend is Authelia's
  canonical single-household setup.
- No SMS 2FA. TOTP or WebAuthn only.
- Aurora backend compromise → attacker can rotate the users_database.yml
  and lock everyone out. Same threat surface as compromised Aurora
  already gives (docker.sock, repo rw).

## References

- Authelia docs: <https://www.authelia.com/>
- Aurora's Phase D migration log:
  `logs/ralph-authelia-migration.md` in the repo.
- Related code:
  - `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/AutheliaService.java`
  - `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/IdentitySecretsService.java`
  - `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/CaddySnippetService.java`
  - `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/controllers/UsersController.java`
