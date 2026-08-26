# snappymail (webmail)

[SnappyMail](https://snappymail.eu/) is a light, fast webmail client — the
lower-footprint alternative to Roundcube in the `webmail` variant group. It
is a **front end only**: the mail server (Stalwart) lives in the core
stack, and SnappyMail connects to it over IMAP on `aurora_net`.

## Maintenance warning (read this)

SnappyMail's last tagged release is **v2.38.2, October 2024**. The
repository is still committed to, but it has gone a long time without a
security release, and it has no SSO story. For a public-facing PHP app
that is a real risk. It is included because it is genuinely lighter and
nicer to use than Roundcube, but **Roundcube is the safer default** and
the recommended pick unless you specifically want the smaller footprint.

## First-run

1. **Create a mailbox on Stalwart** via its admin UI at
   `https://mail-admin.$DOMAIN/`.
2. `./scripts/up.sh core snappymail`
3. **Add the IMAP domain in SnappyMail's admin panel.** SnappyMail has no
   compose-level IMAP config; open `https://snappymail.$DOMAIN/?admin`. The
   admin password is written on first boot to
   `data/snappymail/_data_/_default_/admin_password.txt`. Add a domain
   pointing IMAP/SMTP at `stalwart` with STARTTLS (Stalwart requires TLS
   before it accepts a password).
4. Sign in at `https://snappymail.$DOMAIN/`.

## Two logins, by design

Same model as Roundcube: Authelia (password + 2FA) gates the front door,
then SnappyMail authenticates the mailbox over IMAP. Authelia proves the
person; SnappyMail needs the actual mailbox password to open the session.

## Storage

`data/snappymail/` holds config, contacts and the admin password. The mail
itself is Stalwart's, under `data/stalwart/`, backed up by core.

## Ports

None on the host. Reachable only via Caddy at `snappymail.$DOMAIN`, behind
Authelia. Its own vhost (not `mail.$DOMAIN`) so it and Roundcube can both
run without colliding.
