# Brief: purpose-built dashboard for managing a home.local server

**Target reader:** an AI agent (or team) picking up this project from scratch.
**Author:** the agent that built the home.local `productionize` branch (Jul 2026).
**Companion repo:** [`tomaytotomato/home.local`](https://github.com/tomaytotomato/home.local), branch `productionize` (41 commits ahead of `main`).

## 0. TL;DR

Build a small, single-tenant web app that manages one `home.local` box.
It replaces the CLI `bootstrap.sh` flow with a browser UI, plus adds
security posture reporting, simplified onboarding, live health, and
opinionated guardrails against the specific footguns that hurt real
users of home.local (documented below).

- **Frontend:** Vue 3.5 + Vite 8 + TypeScript 5.6+.
- **Backend:** Java 25 (LTS) + Spring Boot 4 + `docker-java` 3.7.
- **Storage:** SQLite (per-instance). No external DB.
- **Ships as:** one container, packaged as a new `packages/dashboard/`
  bundle inside the home.local repo, so users install it the same way
  they install any other home.local package.

It is **not** a replacement for Homepage (which is the tile grid users
hit day-to-day). It is the *admin plane* — install, configure, audit,
troubleshoot. Think of Homepage as the living-room TV, this thing as
the fuse box.

Working name: **Warden** (feel free to change).

---

## 1. Context: what home.local already is

`~/home.local/` is a repo that turns a fresh Debian/Ubuntu box into a
self-hosted server via three layers:

```
L2  Ansible (host/site.yml)     harden OS, install docker
L3  Docker + docker-compose      single project 'home' on home_net
L4  Installer (bootstrap.sh)     manifest-driven package picker
L5  Packages (packages/<name>/)  15 curated docker-compose bundles
```

Every package obeys a documented contract (`docs/PACKAGE_CONTRACT.md`):

```
packages/<name>/
  manifest.yml       schema-validated metadata (deps, ports, category, required env, host prereqs)
  compose.yml        the stack
  .env.example       every KEY compose references, with comments
  README.md          human notes
  caddy.snippet      (optional) vhost fragments, imported by core Caddy
  homepage.yml       (optional) tile group, merged into Homepage services.yaml
  seed.sh            (optional) idempotent post-up hook
```

State lives in `~/home.local/.state.yml` (gitignored):

```yaml
bootstrap_version: 1
hostname: "aurora"
domain: "home.local"
installed_at: "2026-07-31T14:22:37Z"
enabled:
  - core
  - privacy
  - media
  - storage
profiles:
  - cpu
```

The scripts that touch this world are:

- `bootstrap.sh` — `install / add / remove / list / status`
- `scripts/{up,down,status,doctor,health,backup,pin,rotate-secrets}.sh`
- `scripts/lib/{log,prompt,manifest,state,render,ops}.sh` — sourced helpers

**Read `docs/PACKAGE_CONTRACT.md` and `docs/ARCHITECTURE.md` before
writing a single line of code.** Everything you build has to co-exist
with these primitives, not fight them.

---

## 2. Goal

Give a non-expert user a single browser tab from which they can:

1. **Onboard a fresh box** — pick packages, generate secrets, confirm
   the DNS story, install.
2. **Manage the enabled set** — add, remove, upgrade, restart packages.
3. **See health at a glance** — containers, disk, memory, TLS, DNS,
   fail2ban bans, unattended-upgrades status.
4. **Get opinionated security guidance** — weak secrets, exposed ports,
   missing 2FA, unpatched host, absent backups, out-of-date images.
5. **Do the "next thing"** — every screen should end in a clear next
   step, not just data.

If a user never opens SSH again after first boot, the dashboard
succeeded.

---

## 3. Scope

### In scope (v1)

- Local single-user admin auth (WebAuthn passkey preferred, password
  fallback).
- Package picker mirroring `bootstrap.sh list` / `add` / `remove`.
- Per-package "config" screen that renders `.env` as a form (types
  inferred from key name heuristics: `*_PASSWORD` → password field,
  `*_URL` → URL field, `TZ` → timezone select).
- Health dashboard: containers, host resources, ports, TLS trust, DNS.
- Security posture score with actionable checklist.
- Backup status (was `scripts/backup.sh` run recently? size trend?).
- Simple metrics chart (last 24h): CPU %, memory %, disk %, container
  count. Pulled from `docker stats` + `/proc`, **not** Prometheus.
- Integration for `docker-java` events stream: show container
  start/stop/die/health notifications live.
- First-run wizard for the whole box.

### Out of scope (defer or say no)

- Multi-tenant, multi-user roles. One admin, done.
- Custom dashboards / drag-and-drop tiles. Homepage exists for that.
- Log aggregation UI (users tail Docker logs; we won't reinvent that
  wheel).
- Cluster/multi-host management.
- Custom package authoring in the UI (edit compose.yml in your editor).
- Prometheus/Grafana replacement. Explicitly not competing with the
  optional `packages/monitoring` package.
- Any features that would require anything beyond
  `~/home.local`, `/var/run/docker.sock`, and `/proc`.

---

## 4. Architecture

### 4.1 Frontend

- **Vue 3.5.x** (Composition API), stable. Vue 3.6 is in RC as of
  mid-2026 and adds Vapor Mode (compiler-emitted direct DOM ops, no
  VDOM — SolidJS-tier perf). **Start on 3.5, plan a 3.6 uptake in
  M3/M4 once 3.6 GA lands**; Vapor is opt-in per component so upgrade
  cost is bounded. Do not adopt 3.6 pre-GA — the surface is stable
  but tooling/plugins are still catching up.
- **Vite 8** (8.2+), TypeScript 5.6+ strict.
- **Vue Router 5** for routes; nested routes for wizard flows.
- **Pinia 4** for state (ESM-only, requires `@vue/devtools-api` v8).
  One store per resource (`packages`, `system`, `security`, `auth`,
  `events`).
- **UI kit:** [shadcn-vue 2.4+](https://www.shadcn-vue.com/) built on
  [Reka UI](https://reka-ui.com/) (formerly Radix Vue — renamed 2025).
  This is the strongly recommended default: copy-paste components,
  full source ownership, Tailwind-based styling, small bundles.
  Fallback if the team dislikes Tailwind: **PrimeVue 4.5** with the
  styled-mode theme builder — richer out of the box, less flexible.
  **Pick one, don't mix.**
- **Charts:** [uPlot 1.6](https://github.com/leeoniya/uPlot) via
  `uplot-vue`. ~50 KB min, extremely fast. Do not pull in
  Chart.js/ECharts; overkill for four line charts.
- **Auth on the client:** WebAuthn via
  [`@simplewebauthn/browser` 13.x](https://simplewebauthn.dev/).
- **Server-Sent Events** (`EventSource`) for the docker events stream.
  Do not add WebSockets; SSE is enough and simpler.
- **i18n:** English only in v1. Structure strings via `vue-i18n 10`
  from day one so it's easy later.

### 4.2 Backend

- **Java 25 (LTS)** — released Sep 2025, first LTS since Java 21,
  supported through at least 2033. Use compact source files, module
  imports, and flexible constructors where they simplify things.
- **Spring Boot 4.0** (released Nov 2025, latest 4.0.x as of Jun
  2026). Built on **Spring Framework 7** with **Jakarta EE 11** —
  Servlet 6.1, JPA 3.2, Bean Validation 3.1. Boot 4 introduces a
  full framework modularization; pull only the starters you need.
  Note: Boot 4 requires Java 17+, but we're targeting 25 for the LTS.
- **`docker-java` 3.7.x** for Docker Engine API (list containers,
  stats, events, inspect networks, image pulls).
- **Spring Security 7** for session + WebAuthn. Use the native Spring
  Security WebAuthn integration (added in Spring Security 6.4 and
  hardened in 7). Wrap **[webauthn4j 0.31.x](https://webauthn4j.github.io/webauthn4j/en/)**
  for the attestation/verification primitives if the native
  integration lacks a corner you need.
- **SQLite via `org.xerial:sqlite-jdbc 3.53.x`**, migrations with
  **Flyway 13**. Schema in `src/main/resources/db/migration/V*.sql`.
- **YAML parsing:** SnakeYAML 2.6 for reading `manifest.yml` and
  `.state.yml`. Do not shell out to `yq` from Java; keep the parsing
  in-process.
- **Compose invocation:** shell out to `docker compose` via
  `ProcessBuilder`, wrapping the existing home.local scripts
  (`scripts/up.sh`, `scripts/down.sh`, etc.). Do NOT rewrite the
  installer logic in Java — those scripts are the contract; you're
  the driver.
- **Metrics collection:** a `@Scheduled(fixedRate = 15000)` bean that
  pulls docker stats + reads `/proc/meminfo`, `/proc/loadavg`, `df`,
  stores rows in SQLite table `metrics_sample`. Retain 7 days.
- **Observability of the app itself:** Spring Boot Actuator with
  `/health` and `/info` at minimum. No Prometheus scraping endpoint
  by default (see monitoring package).

### 4.3 Packaging

- **One container.** Multi-stage Dockerfile:
  - `stage 1`: `node:22-alpine` → build Vue app
    (`npm ci && npm run build`).
  - `stage 2`: `maven:3.9-eclipse-temurin-25-alpine` →
    `mvn -T1C package`; copy Vue dist into
    `src/main/resources/static/` before packaging.
  - `stage 3`: `eclipse-temurin:25-jre-alpine` → copy the fat jar,
    entrypoint `java -jar /app/warden.jar`.
  - Target final image size < 250 MB.
- **Ships as** `packages/dashboard/` inside home.local:

  ```
  packages/dashboard/
    manifest.yml        category: core (or 'management')
    compose.yml         warden service + bind mounts
    .env.example        WARDEN_ADMIN_USERNAME, JWT signing key, session secret, TZ
    README.md
    caddy.snippet       warden.$HOME_DOMAIN vhost, protected by import authelia
                        when identity is enabled
    homepage.yml        one tile linking to warden UI
  ```

- **Mounts** (in compose.yml):
  - `/home/${USER}/home.local:/repo:rw` — the entire home.local repo.
    Warden reads manifests, writes .env, writes .state.yml, runs
    scripts.
  - `/var/run/docker.sock:/var/run/docker.sock:rw` — docker-java.
  - `warden_data:/data` — SQLite DB, WebAuthn keys, uploads.
  - `/proc:/host/proc:ro` — host metrics.
- **Runs as** `user: "${WARDEN_UID:-1000}:${DOCKER_GID}"` — same
  pattern as Homepage. `scripts/up.sh` auto-exports `DOCKER_GID`.
- **Port:** 8090 (avoid every used port in the ecosystem — see
  `packages/*/manifest.yml` for the current allocations, and check
  `docs/ARCHITECTURE.md`).

### 4.4 High-level shape

```
Browser (Vue SPA)
    │  HTTPS via Caddy (warden.home.local)
    ▼
Spring Boot (embedded Tomcat, :8090)
    ├─ REST controllers    /api/packages, /api/system, /api/security, …
    ├─ SSE endpoint        /api/events (docker events + backend events)
    ├─ WebAuthn            /api/auth/{registration,authentication}/{options,verify}
    ├─ Scheduler           metric sampler, health poller, backup poller
    ├─ Service layer       PackagesService, DockerService, ScriptRunner
    └─ Persistence         SQLite (flyway migrations)
         │                   │                  │
         ▼                   ▼                  ▼
 docker.sock (docker-java)  Filesystem       Process
                            ~/home.local     scripts/*.sh
                            /proc            docker compose
```

### 4.5 Component versions — verified July 2026

Use these as **hard floors** for a new project starting today. Only
downgrade if you have a defended reason (write it into the repo
README).

| Component                     | Version            | Notes                                                       |
|-------------------------------|--------------------|-------------------------------------------------------------|
| Java / OpenJDK                | **25 LTS**         | Released 2025-09-16, supported ≥ 8 years. Prev LTS: 21.     |
| Spring Boot                   | **4.0.x**          | 4.0.0 GA 2025-11-20. Built on Spring Framework 7.           |
| Spring Framework              | 7.x                | Jakarta EE 11 (Servlet 6.1, JPA 3.2, Bean Validation 3.1).  |
| Spring Security               | 7.x                | Native WebAuthn support (matured since 6.4).                |
| docker-java                   | 3.7.x              | 3.7.1 released 2026-03-19.                                  |
| webauthn4j                    | 0.31.x             | Only if Spring Security WebAuthn lacks a needed primitive.  |
| SnakeYAML                     | 2.6                | Feb 2026 release, prev 2.5 (Aug 2025).                      |
| sqlite-jdbc (org.xerial)      | 3.53.x             | Bundles SQLite 3.53.                                        |
| Flyway                        | 13.0.x             | Jul 2026 release.                                           |
| Testcontainers                | 2.0.x              | Scoped to E2E + isolated-daemon tests only, not integration. |
| Maven                         | 3.9.9+             | Toolchain for Java 25 build.                                |
| Node.js (build-time only)     | 22 LTS             | Discarded from runtime image via multi-stage.               |
| Vue                           | **3.5.x**          | Stable. Migrate to 3.6 (Vapor Mode) when 3.6 GA lands.      |
| Vite                          | 8.2+               | Released 2026-07-30.                                        |
| Pinia                         | 4.0.x              | ESM-only; requires @vue/devtools-api v8.                    |
| Vue Router                    | 5.2+               |                                                             |
| TypeScript                    | 5.6+               |                                                             |
| shadcn-vue                    | 2.4+               | On Reka UI (formerly Radix Vue, renamed 2025).              |
| Reka UI                       | latest             | Headless base used by shadcn-vue.                           |
| @simplewebauthn/browser       | 13.x               | 13.3.0 released 2026-03-10.                                 |
| uPlot                         | 1.6.x              | ~50 KB min; use `uplot-vue` wrapper.                        |
| vue-i18n                      | 10.x               |                                                             |
| Vitest                        | 4.1.x              | v5 in beta as of mid-2026; stay on 4.1 for M1–M2.           |
| Playwright                    | 1.62.x             |                                                             |
| CycloneDX Maven plugin        | latest 2.x         | For SBOM.                                                   |

When bumping any of these, run the full test matrix in CI and cut a
patch release. Do not pin transitively — let the BOMs manage it
(Spring Boot BOM for backend, Vite/Vue peer deps for frontend).

---

## 5. Domain model

| Entity           | Notes                                                                                     |
|------------------|-------------------------------------------------------------------------------------------|
| `Package`        | Parsed from `packages/<name>/manifest.yml`. Immutable snapshot per repo commit.           |
| `EnabledPackage` | Row in `.state.yml`'s `enabled:` list. Attributes: name, enabledAt, profiles.             |
| `Container`      | Live docker view. Derived, not stored.                                                    |
| `EnvVar`         | Key/value in `packages/<name>/.env`. Marked secret via key-name heuristic.                |
| `MetricSample`   | `(ts, name, value)` — cpu, mem, disk, net. Retention 7d.                                  |
| `SecurityFinding`| Static rule output: severity, category, message, remediation link.                        |
| `AuditEvent`     | Every admin action: userId, action, target, timestamp, diff. Never deleted.               |
| `AdminUser`      | Single row (v1). Username, password hash (argon2id), WebAuthn credentials list, TZ.       |
| `Session`        | Spring Security session; JDBC-backed for restart-survival.                                |
| `BackupRun`      | If backup.sh is on cron: parsed from `~/backups/home.local/` timestamps + sizes.          |

---

## 6. UX flows

### 6.1 First-run wizard

Split-pane layout, left = steps, right = current step content. Every
step is skippable but marked with a warning if it is.

1. **Welcome** — detects hostname, LAN IP, distro. Warns if not
   Debian/Ubuntu.
2. **Admin account** — set username, generate strong password (with
   a one-click "copy + I've saved it" flow), optionally enroll a
   passkey now. Explicitly recommend passkey; make it the default
   button.
3. **Hostname & domain** — pre-fills from L2 host state; asks for
   `$HOME_DOMAIN`. Explains what changes when you edit it.
4. **Pick packages** — checkbox grid grouped by category. Recommends
   `core + privacy + storage` as the safe default; shows a "media
   server" preset and a "personal cloud" preset.
5. **Configure secrets** — per selected package, render `.env.example`
   as a form. Auto-generate anything that looks like a secret; make
   the "Generate" button the default action. Show inline docs from
   the comments in `.env.example`.
6. **DNS story** — three tabs:
    - "I have AdGuard on this box" — auto-configured, next.
    - "I'll use my router's DNS" — copy-paste instructions with the
      exact string to type into common routers (Netgear, Unifi,
      pfSense, ASUS).
    - "I only want mDNS" — explain `.local` won't cover subdomains,
      offer `/etc/hosts` snippet.
7. **Trust the TLS root** — one-click download of the Caddy root CA,
   with per-OS install instructions. Verify by loading a hidden iframe
   from a warden-signed URL.
8. **Review & install** — plan diff (packages to enable, services to
   start, ports to open), confirm, streaming log output during
   `scripts/up.sh`.
9. **Done** — links to every package's landing page, big "open
   Homepage" button, small "advanced tools" button that leads to this
   dashboard's normal home.

### 6.2 Package management

- List view: category tabs, per-package tile with status pill
  (running / degraded / stopped / not-installed), quick actions.
- Detail view: overview + config (env form) + logs (tail of `docker
  logs`, not persisted) + related (containers, vhost, ports).
- **Add package**: same secrets form as first-run step 5, then confirm.
- **Remove package**: two-step confirmation. Explains data retention
  (data is kept on disk unless "delete data" checkbox is ticked, which
  is off by default; ticking it shows a red danger banner).
- **Upgrade**: shell to `docker compose pull` + `up -d`. Show image
  digest diff before proceeding. Roll back = swap in previous pinned
  digest from `pins.env`.

### 6.3 Security posture

Single-screen "score" (0–100) with a list of checks; each check is a
row with severity dot, description, remediation button.

Concrete checks (leverage what home.local already knows):

- Weak/empty `.env` secrets (reuse the logic from
  `scripts/rotate-secrets.sh` — read `packages/*/.env` and score).
- Any container exposing a port on `0.0.0.0` that shouldn't be LAN-open.
- UFW status: enabled? default incoming policy?
- fail2ban: running? ban count?
- SSH: password auth disabled?
- Unattended-upgrades: enabled? last run within 7 days?
- Caddy trust: root CA installed on host?
- Identity package enabled but no vhosts protected via `import authelia`?
- Backup age: last successful backup within `SLA` (default 48h).
- Docker Engine version behind LTS?
- Any manifest declaring `requires.host_roles` for roles that don't
  exist yet?

### 6.4 Health

Live grid of container status; SSE-driven; click a container = drawer
with stats, exec (`docker exec` streamed to a browser terminal via
xterm.js — v1 can defer this and just show `docker logs` tail).

### 6.5 Metrics

Four line charts: CPU %, memory %, disk %, container count. 24h
window default; toggle 1h / 24h / 7d.

### 6.6 Backups

List of recent backup files (name, size, age). One-click "back up now"
runs `scripts/backup.sh`. If never run, big call-to-action to
schedule (via a systemd timer we install).

---

## 7. REST API surface (v1)

Everything is JSON except `/api/events` (SSE) and file downloads.

```
GET    /api/auth/session                           current admin session
POST   /api/auth/login                             password login
POST   /api/auth/webauthn/registration/options
POST   /api/auth/webauthn/registration/verify
POST   /api/auth/webauthn/authentication/options
POST   /api/auth/webauthn/authentication/verify
POST   /api/auth/logout

GET    /api/packages                               all available packages (from manifests)
GET    /api/packages/{name}                        package detail
GET    /api/packages/{name}/env                    keyvals from .env (secrets redacted unless ?reveal=1)
PUT    /api/packages/{name}/env                    upsert keys; validates against .env.example
POST   /api/packages/{name}/enable                 wraps ./bootstrap.sh add
POST   /api/packages/{name}/disable                wraps ./bootstrap.sh remove
POST   /api/packages/{name}/restart                docker compose restart <containers>
POST   /api/packages/{name}/upgrade                pull + up -d
GET    /api/packages/{name}/containers             docker ps filtered
GET    /api/packages/{name}/logs?container=&tail=  tailed logs (streams as text/event-stream chunks)

GET    /api/system                                 hostname, domain, uptime, RAM, disk, docker version
GET    /api/system/metrics?window=1h|24h|7d        time-series
GET    /api/system/state                           parsed .state.yml

GET    /api/security                               findings list + score
POST   /api/security/rescan                        re-run checks now
POST   /api/security/rotate/{pkg}                  wrap rotate-secrets.sh --apply

GET    /api/backups                                list of backup artefacts
POST   /api/backups                                run backup.sh now
POST   /api/backups/schedule                       install systemd timer

GET    /api/events                                 SSE stream of docker + system events
GET    /api/audit                                  paginated audit log
```

- Every mutating endpoint requires an authenticated admin session.
- Every mutating endpoint writes an `AuditEvent` row.
- Every long-running action returns a job id + streams progress to
  `/api/events` under a `job.{id}` topic.

---

## 8. Security posture (for the dashboard itself)

Non-negotiable defaults:

- Default admin password is **not shipped**. First-run wizard forces
  creation.
- WebAuthn passkey is the recommended primary auth. Password remains
  as fallback. **No SMS, no email-based 2FA.**
- Session cookies: `HttpOnly`, `SameSite=Lax`, `Secure` when behind
  Caddy TLS. Sliding expiry, absolute cap 12h.
- CSRF token on every mutating endpoint (Spring Security default).
- Rate limit login attempts: 5 per minute per IP, then 30-second
  backoff climbing to 5 minutes.
- All `.env` mutations diff-checked and audit-logged.
- Never log secret values. Redact any string longer than 16 chars
  whose key name matches `PASSWORD|SECRET|KEY|TOKEN|PSK`.
- File uploads: only WebAuthn attestation blobs and backup restores;
  everything else rejected.
- No CORS. Same-origin only.
- CSP: `default-src 'self'; script-src 'self'; style-src 'self'
  'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'`.
- SBOM emitted at build time (CycloneDX Maven plugin + `npm sbom`).

---

## 9. Integration with home.local — concrete contract

The Warden container **reads and writes** the following paths inside
its `/repo` mount (which is a bind of `~/home.local`):

| Path                                   | Read | Write | Notes                                                                                 |
|----------------------------------------|:----:|:-----:|---------------------------------------------------------------------------------------|
| `docs/PACKAGE_CONTRACT.md`             | ✓    |       | Source of truth for the manifest schema.                                              |
| `.github/schema/manifest.schema.json`  | ✓    |       | Validate manifests before enabling.                                                   |
| `packages/*/manifest.yml`              | ✓    |       | Package catalogue.                                                                    |
| `packages/*/.env.example`              | ✓    |       | Form field templates + inline docs.                                                   |
| `packages/*/.env`                      | ✓    | ✓     | User secrets. Never checked into git; always mode `0600`.                             |
| `packages/*/README.md`                 | ✓    |       | Rendered as markdown in the package detail view.                                      |
| `packages/*/caddy.snippet`             | ✓    |       | Show planned vhosts.                                                                  |
| `packages/*/homepage.yml`              | ✓    |       | Show planned dashboard tiles.                                                         |
| `.state.yml`                           | ✓    | ✓     | Never write directly — always shell to `bootstrap.sh` or use the state library.        |
| `group_vars/all.yml`                   | ✓    | ✓     | Host-level Ansible vars; rewrite via structured YAML edit, never string-replace.       |
| `inventory.ini`                        | ✓    | ✓     | Same.                                                                                 |
| `data/**`                              |      |       | **Never touched from Java.** These are container-owned bind mounts.                   |
| `scripts/*.sh`                         | ✓    |       | Executed via ProcessBuilder; never modified.                                          |

The following scripts are the ONLY things Warden may `ProcessBuilder`:

- `bootstrap.sh {add,remove,list,status}`
- `scripts/{up,down,status,doctor,health,backup,pin,rotate-secrets}.sh`
- `docker compose ...` (via docker-java where possible; shell only for
  pull/up/down on the merged project)

Anything not on that list requires a new user-facing feature and
should be added deliberately, with an audit log entry and a
security review.

Docker socket access is via `docker-java`. Do NOT invoke `docker ps`
etc. via subprocess for read operations — use the SDK. Subprocess is
only for compose/scripts (which have their own opinionated behaviour
we're wrapping).

---

## 10. Opinionated defaults

Take these decisions and defend them rather than making them
configurable knobs:

- **Single admin user.** Multi-user is a v2 concern.
- **WebAuthn first**, password second, no TOTP, no SMS.
- **SQLite** for storage. Not H2, not Postgres. No JPA — use Spring
  JDBC + Flyway. Simpler, faster to reason about, easier backups.
- **No JavaScript build in production.** Vite output is served
  statically from Spring Boot; hot reload only in dev.
- **No Node in the runtime image.** Multi-stage build discards Node.
- **No Prometheus/Grafana in-app.** Users who want that install
  `packages/monitoring`.
- **English only** in v1.
- **UTC in the DB, user's TZ in the UI.**
- **Every write is auditable.** No "quiet" mutations.
- **Docker is the only orchestrator we know about.** Podman is a
  post-1.0 stretch goal.
- **Track LTS Java, not bleeding-edge.** Java 25 today; move to Java
  29 (expected LTS in 2027) when it lands, not before.
- **Track stable Vue only.** Do not run Vue 3.6 pre-GA. Adopt Vapor
  Mode component-by-component after it goes stable.

---

## 11. Pitfalls (learned the hard way while building home.local)

Read these; each one bit me in production. Bake them into your design.

### 11.1 Bind-mount source rm while containers are running

`sudo rm -rf data/` while a container is running does NOT immediately
break the container (docker holds the mount open) — but the container
becomes a ticking bomb. The next restart re-resolves the bind source,
finds nothing, and boots as a fresh install with every setting lost.

- **Warden must never delete a bind-mount source while its container
  is running.** Every "delete data" flow must first stop the affected
  containers, then delete, then re-up.
- Ship a first-class `POST /api/packages/{name}/reset` endpoint that
  does this correctly, and gate any raw-filesystem operations behind
  it.
- Surface `doctor.sh --json` output in the health view; it includes
  a `bind-mount integrity` check we added specifically for this.

### 11.2 Relative bind-mount paths in multi-`-f` compose

`scripts/up.sh` invokes `docker compose -p home -f packages/core/compose.yml
-f packages/<pkg>/compose.yml ...`. Compose resolves relative bind
sources against the **first** `-f` file's directory, not each file's
own directory. So `./prometheus/prometheus.yml` inside
`packages/monitoring/compose.yml` becomes
`packages/core/prometheus/prometheus.yml` at runtime and blows up.

- Warden should refuse to enable a package whose compose.yml contains
  `./` relative bind sources. Fail loudly with a link to the "Gotcha"
  section of `docs/PACKAGE_CONTRACT.md`.
- Consider a lint step in the UI that flags this on package selection.

### 11.3 Docker group GID drift

The `docker` group's gid varies per distro/install: Ubuntu 998,
Debian 998–999, some older installs 975, RHEL 991. Any container that
mounts `/var/run/docker.sock` and runs as a non-root user needs the
right gid at container-user level.

- Warden's own compose.yml must use `${DOCKER_GID}` and `scripts/up.sh`
  exports it via `getent group docker`. Do NOT hardcode a number.

### 11.4 Homepage's dashboard is NOT this dashboard

Users will confuse "Homepage" (the tile grid at `home.local`) with
Warden. Be explicit:

- Every Warden screen has a "Back to dashboard" link that goes to
  Homepage, not to Warden's own root.
- The Warden vhost is `warden.$HOME_DOMAIN`, distinct.
- README/onboarding calls them "the dashboard" (Homepage) and "the
  admin panel" (Warden) consistently.

### 11.5 Caddy caches the config

`docker compose up -d` doesn't reload Caddy if only the mounted
Caddyfile changed. Warden must issue `docker exec caddy caddy reload
--config /etc/caddy/Caddyfile` after any change to snippet content or
Caddyfile.

### 11.6 First-run URLs matter more than dashboards

After install, users need first-run URLs (AdGuard wizard, Sonarr
setup, Seerr onboarding). Warden must **surface these prominently**
on the "just installed" screen. Bury the tile grid; foreground the
"what to do next".

### 11.7 Homepage user gid + docker.sock

Any Java container that mounts docker.sock needs a matching gid,
same as Homepage. This bit us. See 11.3.

### 11.8 States that need reconciliation

`.state.yml` (installer intent) can drift from what docker actually
runs (reality). Warden's home screen should always show both and flag
divergence. Common causes:

- User did `docker stop foo` manually.
- Package's compose.yml has a syntax error.
- A dependency package was removed but the dependent is still up.

Show a "Reconcile" button that re-runs `scripts/up.sh` against the
state's enabled set.

### 11.9 Secrets in `.env` are the trust boundary

Every writable path Warden touches contains secrets. Any bug in the
`.env` diff/write path leaks credentials. Wrap all writes in a small
`EnvFileMutator` service with explicit tests. Never templatize `.env`
from strings; always parse-modify-serialize.

### 11.10 Don't build a compose editor

Users will ask. Say no in v1. Editing compose.yml means learning
Docker Compose, and if they're at that point they should be on SSH.
Warden's job is the layer above.

---

## 12. Milestones

Deliver in vertical slices; each one should be independently useful.

### M1 — Skeleton (1 week)

- Repo scaffold: Maven multi-module (`warden-server`, `warden-web`).
- Frontend serves a static "Warden" placeholder.
- Backend: docker-java lists containers, exposes `/api/containers`.
- Auth: password-only login, session cookie, one hardcoded admin from
  env var for dev.
- Dockerfile + `packages/dashboard/` bundle installs cleanly via
  `./bootstrap.sh add dashboard`.
- **Ship criterion:** user can install, log in, see a live list of
  containers.

### M2 — Package management (1 week)

- Read manifests, render catalogue.
- Enable / disable via `bootstrap.sh add/remove`.
- Env-form editor with validation.
- Audit log persisted to SQLite.
- SSE events for install progress.
- **Ship criterion:** user does the whole `productionize`-branch flow
  from the UI, no SSH.

### M3 — Health + metrics (1 week)

- Live container health via docker events.
- Metric sampler + charts (uPlot).
- `doctor.sh --json` integration + a "System check" screen.
- Container logs tail (last 200 lines, no live stream).
- **Ship criterion:** user sees a real-time picture of their box.

### M4 — Security posture (1 week)

- Rules engine + findings model.
- Weak-secret detection.
- Firewall/fail2ban/UU status.
- Backup age + one-click backup.
- "Fix it" links from every finding.
- **Ship criterion:** a user with a warning gets a click-through path
  to resolution.

### M5 — WebAuthn + onboarding wizard (1 week)

- Passkey enrollment on first-run.
- Full first-run wizard (§6.1).
- Guided DNS + TLS trust flow.
- **Ship criterion:** fresh box → productive server in one browser
  session.

### v1.0 — Polish

- CI, tests, docs, screenshots.
- README with animated GIF of onboarding.
- Ship image to `ghcr.io/<owner>/warden`.

Cadence estimate: 5 weeks of focused single-agent work.

---

## 13. Deliverables

- New repo (suggested name `home.local-warden` or `warden`), MIT
  licensed, hosted at `github.com/tomaytotomato/…`.
- Multi-stage `Dockerfile` producing an image ≤ 200 MB.
- Multi-arch build (`linux/amd64`, `linux/arm64`) via `docker buildx
  bake` in GH Actions.
- End-to-end test using Playwright hitting a real dev instance.
- Component tests for Vue via Vitest.
- Backend tests: JUnit 5 + Mockito for unit tests; docker-java
  against the ambient daemon (with `warden.test=true` label cleanup)
  for integration tests; Testcontainers 2.0+ narrowly scoped to
  end-to-end (booting the Warden image) and optional isolated-daemon
  runs (see §14).
- A PR against `tomaytotomato/home.local` adding
  `packages/dashboard/` that installs Warden.
- Documentation site (optional v1): a `docs/` folder rendered by
  VitePress, deployed on GH Pages.

---

## 14. Testing expectations

Three tiers, each with a defined tool. Testcontainers has a role;
it just isn't at every tier.

### 14.1 Unit tests (fastest, mocked)

- **JUnit 5 + Mockito.** Mock `com.github.dockerjava.api.DockerClient`
  and its command builders. Test service logic (dep resolution,
  env-file mutation, state transitions, security-rule scoring) in
  isolation. No daemon, no containers, no Testcontainers. Runs on
  every save; targets < 5 s total.

### 14.2 Integration tests (docker-java ↔ real daemon)

- **JUnit 5 + docker-java against the ambient `/var/run/docker.sock`.**
  Test `DockerService`, container lifecycle, event streaming,
  `docker compose config -q` invocation. **Do not wrap this in
  Testcontainers** — docker-java is already the SUT dependency, so
  Testcontainers would just be docker-java-driving-docker-java. Every
  container this tier creates carries a `warden.test=true` label and
  a `warden-it-<uuid>` name prefix; `@AfterEach` prunes by label so a
  hard failure still leaves the daemon clean.

### 14.3 End-to-end / isolation tests (**Testcontainers earns its keep**)

**Use Testcontainers 2.0+ here, deliberately and narrowly.** The
cases where it genuinely helps:

1. **Whole-app end-to-end.** Boot the built Warden image itself
   (`GenericContainer("warden:local").withExposedPorts(8090)`) in a
   test, mount a temp directory as `/repo`, and drive it with real
   HTTP. Tests the packaged artefact, not just a local Maven run.
2. **Isolated docker daemon for local `mvn verify`.** On a developer
   laptop, integration tests polluting the real docker daemon is a
   pain. Optional profile `-Pisolated-docker` spins up a
   `docker:dind` container via Testcontainers, points
   `DOCKER_HOST=tcp://<dind>:2375` at it, and both Warden-under-test
   and its child compose calls hit that isolated daemon. In CI this
   profile is off (GH Actions gives every job its own daemon
   already).
3. **Multi-package scenario tests via `ComposeContainer`.** For
   tests that need "start core + privacy + media as a real project
   and assert Warden sees them correctly", `ComposeContainer` is
   the cleanest way — it does the compose lifecycle for you.
4. **Playwright + Warden container.** The E2E suite starts a Warden
   container (Testcontainers), waits for `/health`, and points
   Playwright at the exposed port. The Warden image is the SUT; the
   browser is the driver.

Rule of thumb: **if the test is about docker-java behaviour, don't
use Testcontainers. If the test is about Warden-as-a-shipped-artefact
or about daemon isolation, do.**

### 14.4 Frontend

- **Vitest 4.1** for components with logic (composables, form
  validators, security-score reducers). Snapshot tests discouraged.
- **Playwright 1.62** for the first-run wizard and package-
  add/remove flow end-to-end. Runs against the Testcontainers-hosted
  Warden from §14.3.

### 14.5 Security tests

- OWASP ZAP baseline scan in CI, mandatory to pass before release.
- `npm audit --production` + `mvn dependency-check:check` gates on
  Critical/High only (Medium/Low ignored to avoid alert fatigue).

### 14.6 Coverage targets

- Services: ≥ 80% line coverage.
- Overall backend: ≥ 60%.
- Frontend components with logic: ≥ 70%.
- Do not chase 100%; coverage past 80% usually tests trivialities.

---

## 15. Non-goals (say no to these)

- User-facing compose.yml editor.
- Multi-node/multi-cluster management.
- Plugin marketplace.
- Custom scripting/automation UI.
- Notifications channel (email, Slack) in v1. Native browser
  notifications only.
- Log aggregation (Loki/journald/etc).
- Metrics beyond the four core ones in v1.
- Kubernetes support.
- Backup restore UI (deliberately manual to reduce blast radius).

---

## 16. Success criteria for v1

1. A non-technical user (or a new AI agent) with SSH access to a
   fresh Debian box can go from `curl … bootstrap.sh | bash` (with
   `ENABLE_PACKAGES=core dashboard`) to a working, secured server
   without ever opening a second terminal.
2. Every mutating action leaves an audit trail.
3. Any secret you set stays a secret in every log and every JSON
   response.
4. Doctor + Warden together catch all 10 pitfalls in §11 before they
   destroy user data.
5. The onboarding wizard fits on one laptop screen at 1440x900 (no
   scrolling) at every step.

---

## 17. What to do first (if you're the agent picking this up)

1. Read this brief end-to-end. Then read `docs/PACKAGE_CONTRACT.md`,
   `docs/ARCHITECTURE.md`, and `docs/OPERATIONS.md` in the home.local
   repo.
2. Skim `bootstrap.sh` and `scripts/lib/*.sh` — those are the CLI
   you're replacing.
3. Skim two package pairs to feel the shape:
   `packages/{core, monitoring}/{manifest.yml, compose.yml, .env.example}`.
4. Read §18 (Prior art) before making UX decisions — there are ten
   existing products in this space, and the brief takes a strong
   position on what to steal from each.
5. Fork the home.local repo and check out `productionize`.
6. Create a new empty repo for Warden.
7. Scaffold M1 in a single PR: Maven parent + `warden-server` (Spring
   Boot Hello World) + `warden-web` (Vue Hello World) + Dockerfile
   that serves them both from one port.
8. Add `packages/dashboard/` in a PR against home.local.
9. When M1 lands, ping the human, then start M2.

---

## 18. Prior art — what to steal, what to avoid

The self-hosted dashboard space is crowded. Before you invent a
navigation pattern, read this. Every product below solved a piece of
the problem; Warden's job is to synthesize the good ideas, not
relitigate them.

Categories:

- **Tile dashboards** — read-only launchers with widgets
  (Homepage, Homarr, Glance, TraLa).
- **App-store OSes** — own the whole box, ship an app catalogue
  (CasaOS, ZimaOS, Runtipi, UnRAID).
- **Container / server admin panels** — general-purpose ops UIs
  (Portainer, Cockpit, Proxmox).

Warden lives closest to the third category (admin panel), but
borrows heavily from the second (curated, opinionated app catalogue
via home.local packages). It is deliberately **not** a tile
dashboard — Homepage already exists in `packages/core`.

### 18.1 Homepage — the tile grid already in-house

**One-line:** static, YAML-driven service launcher with widgets for
100+ apps; API keys proxied through the server so the client never
sees them.

**Steal:**
- **Docker-label discovery.** Users write `homepage.group="Media"` on
  a container and it shows up. Warden should treat these labels as
  authoritative and merge them with the package manifest.
- **Widget-per-service pattern.** Each package can declare a widget
  spec (`homepage.yml`). Warden should render the same specs in its
  own admin views so the "health" pane is data-driven.
- **API-key proxying.** Never expose secrets to the browser. Every
  outbound call to a managed app goes through the Warden server.

**Avoid:**
- **Read-only.** Homepage does one thing well; users still SSH for
  the other 90%. Warden's job is the other 90%.
- **YAML-only configuration.** Homepage's config surface is a `.yaml`
  file. That's fine for a bookmark grid; unacceptable for an admin
  panel. Warden edits YAML on the user's behalf, atomically.
- **Static build.** Static works when there's no auth and no mutation.
  Warden has both.

### 18.2 Homarr — the drag-and-drop dashboard

**One-line:** Next.js dashboard with a grid editor, per-tile widgets,
7,000 icons, and "advanced secrets management", packaged as one
container. The original repo is archived; v1+ lives at
`homarr-labs/homarr`.

**Steal:**
- **Icon picker with fuzzy search.** Users spend a shocking amount of
  time picking icons. Ship a searchable picker backed by
  [selfh.st/icons](https://selfh.st/icons) + mdi/lucide sets.
- **First-class secret rotation UI.** Homarr treats secrets as
  editable-but-audited objects. Warden should too — every `.env`
  value has a history and a rotation button.
- **Modular integrations catalogue.** Homarr publishes a table of
  supported apps with a per-app widget doc. Warden's package manifests
  give us the same catalogue for free.
- **Live status pills.** Every tile gets a green/red status dot from
  the container-health probe. Do the same on our package list.

**Avoid:**
- **Drag-and-drop as the primary interaction.** Great for a launcher,
  bad for an admin panel. Warden's layout is fixed; drag-and-drop is
  a v2 stretch goal, if at all.
- **Building your own auth system.** Homarr rolled its own users +
  invites + roles. Spring Security + WebAuthn is battle-tested; use
  it and ship faster.
- **Heavy client framework.** Next.js + tRPC + Drizzle + Mantine is a
  lot of moving parts. Vue + Vite + a REST API is enough.

### 18.3 Glance — the small, fast, feed-focused dashboard

**One-line:** Go, ~20 MB single binary or tiny container, YAML pages
with columns and widgets (RSS, Reddit, YouTube, weather, markets,
docker-containers, server-stats). Vanilla JS on the client.

**Steal:**
- **Ruthless performance budget.** Glance boasts uncached page loads
  in ~1 s. Warden should aim for the same on the primary dashboard:
  under 300 KB JS, under 1 s time-to-interactive on a Pi 4.
- **Vanilla JS in critical paths.** Not saying drop Vue — but the SSE
  event feed, the container status pills, and the metrics chart don't
  need reactive state trees. Keep them plain.
- **Column layout with sizes.** Glance's `size: small / full / small`
  column model is a great mental model for the dashboard grid.
- **A "docker-containers" widget as a first-class citizen.** Copy the
  "show every container with a health-hint tag" concept.

**Avoid:**
- **All the RSS/Reddit/YouTube widgets.** Out of scope. Glance is a
  personal-portal; Warden is an admin panel.
- **Server-rendered HTML templates.** Fine for Glance's read-only
  surface; a poor fit for our forms and streaming views.

### 18.4 TraLa — the router-reflecting dashboard

**One-line:** reads Traefik's admin API, groups discovered routers
by tags from `selfh.st/apps`, auto-picks icons from `selfh.st/icons`.

**Steal:**
- **Reflect the router truth.** Warden should read Caddy's admin API
  (`:2019/config/` or `/id/{id}`) and surface "vhosts caddy actually
  knows about right now" alongside "vhosts the packages *say* should
  exist". Divergence is a security-relevant fact.
- **`selfh.st/apps` + `selfh.st/icons` as reference metadata.**
  Ubiquitous, high-quality, opinionated icon set. Use it.
- **Group by app-category tag automatically.** Warden's manifest
  already has a `category` field — lean into it as the primary grouping
  in every list view.

**Avoid:**
- **Single-integration bind.** TraLa only works with Traefik. Warden
  should assume Caddy but not be structurally locked to it — abstract
  the vhost-reflection behind a small interface.

### 18.5 CasaOS / ZimaOS — the app-store OS

**One-line:** CasaOS is a "personal cloud OS" with a friendly app
catalogue, file manager, media playback, and network storage. ZimaOS
is its OTA-friendly successor image, tuned for the ZimaBoard /
ZimaCube hardware but installable on any x86-64 UEFI machine.

**Steal:**
- **The onboarding vibe.** CasaOS's first-run is warm and
  screenshotted, not a wall of forms. Copy the tone: one big call to
  action per screen, plain language, images.
- **An app catalogue as the primary navigation.** CasaOS treats
  "install an app" as the central action, not a hidden menu. Warden
  should surface `packages/*` the same way: as a catalogue with
  categories, previews, screenshots, ratings-esque "how commonly
  used" signals.
- **First-class file browser to the mounted data volumes.** Not for
  v1, but a great v2 feature. Users constantly want to peek at their
  `data/<pkg>/config`.
- **A visible "suggested next step" strip.** CasaOS shows CPU/RAM/net
  live plus a "you should back up" / "you should update" prompt.

**Avoid:**
- **Owning the whole OS.** Warden is an app you install on someone's
  Ubuntu, not a distro. That's the entire point of home.local's L2/L3
  split.
- **A closed app taxonomy.** CasaOS's app store is curated by
  IceWhale. Warden's catalogue is the local `packages/*` directory
  — the user (and third parties) can add packages by dropping a
  folder.
- **Custom compose formats.** CasaOS wraps compose in its own
  `docker-compose.yml`-plus-metadata. Ours ARE plain compose; keep it
  that way.

### 18.6 Proxmox VE — the datacenter-in-a-browser

**One-line:** virtualization host with VMs and LXC containers, a
tree-navigator (datacenter → node → guest), live resource graphs,
backup + snapshot lifecycle, clustering. The gold-standard admin UI
in the homelab world.

**Steal:**
- **The tree view as the primary spatial model.** Datacenter (=
  fleet) → Node (= host) → Guest (= package/container). Warden is v1
  single-host, but structure the URL and store hierarchy this way so
  multi-host is a config change, not a rewrite.
- **Live graphs at every level of the tree.** Every screen has
  aggregate stats for its subtree. Cheap to implement, huge UX win.
- **Backup/restore lifecycle as a first-class object.** Proxmox
  treats backups as versioned artefacts you can browse, tag, and
  restore from. Warden's backup list should feel the same.
- **"Bulk action" pattern.** Select multiple guests → apply an action.
  Warden should support this for packages ("restart selected",
  "upgrade selected").
- **Task log at the bottom.** A persistent, dismissible drawer
  showing the current + last-N background tasks (up.sh, pin, backup)
  with expandable log output. Do this from day one.

**Avoid:**
- **Enterprise UI density.** Proxmox is dense on purpose; its users
  are ops engineers. Warden's users are a mix, so bias toward less
  chrome and larger targets.
- **VM/LXC concepts.** They're out of scope; don't leak them into
  the vocabulary.
- **Custom ExtJS.** Not a decision to relitigate; just note we're
  taking the *ideas*, not the frontend stack.

### 18.7 UnRAID — the storage-first NAS

**One-line:** paid NAS OS with parity-based any-size-drives arrays,
docker + VM management, and the fabled "Community Applications"
plugin — a curated catalogue where you click a template and get
templated docker-run flags.

**Steal:**
- **The docker template pattern.** UnRAID templates are XML files
  that translate to `docker run` flags with per-field descriptions.
  Warden's `.env.example` files play the same role; render them as
  friendly forms with the inline comments as helper text (**major**
  differentiator vs Portainer's raw compose editor).
- **The "Advanced view" toggle.** UnRAID hides the ugly bits behind a
  toggle so casuals see friendly forms, power users see raw values.
  Steal directly.
- **A dedicated dashboard tile per array/disk.** For Warden, replace
  "array" with "data volume" — show per-package disk usage at a glance.
- **Discoverable community apps.** Users install things they didn't
  know existed. Even without a real app store, Warden should surface
  a "Suggested next packages" strip based on category (e.g., if you
  have `media`, suggest `home-automation`).

**Avoid:**
- **A paid tier.** UnRAID's UI polish is partially funded by licence
  sales; Warden is MIT/GPL and doesn't have that budget. Compensate
  with simplicity, not chrome.
- **XML template files.** We already have `manifest.yml` + `.env.example`.
- **A parallel plugin ecosystem.** Packages are the extension point.

### 18.8 Portainer CE — the neutral docker admin panel

**One-line:** general-purpose Docker/Swarm/Kubernetes admin UI:
containers, images, volumes, networks, stacks (compose), registries,
logs, exec, resource graphs. Community edition free, business
edition adds RBAC + support.

**Steal:**
- **The container detail drawer.** Portainer's per-container view
  (stats, logs, exec, inspect, network attachments) is the reference
  design. Copy it for our per-container detail view, but constrain
  it to the packages we know about — no full docker admin surface.
- **The stack (compose) diff view.** Before Portainer applies a
  change, it shows a diff of the compose file. Warden should show a
  plan (packages added/removed, ports opened/closed, env keys added)
  before every install.
- **Live event log.** Portainer streams docker events into a
  bottom-of-page log. Take this pattern for the task drawer.
- **Endpoints concept.** Even if Warden is single-host in v1, expose
  the "current endpoint" in the URL / top-bar so multi-host later is
  a URL change, not a rewrite (same principle as Proxmox tree).

**Avoid:**
- **General-purpose docker exposure.** Portainer's audience includes
  ops engineers who want to `docker run` anything. That path leads to
  users breaking home.local's invariants. Warden should refuse to
  create resources outside the known package set.
- **The "choose your orchestrator" flow.** Docker only. If someone
  needs Swarm/K8s, they don't need Warden.
- **License-gated features.** Everything Warden ships is open. Don't
  design around a "pro tier" from day one.

### 18.9 Runtipi — the personal homeserver orchestrator

**One-line:** NestJS + React + Drizzle app that turns a Debian box
into an app-store server. Curated repo of ~200 apps with
one-click install, per-app config forms, users, TOTP, guest
dashboard.

**Steal:**
- **Config forms driven by app metadata.** Runtipi apps carry a
  `config.json` describing each field type (text, password, url,
  boolean). Warden's `.env.example` comments should be parsed into
  the same field-type hints (`# type: password`, `# type: url`,
  `# required`, `# generated`).
- **A public app-repo model.** Runtipi supports community app stores
  as git repos. Warden's `packages/*` structure already supports this
  — lean in with a `warden-app-store.example.com` future concept
  that points to a git remote of extra packages.
- **TOTP as a backup 2FA path.** WebAuthn is the primary, but not
  every user will have a passkey device. TOTP is a fine secondary.
- **Guest dashboard.** A read-only "what's on this server" that a
  family member can open without an account. Perfect fit for v2.
- **Backups per-app.** Runtipi does per-app backup/restore. Warden's
  `scripts/backup.sh` covers all packages at once; expose it
  per-package too.

**Avoid:**
- **Reinventing package format.** Runtipi has its own JSON schema
  parallel to compose. We already committed to plain compose +
  manifest.yml. Don't re-relitigate.
- **Rolling your own auth.** Runtipi's auth is another
  built-from-scratch stack. Use Spring Security.
- **Node/TS end to end.** Runtipi is TS. We chose Java for the
  backend to make docker-java trivial and to reduce the surface area
  of a JS supply chain.

### 18.10 Cockpit — the Red Hat systemd admin console

**One-line:** browser-based Linux server admin, systemd-native.
Inspect and control services, journal, networks, storage, VMs,
firewall, updates. On-demand via systemd socket activation — zero
resource cost when idle. Multi-host via "machines".

**Steal:**
- **Integrated terminal.** Cockpit ships an xterm.js terminal on
  every host. For Warden, this is v2 material — but structurally,
  every "container" detail view should have a “Logs / Exec” tab
  ready for it.
- **On-demand activation.** Warden's JVM should be lazy where
  possible: cheap idle, aggressive warm-up when a user is present.
  Aim for < 100 MB RSS at idle.
- **Multi-host as “machines”.** Same principle as Proxmox/Portainer.
- **“Fix it” in-context.** Cockpit's SELinux page finds violations
  and offers a one-click policy edit. Every Warden security finding
  should end in a button that takes the action, not just describes
  it.
- **Terminal as a fallback for anything not yet UI-wrapped.** Users
  should never hit a wall.

**Avoid:**
- **RHEL vocabulary.** SELinux, subscription-manager, journald
  specifics — leak these and Ubuntu/Debian users get lost.
- **Server-side rendered pages with per-page reloads.** Cockpit does
  this and it feels dated. Warden is an SPA.
- **Generic Linux admin scope.** Cockpit does firewalls, users,
  storage, kdump, everything. Warden stays focused on the
  home.local surface.

### 18.11 Synthesis: the ten principles Warden takes forward

Distilled from the above:

1. **The app catalogue is the front door.** Not a widget grid. Not a
   raw docker list. First screen after login is “what's installed”
   plus “what could you add.”
2. **Every list has a status pill and a next action.** Never render a
   row without a “do something” affordance.
3. **Forms, not YAML.** Users edit `.env` values through typed fields
   with inline docs. Under the hood the YAML/env is authoritative.
4. **Diff before apply.** Every mutation shows a plan (packages,
   ports, env keys, image digests). Approve, then execute.
5. **A persistent task drawer.** Long-running actions surface at the
   bottom of the screen, expandable to full logs. Never a modal.
6. **Reflect reality, not just intent.** Show both `.state.yml`
   (intent) and `docker ps` / Caddy admin API (reality), and flag
   drift.
7. **Security findings end in a button.** Not a link to a doc — a
   button that takes the action, plus a link to the doc.
8. **Advanced mode is a toggle.** Casuals see friendly forms; power
   users flip a switch and see raw values.
9. **Guest read-only view.** A share-safe URL family members can open
   to see what's up, without an account. (v2, but plan for it.)
10. **Multi-host later; single-host now, but shaped for both.** URL
    structure, store hierarchy, and API paths all assume an endpoint
    id, even when there's only one.

### 18.12 What every one of them gets wrong (and Warden must not)

- **They all assume the user knows what they want to install.** None
  of them provide a decision-tree wizard for “I want to build a media
  server” → packages selected + configured + started. Warden's
  first-run wizard is a competitive advantage.
- **None of them audit their own effect on the host.** No one shows
  you the current UFW rules, the current fail2ban ban count, the
  current unattended-upgrades run history. Security posture is
  Warden's other differentiator.
- **None of them protect the user from the specific footguns** in
  §11. Bind-mount rm, GID drift, multi-`-f` compose paths — these
  are unique to how home.local composes packages, and Warden should
  own them.

That's the pitch: **a Portainer-shaped admin panel with a
Runtipi-shaped catalogue, a CasaOS-shaped onboarding, and a
Cockpit-shaped “fix it” attitude, purpose-built for the
home.local package model.**

