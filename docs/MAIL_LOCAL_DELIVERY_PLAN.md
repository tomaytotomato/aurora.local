# Mail local delivery — the Aurora-owns-Stalwart-config plan (C27)

## What is broken

On a freshly installed box, SMTP :25 answers on the host and a full
`MAIL FROM` / `RCPT TO` / `DATA` transaction returns 250 all the way
through. Nothing arrives in any mailbox. The message row count in
Stalwart's Postgres store is unchanged before and after. `docker logs
stalwart` is completely empty. Ports :143 and :587 are advertised (in
compose and in the dashboard's "connect a mail client" card) but refuse
connections. So mail is accepted and silently discarded, and an
operator has no way of seeing any of it happen.

Root cause: in Stalwart v0.16 the wizard is what writes the working
configuration — hostname, listeners, local-delivery routing, tracers —
into the datastore as a set of JMAP objects. Aurora's `render_stalwart_config()`
correctly seeds the on-disk `config.json` with the datastore pointer, and
therefore correctly skips bootstrap mode (no more setup wizard). But
because the wizard never ran, the JMAP objects it would have written are
absent. Stalwart comes up in "normal" mode against an almost empty
registry: enough for SMTP on :25 to answer politely, not enough to
actually deliver.

## The doctrine call

`ESSENCE.md` is explicit about this shape:

> aurora already owns AdGuard's and Authelia's config for exactly this
> reason; mail is the outlier.

Authelia is 191 lines of Aurora-rendered YAML that the container reads
read-only, and the Users list is drift-reconciled from the dashboard's
own database. AdGuard is provisioned by `AdguardProvisionService` before
its first start. Stalwart currently gets nine lines describing the
datastore and then whatever the upstream defaults do — which turns out
to be nowhere near a working local-delivery server.

**Aurora should own the equivalent of the wizard's writes.** That is
what the doctrine says, and it also happens to be the honest fix: the
alternative is telling every Sarah on every fresh box to open a browser
tab to the Stalwart admin console, click through the wizard, and answer
questions like "which storage backend?" that Aurora already answered on
her behalf.

## The mechanism

v0.16 exposes every configuration surface as a JMAP object under the
`urn:stalwart:jmap` capability. The same JMAP endpoint that
`StalwartMailClient` already uses (POST `/jmap/`) is how the wizard
writes its own decisions. The management CLI (`stalwart-cli apply --file
plan.ndjson`) is a wrapper around that.

The seed we need is the set of objects the wizard would have written,
parameterised by the box's own facts:

- `SystemSettings` — `defaultHostname` = the box's `MAIL_HOSTNAME`,
  `defaultDomainId` = the box's mail domain, tracer configuration that
  sends INFO+ to stdout (so `docker logs stalwart` stops being empty),
  queue retry defaults.
- `Domain` — the box's `$DOMAIN` (`aurora.local` on the reference box).
  `StalwartProvisionService` already provisions this on boot; the seed
  step must be idempotent with what is already there.
- `NetworkListener` × N — SMTP on :25 (present in the defaults, still
  worth being explicit), submission-STARTTLS on :587, submission-SSL on
  :465, IMAPS on :993, IMAP-STARTTLS on :143, ManageSieve on :4190.
  Every one of these is a port compose already publishes and the
  dashboard already advertises. The current state is that only :25,
  :993, and :4190 answer; the others were quietly missing.
- `SessionSettings` / `RcptSettings` — accept mail for
  `postmaster@<domain>`, `admin@<domain>`, `system@<domain>` and every
  provisioned mailbox. Aliases are the C24/C25 work; local delivery
  behaviour is what makes them arrive.
- `Certificate` — reference the same internal-CA-issued cert Caddy
  hands out on `mail.$DOMAIN`, so implicit-TLS and STARTTLS listeners
  serve trusted-by-the-box TLS to Apple Mail / Thunderbird / phones.

## Two shapes to choose between

### Option A — Aurora renders and applies at boot (recommended)

A new `StalwartRegistrySeedService`, wired next to
`StalwartProvisionService`. On every boot:

1. Read the current registry state (JMAP `Query`/`Get`) for each of the
   objects above, keyed by a well-known id or by name.
2. Compute the desired state from `$DOMAIN`, `MAIL_HOSTNAME`, and the
   list of provisioned mailboxes.
3. `set` the objects that need creating or updating; leave the rest
   alone. This is idempotent and drift-reconciling, matching how
   `AutheliaService` re-renders the users file every 5 minutes.
4. Record an audit row keyed `mail.registry.seed` with a hash of the
   desired set, so a rebuild that changes the seed is visible in the
   log.

Failure mode: if the JMAP calls fail (Stalwart still starting up), log
one line and try again on the next scheduler tick. Never fatal —
Aurora's boot must not be blocked by the mail server's boot.

Verification: end-to-end from the reference box.
- SMTP `MAIL FROM ... RCPT TO:<sarah@aurora.local> ... DATA .` and a
  message body ending in `.` succeeds and the row count in
  `stalwart.jmap_message` goes up by one.
- IMAPS `LOGIN sarah@aurora.local <password>` then `SELECT INBOX`
  shows the message with `FETCH 1 BODY[]`.
- IMAP-STARTTLS :143 and submission :587 both answer instead of
  refusing.
- `docker logs stalwart` has something in it.

### Option B — Aurora seeds by writing directly into Postgres

The registry is a table of hex-encoded key-value pairs. In principle
Aurora could write into it directly, bypassing JMAP. Rejected: the
schema is undocumented and Stalwart's own migration path across
versions goes through the registry API. A future v0.17 could rename
a field and Aurora would be writing straight into a corruption. JMAP
is the supported seam.

## Test plan

Backend (green in CI, no live Stalwart needed):

- `StalwartRegistrySeedServiceTests` — pins the shape of the JMAP
  payloads with a mocked `StalwartMailClient`. Property-based:
  given a `$DOMAIN` and `MAIL_HOSTNAME`, the SystemSettings write
  carries exactly those, tracers is on, and the listener set is the
  documented six.
- Idempotency test: two runs against a mocked client that echoes back
  what was set produce one create then one no-op.
- `StalwartRegistrySeedControllerTests` — nothing (no controller,
  no user-facing surface).

Live-box gate before merging:

- Full `bootstrap.sh install` from a wiped box (data/ + secrets +
  every `.env`), then:
  - `docker logs stalwart | head` shows startup traces.
  - `swaks --to sarah@aurora.local --from external@example.com
    --server aurora.local:25` succeeds AND the message arrives via
    IMAPS.
  - Same test on :587 with submission auth.
  - `nc aurora.local 143` responds with `* OK`.
- Repeat with a rebuild that changes the seed by one listener; audit
  row `mail.registry.seed` records the diff.

## Not in scope

- **Outbound queue tuning.** The queue's retry schedule, backoff, and
  per-recipient policy are all part of the same registry surface, but
  they are a delivery-quality concern that arrives after local delivery
  works. Ship after C27.
- **DKIM / SPF / DMARC keys.** The wizard writes a default DKIM
  keypair; Aurora should own that too, from the same seed. Tracked as
  a follow-up to keep C27 focused on "mail is not discarded".
- **Sieve preludes.** Similar surface, no user impact until people are
  actually receiving mail.
- **The webmail listener on Caddy.** That already works
  (`packages/core/caddy` proxies `mail.$DOMAIN`); it is the mail
  protocols that are broken.

## Followups after C27 lands

- C26 (Aurora's alerts → `system@`) becomes safe to ship. Right now
  routing anything to `system@` would be a silent loss.
- Update `docs/CORE_SHARED_SERVICES_PLAN.md` to describe the seed step
  next to the datastore-pointer step, so a future reader sees the
  whole "no wizard" story on one page.
