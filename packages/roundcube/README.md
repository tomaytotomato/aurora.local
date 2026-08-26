# roundcube (webmail)

[Roundcube](https://roundcube.net/) is the default webmail for the box.
It is a **front end only**: the mail server (Stalwart) lives in the core
stack, and Roundcube connects to it over IMAP/SMTP on the internal
`aurora_net`. It is the default of the `webmail` variant group; SnappyMail
(`packages/snappymail`) is the lighter alternative.

## First-run

1. **Create a mailbox on the mail server first.** Roundcube has no
   accounts of its own — it authenticates against Stalwart. Add one via
   the Stalwart admin UI at `https://mail-admin.$DOMAIN/` (or its API).
2. `cp packages/roundcube/.env.example packages/roundcube/.env` (optional;
   every value has a working default).
3. `./scripts/up.sh core roundcube`
4. Visit `https://mail.$DOMAIN/` and sign in with the full address and the
   mailbox password.

## Two logins, by design

`mail.$DOMAIN` is gated behind Authelia (`sso: protect: true`). Reaching
the inbox is two steps:

1. **Authelia** — sign in as an Aurora user (password + 2FA).
2. **Roundcube** — sign in to the mailbox with its IMAP password.

They are not redundant: Authelia authenticates *the person* (and hides the
webmail from any unauthenticated LAN device), while Roundcube needs the
actual mailbox password to open the IMAP session. True single-login would
need Stalwart to trust Authelia's tokens as its directory, which makes
Stalwart reject inbound mail to any address that hasn't logged in yet —
not a trade worth making for a mail server.

## Internal TLS to Stalwart

Stalwart will not accept a plaintext password, so Roundcube connects with
STARTTLS (`tls://stalwart` on 143 / 587). Stalwart's certificate is
internal, so on first run the handshake may fail peer verification. If so,
add a Roundcube config override to skip verification for that internal hop
(the browser-to-Caddy hop is still real HTTPS). Point the connection
elsewhere by setting `WEBMAIL_IMAP_HOST` / `WEBMAIL_SMTP_HOST` in
`packages/roundcube/.env`.

## Storage

`data/roundcube/` holds Roundcube's sqlite (contacts, preferences) and
config. The mail itself is Stalwart's, under `data/stalwart/`, and is
backed up by core. The manifest's `backup:` block keeps this address book
and settings so they survive a rebuild.

## Ports

None on the host. The webmail is reachable only via Caddy at `mail.$DOMAIN`,
behind Authelia — a published port would bypass that gate on the LAN. The
mail protocol ports (25/143/465/587/993) belong to Stalwart in core.
