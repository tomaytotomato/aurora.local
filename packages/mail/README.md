# mail (docker-mailserver + Roundcube)

A self-hosted mailbox on your own box.
[docker-mailserver](https://docker-mailserver.github.io/docker-mailserver/latest/)
runs Postfix, Dovecot, Rspamd and Fail2ban in a single container;
[Roundcube](https://roundcube.net/) is the webmail front end at
`mail.$DOMAIN`. Accounts and messages are files under `data/mail/` — no
provider holds your correspondence.

## Why two services

docker-mailserver is IMAP/SMTP only; it has no web UI. Roundcube gives
you one, and it is the clickable tile on the dashboard. They talk over
the internal `aurora_net`, so the only encrypted-at-the-edge hop is your
browser to Caddy.

Alternatives considered: **Mailu** and **Mailcow** bundle webmail, admin
and antispam into one opinionated stack, but each wants to own its own
reverse proxy, which fights Aurora's shared Caddy. docker-mailserver +
Roundcube stays modular and merges cleanly into the `home` compose project.

## First-run

1. `cp packages/mail/.env.example packages/mail/.env` and set at least
   `MAIL_HOSTNAME` and `MAIL_POSTMASTER` to your domain.
2. `./scripts/up.sh core mail`
3. Create the first mailbox (this is also your webmail login):

   ```
   docker exec -it mailserver setup email add you@$DOMAIN
   docker exec -it mailserver setup alias add postmaster@$DOMAIN you@$DOMAIN
   ```

4. Visit `https://mail.$DOMAIN/` and sign in with the full address and
   the password you just set.

## Storage

Everything lives under `data/mail/`:

| Path                     | Holds                                   |
|--------------------------|-----------------------------------------|
| `maildata/`              | the mailboxes (the thing worth keeping) |
| `config/`                | accounts, aliases, DKIM keys, Rspamd    |
| `mailstate/`, `maillogs/`| runtime state and logs                  |
| `roundcube/`             | Roundcube's sqlite db + config          |

`maildata`, `config` and `roundcube` are declared in the manifest's
`backup:` block, so the backup package snapshots them. The logs and
transient state are not.

## Delivering to the outside world

A mailbox on the LAN works the moment the container is up. Being trusted
by the rest of the internet is a different job, and none of it is Aurora's
to grant:

- **Outbound port 25** is blocked by most residential ISPs, so
  server-to-server delivery silently fails. A relay/smarthost (a cheap
  VPS, or your registrar's SMTP) is the usual fix.
- **Reverse DNS (PTR)** on your public IP must match `MAIL_HOSTNAME`.
- **SPF, DKIM, DMARC** records at your DNS provider. Generate the DKIM
  key once your domain is set and publish the printed TXT record:

  ```
  docker exec -it mailserver setup config dkim
  ```

Until those exist, treat this as an internal mailbox.

## Hardening the internal TLS hop

On first run Roundcube talks to Dovecot/Postfix in plaintext over
`aurora_net` (there is no server certificate yet, so `tls://` would fail
the handshake). Once you set `MAIL_SSL_TYPE` and docker-mailserver has a
certificate, switch the webmail over in `packages/mail/.env`:

```
MAIL_IMAP_HOST=tls://mailserver
MAIL_IMAP_PORT=993
MAIL_SMTP_HOST=tls://mailserver
MAIL_SMTP_PORT=465
```

then `./scripts/up.sh core mail` to re-render.

## No SSO

Unlike `notes`, this package is **not** put behind Authelia. The webmail
login is the mailbox credential itself, so a forward-auth wall in front
would only add a second, redundant login. That is a deliberate choice in
`manifest.yml` (no `sso:` block), not an omission.

## Ports

See `manifest.yml`. SMTP/IMAP (25/143/465/587/993) are published on the
host; webmail is on 8030 behind Caddy at `mail.$DOMAIN`.
