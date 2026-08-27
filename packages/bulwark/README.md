# bulwark (webmail)

[Bulwark](https://github.com/bulwarkmail/webmail) is a self-hosted webmail
suite (mail, calendar, contacts, files) built for the [JMAP](https://jmap.io)
protocol and [Stalwart](https://stalw.art/). It is a **front end only**:
the mail server (Stalwart) lives in the core stack, and Bulwark connects
to it over JMAP on the internal `aurora_net`. It is an alternative in the
`webmail` variant group; Roundcube remains the default, SnappyMail is the
lighter-weight third option.

Bulwark is a Next.js/Node app — heavier at runtime than Roundcube's PHP,
but it speaks JMAP natively (Stalwart's first-class protocol) and rolls
mail + calendar + contacts + files into one login.

## First-run

1. **Create a mailbox on the mail server first.** Bulwark has no accounts
   of its own — it authenticates against Stalwart. Add one via the
   Stalwart admin UI at `https://mail-admin.$DOMAIN/` (or its API).
2. `cp packages/bulwark/.env.example packages/bulwark/.env` (optional;
   every value has a working default).
3. `./scripts/up.sh core bulwark`
4. Visit `https://bulwark.$DOMAIN/` and complete Bulwark's own web-based
   setup wizard: accept the pre-filled JMAP endpoint
   (`http://stalwart:8080` — Bulwark reaches Stalwart internally on
   `aurora_net`), set an admin password, and finish.
5. Sign in with the full mailbox address and its password.

## Two logins, by design

`bulwark.$DOMAIN` is gated behind Authelia (`sso: protect: true`).
Reaching the inbox is two steps:

1. **Authelia** — sign in as an Aurora user (password + 2FA).
2. **Bulwark** — sign in to the mailbox with its JMAP password.

They are not redundant: Authelia authenticates *the person* (and hides
the webmail from any unauthenticated LAN device), while Bulwark needs the
actual mailbox password to open the JMAP session. True single-login would
need Stalwart to trust Authelia's tokens as its directory, which makes
Stalwart reject inbound mail to any address that hasn't logged in yet —
not a trade worth making for a mail server. Bulwark also has native
OAuth2/OIDC support, so an operator who wants one-login here can point
Bulwark's `OAUTH_*` settings at Authelia later; the default keeps the
same convention as Roundcube/SnappyMail.

## Internal JMAP to Stalwart

Bulwark's JMAP client runs server-side in Next.js, so it can reach
Stalwart on the internal service name (`http://stalwart:8080`) without
going back out through Caddy. That means it bypasses the Authelia gate on
`mail-admin.$DOMAIN` (fine — the request never leaves aurora_net) and it
does not need to trust Stalwart's internal certificate (there's no TLS on
the hop). Override with `BULWARK_JMAP_URL` in `packages/bulwark/.env` if
you point Bulwark at a Stalwart running somewhere else.

## Storage

`data/bulwark/` holds four things:

- `settings/` — per-user preferences (encrypted).
- `admin/` — operator-authored config (config.json, policy.json, admin
  password hash, plugins, themes, branding uploads). Can be remounted
  read-only after the setup wizard completes.
- `admin-state/` — runtime state (audit log, setup token, login
  timestamps).
- `telemetry/` — anonymous telemetry consent + a stable instance id.
  Telemetry is off by default.

The mail itself is Stalwart's, under `data/stalwart/`, and is backed up
by core. The manifest's `backup:` block keeps the settings, admin config
and audit log so they survive a rebuild.

## Ports

None on the host. The webmail is reachable only via Caddy at
`bulwark.$DOMAIN`, behind Authelia — a published port would bypass that
gate on the LAN. The mail protocol ports (25/143/465/587/993) and the
JMAP endpoint on :8080 belong to Stalwart in core.
