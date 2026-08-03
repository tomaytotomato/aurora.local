# identity

Authelia SSO + 2FA (TOTP / WebAuthn) fronting Caddy.

Backend is intentionally simple: a YAML users database and a local
SQLite storage file. Zero external DB / LDAP. Good for one household.

## First-run

### 1. Generate secrets

Three high-entropy secrets are required. Paste each into `.env`:

    openssl rand -hex 32   # → AUTHELIA_JWT_SECRET
    openssl rand -hex 32   # → AUTHELIA_SESSION_SECRET
    openssl rand -hex 32   # → AUTHELIA_STORAGE_ENCRYPTION_KEY

### 2. Bootstrap the users database

    mkdir -p data/identity/authelia
    cp packages/identity/authelia/users_database.example.yml \
       data/identity/authelia/users_database.yml

Generate a real argon2id hash:

    docker run --rm authelia/authelia:latest \
        authelia crypto hash generate argon2 --password 'your-real-password'

Copy the `$argon2id$...` string into `data/identity/authelia/users_database.yml`
as the `password:` field. Repeat for each user.

### 3. Bring it up

    ./scripts/up.sh core identity

Then visit `https://auth.$DOMAIN/` and complete TOTP enrolment.
(If SMTP isn't configured, the enrolment link lands in
`data/identity/authelia/notification.txt` — grep it out and paste
it into your browser.)

## Protecting another package

Any package with a Caddy vhost can be gated behind Authelia by
adding **one line** inside the vhost block:

    import authelia

Full example — protecting Sonarr:

    https://sonarr.{$DOMAIN} {
        tls internal
        import authelia
        reverse_proxy sonarr:8989
    }

The `(authelia)` named route is defined in this package's
`caddy.snippet`, so `import authelia` only resolves when the
identity package is enabled. The bootstrap installer therefore
only injects `import authelia` into other packages' vhosts when
identity has been selected.

## 2FA enrolment flow

1. User signs in with password (`one_factor`).
2. Authelia forces registration on first login if the target policy
   is `two_factor` (which is the default in `configuration.yml` for
   everything except `$DOMAIN`).
3. Enrolment link is emailed (SMTP) or written to the filesystem
   notifier (no SMTP configured).
4. User scans the QR into their authenticator (or registers a
   WebAuthn key).

## Access control

Defaults in `authelia/configuration.yml`:

| Domain                    | Policy       |
|---------------------------|--------------|
| `auth.$DOMAIN`       | bypass       |
| `$DOMAIN`       | one_factor   |
| everything else           | two_factor   |

Edit `authelia/configuration.yml` `access_control.rules` to
customise per-subdomain.

## Ports

See `manifest.yml`. Authelia is bound to `127.0.0.1:9091` on the
host for local debugging only; real access goes through Caddy.
