# Core shared services — an opinionated, pre-configured core stack

**Status:** IN PROGRESS — Phases 1 + 2 shipped, verified via full nuke-and-rebuild 2026-08-28.
**Author:** Aurora dashboard team.
**Requested by:** Bruce, 2026-08-28.

## Progress

- **Phase 0 (contract/guardrails): DONE.** Wrote the core/non-core
  isolation boundary into `docs/PACKAGE_CONTRACT.md`; added the
  `core-isolation` CI job (fails a non-core `compose.yml` that references
  `core-db`/`core-cache`); added `CoreDbIsolationRule`, a dashboard
  security finding that flags a non-core container joined to core's
  datastore on a live box (6 tests).
- **Phase 1 (core-db + Authelia): DONE + verified.** Added `core-db`
  (postgres:17-alpine) with a per-app init script, seeded DB passwords via
  rotate-secrets, migrated Authelia off SQLite onto `storage.postgres`.
- **Phase 2 (Stalwart onto core-db): DONE + verified — the wizard is gone.**
  Aurora seeds `data/stalwart/etc/config.json` (datastore → core-db) via
  `render_stalwart_config()`. Fixed a latent `up.sh` ordering bug
  (`render_all` now runs before rotate-secrets).
- **Phase 3 (core-cache):** deferred — no core app needs a shared
  Redis/Valkey yet (Authelia + Stalwart don't). Will add when a consumer
  appears; `core-cache` is already reserved in the isolation guardrails.
- **Phase 4 (one-shot backup): DONE.** Core's backup block now captures
  the real state: a `postgres-dump` of `core-db` (Authelia + Stalwart
  metadata) plus the on-disk bits not in Postgres (`data/stalwart`
  config+blobs, `data/authelia`). Replaces the old stop-Stalwart-and-copy.
- **Full nuke-and-rebuild test PASSED** (see Phase 2 detail below).

### Remaining manual step (tracked, not yet automated)

DONE (2026-08-28). Stalwart domain + mailbox provisioning is now automated:

- **`StalwartMailClient`** — a tested client for Stalwart's JMAP management
  API (v0.16 moved principal management to JMAP). Object shapes verified
  against a live v0.16.19: `x:Domain/set` create, `x:Account/set` create
  with a `credentials` MAP (not array). Basic-auth with the recovery admin.
- **`StalwartProvisionService`** — on boot + a 10-min reconcile, ensures the
  box's own mail domain (from `.state.yml`) exists in Stalwart. Idempotent,
  fail-closed. So a fresh box comes up with its mail domain configured, no
  wizard and no manual domain step.
- **`POST /api/services/stalwart/mailboxes`** — admin-only; creates a
  mailbox on the box domain with a GENERATED strong password returned once
  (same one-time-reveal as the admin-password reset). 409 on duplicate,
  400 on a bad local part, 502 when Stalwart is unreachable.
- **Verified live end-to-end:** dashboard boot auto-confirmed the domain;
  `POST /mailboxes` created `hello@aurora.local`, which then authenticated
  against the real mail server (JMAP 200), rejected a wrong password (401),
  and was audited. 18 new tests; 954 backend total, all green.

Mailboxes are the one thing that still needs an operator click (they carry
a password), but that click is now one dashboard action, not an SSH + admin
console + wizard. A frontend surface for it (a "Create mailbox" panel on
the Stalwart core-service page) is a small follow-up.

## The vision, in Bruce's words

> The core apps should all be pre-configured with an opinionated stack and
> values. The core is its own stack. Non-core apps have their own stacks,
> isolated to minimise blast radius.

Two rules, and the whole design falls out of them:

1. **Within core: consolidate.** Core is one tightly-integrated stack with
   shared infrastructure — a common database, a common cache, one set of
   secrets, pre-seeded config. Core apps do *not* each bring their own
   database; they use the common one. There are no first-run wizards.
2. **Across non-core: isolate.** Every non-core app is a self-contained
   stack that owns everything it needs, including its own database. A
   non-core app never reaches into core's database or another app's. You
   can reinstall or delete one app's whole stack — data included — without
   collateral damage.

The reason the two rules point in opposite directions is **blast radius**.
Core is already one blast radius: if Caddy or Authelia is down, the box is
effectively down, so sharing a database between core apps costs nothing and
buys a lot (one backup, one set of credentials, one thing to tune). A
non-core app is a *separate* blast radius on purpose: Immich hammering or
corrupting its database must not be able to touch mail, auth, or Paperless.

## Why this is worth doing

Two concrete problems today:

1. **Core hands the operator a raw setup wizard.** Stalwart boots in
   "bootstrap mode" with no config and makes the operator complete an
   upstream datastore wizard (choose RocksDB vs SQLite, set paths, …).
   That is the one place Aurora violates its own "opinionated defaults, no
   operator setup" principle. Authelia is better (it is configured) but
   keeps its own SQLite file, unrelated to anything else.

2. **Non-core DB sprawl is unmanaged, not wrong.** Across the stack there
   are already five datastore containers (2× Postgres, 3× Redis) in the
   `dev`, `documents`, and `photos` packages. Under rule 2 that is
   *correct* — each app owns its own DB — but nothing states the rule, so
   there is no contract keeping a future package from doing the wrong
   thing (reaching into core, or a sibling).

This plan fixes (1) by giving core a shared DB and pre-seeding every core
app against it, and codifies (2) as an explicit, lintable package
contract.

## What "core" is today

`packages/core/compose.yml` — one compose file, three services, each with
its **own** store:

| Service   | Role                         | Store today                    |
|-----------|------------------------------|--------------------------------|
| caddy     | reverse proxy, TLS, fwd-auth | none                           |
| authelia  | SSO / 2FA                    | **SQLite** `/data/db.sqlite3`  |
| stalwart  | mail (SMTP/IMAP/JMAP)        | **own datastore** (the wizard) |

Plus `manifest.yml`, `.env(.example)`, `caddy.snippet`, `authelia/`.

## Target: core as a shared-infrastructure stack

Add **one Postgres instance** to core, and (phase 2) **one Redis/Valkey**.
Core apps use per-app *databases* inside the shared *instance* — not shared
tables; Authelia and Stalwart schemas remain their own.

```
packages/core/compose.yml
├── core-db  (postgres:17-alpine)            ← NEW: the common core datastore
│    ├── database: authelia   (owner: authelia_user)
│    └── database: stalwart   (owner: stalwart_user)
├── core-cache (valkey/redis) [phase 2]      ← NEW: shared sessions/rate-limits
├── caddy      (no DB)
├── authelia   → storage.postgres → core-db/authelia   (was SQLite)
└── stalwart   → datastore postgresql → core-db/stalwart (was wizard)
```

Key properties:

- **One volume to back up** (`data/core-db`) instead of scattered SQLite
  files and a Stalwart datastore. The backup block simplifies to a single
  `pg_dump` (or a stop-and-copy of one directory).
- **No Stalwart wizard.** Stalwart's datastore is declared to point at
  `core-db` before first boot, so it comes up configured. (See § Stalwart.)
- **No Authelia SQLite.** Authelia's `storage.postgres` block is rendered
  from env, same as its encryption key is today.
- **One set of DB secrets**, generated once on boot by a new
  `CoreDbSecretsService`, exactly like `BackupSecretsService` /
  `IdentitySecretsService` already do for their domains.

### Why Postgres (not MySQL / keep-SQLite)

Both core apps that need a relational store support Postgres natively
(Authelia: `storage.postgres`; Stalwart: `store.*` with `type = "postgresql"`).
Postgres is the common denominator, is boring and reliable, and one engine
is one thing to learn and back up. SQLite can't be *shared* across
containers (single-writer, file-local), which is the whole point we're
moving away from for core.

## The non-core contract (rule 2, made enforceable)

A non-core package:

- **MUST** own its datastore inside its own compose stack (as `dev`,
  `documents`, `photos` already do).
- **MUST NOT** connect to `core-db`, `core-cache`, or another package's
  services. Its only permitted cross-stack dependency is the reverse proxy
  + SSO edge (Caddy/Authelia forward-auth), which is how every app is
  reached anyway.
- Is deployable, restartable, and *destroyable* as a unit — deleting the
  app deletes its data with it, and nothing else notices.

This becomes a checked rule, not just prose:

- A new schema key (or reserved-name rule) so a manifest can't declare a
  dependency on `core`'s internal services.
- A CI/lint check: no non-core `compose.yml` may reference `core-db` /
  `core-cache` hostnames, and only `core/*` may define them.
- The existing `SecurityFindingsService` gets a rule that flags a non-core
  container joined to core's DB, so drift on a live box is visible in the
  dashboard.

## Env injection — the mechanism already exists

Nothing new architecturally; this slots into three existing seams:

1. **`scripts/lib/render.sh`** already seeds config (e.g. the Authelia
   users DB). Add `render_core_db()` to materialise per-app connection
   settings and Stalwart's datastore config before first boot.
2. **`*SecretsService.java`** on backend boot already auto-generates
   secrets (Kopia password, identity secrets). Add `CoreDbSecretsService`
   to generate the Postgres superuser + per-core-app passwords once, write
   them to `packages/core/.env`, never rotate a populated value (same
   invariant as `BackupSecretsService`).
3. **`.env.example` + `rotate-secrets.sh`** hold the connection strings and
   classify the new `*_DB_PASSWORD` keys as secrets (they already match the
   `PASSWORD` hint pattern).

A core app therefore comes up with, e.g.:

```
AUTHELIA_STORAGE_POSTGRES_ADDRESS=core-db:5432
AUTHELIA_STORAGE_POSTGRES_DATABASE=authelia
AUTHELIA_STORAGE_POSTGRES_USERNAME=authelia
AUTHELIA_STORAGE_POSTGRES_PASSWORD=<generated once>
```

injected at render time — the operator sets nothing.

## Stalwart specifically (kills the wizard you just hit)

Stalwart v0.16 keeps a tiny `config.json` at `/etc/stalwart` describing
*only* the datastore; everything else lives in its DB. Today Aurora mounts
that dir empty, so Stalwart boots to the wizard. The fix:

- `render_core_db()` writes `data/stalwart/etc/config.json` pinning the
  store to Postgres on `core-db` **before** first boot, so Stalwart starts
  configured and its SMTP/IMAP listeners come up without a human.
- The recovery admin (`STALWART_RECOVERY_ADMIN=admin:<secret>`) is already
  seeded from env; keep it for break-glass.
- Domain + first mailbox creation is the one genuinely per-box step left;
  Aurora can drive it via Stalwart's API from the dashboard (a follow-up),
  or leave it as the single documented action.

## Phasing

**Phase 0 — Contract + guardrails (no behaviour change).** Write the
non-core isolation rule into `docs/PACKAGE_CONTRACT.md`, add the CI lint
(no non-core reference to core-internal services), add the
`SecurityFindingsService` drift rule. This locks rule 2 before we touch
rule 1.

**Phase 1 — core-db + Authelia.** Add `core-db` (postgres) to core's
compose with its own volume + healthcheck. Add `CoreDbSecretsService` +
`render_core_db()`. Migrate Authelia from SQLite to `storage.postgres`.
Authelia is the safe first mover: fresh box = empty auth DB, and this box
can re-enrol. Verify SSO end-to-end.

**Phase 2 — Stalwart.** Point Stalwart's datastore at `core-db` via a
pre-seeded `config.json`; Stalwart boots configured, no wizard. Verify mail
admin + a test mailbox + webmail login.

**Phase 3 — core-cache (optional).** Add a shared Valkey/Redis for
sessions/rate-limits if/when a core app needs one. Not required by Authelia
or Stalwart today, so this is deferred until there's a consumer.

**Phase 4 — one-shot backup.** Collapse core's backup surface to a single
`pg_dump` of `core-db` (+ the small `data/authelia` and `data/stalwart/etc`
config), replacing the per-store copy.

Phases 0–2 are the meaningful work. On a **fresh** box this is clean. On
**this** box (Authelia already has enrolments, Stalwart is mid-wizard),
Phase 1 needs a small migrate-or-reenrol step, called out in that phase.

## Tradeoffs, stated honestly

- **Core coupling is intentional.** `core-db` down means auth + mail down
  together. On a single home box they are one blast radius already, so this
  is acceptable and the manifest healthchecks make it visible.
- **A Postgres container is heavier than a SQLite file.** ~30–50 MB RAM
  idle. Fine on the Optiplex; worth noting for a Pi-class box. If it ever
  bites, Postgres tuning (shared_buffers, max_connections) lives in one
  place — a benefit of consolidation, not a new problem per app.
- **Migration is one-time and per-box.** Fresh installs are free; existing
  boxes pay a small migrate/re-enrol cost once, which Phase 1/2 scripts.

## What this plan is NOT

- **Not** a shared database across *non-core* apps. Immich, Paperless,
  Forgejo keep their own DBs in their own stacks. The shared DB is
  **core-internal only**. (This is the correction from the first draft.)
- **Not** shared *tables/schemas* between core apps — Authelia and Stalwart
  each own their schema inside their own database on the shared instance.
- **Not** a change to how non-core apps are reached (Caddy + Authelia edge
  is unchanged and remains the only sanctioned cross-stack seam).
- **Not** removing the recovery admin or any break-glass path.

## Related

- `docs/PACKAGE_CONTRACT.md` — gains the non-core isolation rule.
- `docs/MARKETPLACE_HOSTING_PLAN.md` — same "opinionated, pre-configured,
  operator sets nothing" philosophy, applied to the catalogue.
- `BackupSecretsService` / `IdentitySecretsService` — the boot-seed pattern
  `CoreDbSecretsService` copies.
