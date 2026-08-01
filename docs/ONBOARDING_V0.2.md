# Onboarding v0.2

v0.1 shipped an onboarding wizard that worked once, straight through,
but was fragile the moment the user did anything unexpected: refresh
mid-wizard, click Welcome in the sidebar after starting, or hit
Continue twice. v0.2 rebuilds the wizard around three principles:

- **The URL is the step cursor.** The store no longer holds a `currentStep`
  that can drift from the visible page.
- **The server holds the durable draft.** A refresh restores domain,
  packages, admin username, DNS mode — everything except the
  freshly-generated admin password.
- **`sessionStorage` cushions pre-submit typing.** A refresh while typing
  in the domain field does not lose the input, but nothing survives a
  browser close and nothing sensitive is stored.

## API surface

| Method | Path                          | Purpose                                                                 |
|--------|-------------------------------|-------------------------------------------------------------------------|
| GET    | `/api/onboarding`             | Full draft. Router guard + store hydration read this. **Public.**       |
| PATCH  | `/api/onboarding`             | Partial update of any draft field (domain, packages, dns_mode, step).   |
| POST   | `/api/onboarding/admin`       | One-shot creation of the initial admin. 409 on re-invocation.           |
| POST   | `/api/onboarding/install`     | Apply draft (write `.state.yml` + `packages/core/.env`), report diff.   |
| POST   | `/api/onboarding/complete`    | Commit — flips `onboarding.complete = true`.                            |
| GET    | `/api/onboarding/env`         | Host facts (hostname, distro, kernel, LAN IP, CPU/RAM/disk/GPU).        |
| GET    | `/api/onboarding/plan`        | Preview: ports, vhosts, warnings computed from manifests.               |
| GET    | `/api/onboarding/status`      | **Deprecated.** Slim `{complete, bootstrap_mode, step}` view.           |
| POST   | `/api/onboarding/domain`      | **Deprecated shim** over `PATCH /api/onboarding`. One release only.     |
| POST   | `/api/onboarding/packages`    | **Deprecated shim** over `PATCH /api/onboarding`. One release only.     |
| GET    | `/api/system/caddy-root.crt`  | Root CA download for the TLS step. **Public.** Fetches via docker exec. |

All endpoints under `/api/onboarding/**` are declared public in
`SecurityConfig`. Mutating endpoints also require
`!isBootstrapMode() && !isComplete()`, enforced by
`OnboardingService.guardMidOnboarding()`. `POST /admin` is the only
route that runs *in* bootstrap mode.

### Guard matrix

| Endpoint      | Requires admin? | Requires !complete? | Notes                                    |
|---------------|-----------------|---------------------|------------------------------------------|
| GET /         | no              | no                  | always readable                          |
| PATCH /       | yes             | yes                 | 409 if bootstrap or complete             |
| POST /admin   | must NOT exist  | n/a                 | 409 if admin already created             |
| POST /install | yes             | yes                 | idempotent — safe to retry               |
| POST /complete| yes             | yes                 | one-way flip                             |
| GET /env      | no              | no                  | public system facts only                 |
| GET /plan     | no              | no                  | read-only preview                        |

## Store architecture

The store (`packages/dashboard/frontend/src/stores/onboarding.ts`)
mirrors the server for local UX responsiveness. Three data tiers,
each with a specific job:

| Tier             | Contents                                                    | Lifetime                     |
|------------------|-------------------------------------------------------------|------------------------------|
| Server           | admin username, domain, enabled_packages, dns_mode, step    | durable, cross-device        |
| sessionStorage   | Unsaved form drafts (domain being typed, package toggles)   | tab lifetime                 |
| In-memory        | Generated admin password, `copied` flags, transient UI      | until component unmount / F5 |

Rules that fall out of this:

- `currentStep` is derived from the URL by `OnboardingShell.vue`
  (watches `useRoute().path`, calls `store.syncFromRoute(seg)`).
  `hydrate()` never overwrites it. This is what kills the drift bug
  where clicking Welcome in the sidebar and then Continue jumped to
  wherever the store cursor had ended up.
- `hydrate()` populates domain, packages, dns_mode, admin_username,
  and reconstructs the sidebar's `completed` set from the server's
  step index. Called once per SPA lifetime by the router guard.
- `patchDraft(fields)` is the single mutation path. Optimistic local
  update → server PATCH → sessionStorage cleanup for keys the server
  now owns.
- The admin password lives only in `store.admin.password` (in-memory).
  It is never written to `localStorage` or `sessionStorage`, both of
  which are XSS-harvestable. The Admin view warns the user that a
  refresh before Continue discards it, and the account itself is fine
  — an SSH reset path recovers it.

## OnboardingDraft shape

```ts
export type OnboardingStepId =
  | 'welcome' | 'admin' | 'domain' | 'packages'
  | 'secrets' | 'dns'   | 'tls'    | 'review'   | 'done';

export type DnsMode = 'adguard' | 'router' | 'mdns';

export interface OnboardingDraft {
  complete: boolean;
  bootstrap_mode: boolean;
  step: OnboardingStepId;
  admin_username: string | null;
  domain: string | null;
  enabled_packages: string[];
  dns_mode: DnsMode | null;
}

export interface OnboardingPatch {
  domain?: string;
  enabled_packages?: string[];
  dns_mode?: DnsMode;
  step?: OnboardingStepId;
}
```

Example `GET /api/onboarding` response mid-wizard:

```json
{
  "complete": false,
  "bootstrap_mode": false,
  "step": "packages",
  "admin_username": "bruce",
  "domain": "aurora.local",
  "enabled_packages": ["core", "privacy", "storage"],
  "dns_mode": null
}
```

Example `PATCH /api/onboarding` request from the DNS step:

```json
{
  "dns_mode": "adguard",
  "step": "tls"
}
```

The server accepts both snake_case (`enabled_packages`, `dns_mode`)
and the legacy `enabled`/`names` keys, so a stale SPA build talking
to a v0.2 server keeps working through one release.

## Sequence: fresh install

```
                        ┌─────────────────┐
  browser hits /        │ router.beforeEach│
  ─────────────────────▶│ - store.hydrate()│ ── GET /api/onboarding ──▶ server
                        │ - if not complete│
                        │   redirect to    │
                        │   /onboarding/${step}
                        └────────┬────────┘
                                 ▼
                          /onboarding/welcome
                                 │
                                 │ GET /api/onboarding/env  (host facts)
                                 │ Continue
                                 ▼
                          /onboarding/admin
                                 │ POST /api/onboarding/admin
                                 │   {username, password}
                                 │ store.hydrate()  (bootstrap_mode: false)
                                 ▼
                          /onboarding/domain
                                 │ PATCH { domain, step: 'packages' }
                                 ▼
                          /onboarding/packages
                                 │ debounced GET /plan?enabled=…  (warnings)
                                 │ PATCH { enabled_packages, step: 'secrets' }
                                 ▼
                          /onboarding/secrets
                                 │   (v0.1: visual stub only)
                                 ▼
                          /onboarding/dns
                                 │ PATCH { dns_mode, step: 'tls' }
                                 ▼
                          /onboarding/tls
                                 │ GET /api/system/caddy-root.crt  (download)
                                 ▼
                          /onboarding/review
                                 │ GET /api/onboarding/plan  (real ports/vhosts)
                                 │ Install ─▶ PATCH { step: 'done' }
                                 │           POST /api/onboarding/install
                                 │           POST /api/onboarding/complete
                                 ▼
                          /onboarding/done
                                 │ shows host_command + packages_to_start
                                 │ Take me to Aurora
                                 ▼
                                  /
```

Refresh at any step: the guard re-hydrates from `GET /api/onboarding`
and redirects to `/onboarding/${server.step}` if the URL and server
disagree, otherwise it renders the step with all fields prefilled.

## Known limitations

**Post-admin, pre-complete unauth hole.** Between `POST /admin`
success and `POST /complete`, mutating routes are still declared
public in `SecurityConfig`. Anyone on the LAN who can reach
`admin.$DOMAIN` can PATCH the draft and hit `/complete` themselves.
Fix in v0.3: either require session auth once `bootstrap_mode=false`
(forces the user to log in with the password they just saved before
continuing the wizard), or set a bootstrap-token cookie at admin
creation and require it on subsequent onboarding calls. The first
option is cleaner but adds a login step mid-wizard.

**Secrets step is a visual stub.** `OnboardingSecrets.vue` shows the
selected package list and an info alert. Per-package `.env` editing
ships in v0.2+. Until then, secrets in `.env.example` are used
verbatim; auto-generation of missing secrets happens at first `up.sh`.

**Install does not spawn containers.** The aurora container image
does not carry `docker compose` (Alpine multi-stage build, minimal
runtime). `POST /install` writes `.state.yml` and reports which
packages the operator must bring up via `scripts/up.sh` on the host.
The Done screen surfaces that as an action-required card. This is
intentional for v0.1 — Aurora is the fuse box, not the electrician.

## Shipped in v0.2

**LAN IP detection reads host `proc` fib_trie.** `SystemService.detectLanIp()`
parses the host PID-1 netns fib_trie for RFC1918 addresses, prefers
`192.168/16` then `10/8` then non-docker `172.16-31`, and skips a
configurable exclusion list (docker bridges, VPN interfaces). Host proc
path is injected via `props.hostProcPath()`; exclusions live in
application config. Landed in `3ff7dfb`, hardened in `e38a721`.

**Manifest-driven `plan.warnings`.** The static planner rules (media
without privacy, `dns_mode=adguard` without privacy, empty selection,
missing core, missing domain) are joined by per-manifest warning rules
declared under `warnings:` in each `packages/*/manifest.yml`. Rule types:
`ram_below_mb`, `cpu_threads_lt`, `free_disk_gb_below`, `no_gpu`, plus a
resource-budget summary that sums `requires.min_ram_mb` /
`requires.min_disk_gb` across the selected packages and warns when the
box's ceiling is exceeded. Coverage: `ai`, `media`, plus 4-6 additional
packages. Evaluator has unit tests. Landed in `351a4a5`, `d6661ca`,
extended in `34c199a`. The frontend calls `GET /plan?enabled=…` on
selection change (debounced 250 ms, monotonic sequence guard).

## Migration from v0.1 clients

If you had a bookmarklet, a script, or a stale SPA build calling the
v0.1 shape, it will still work through one release:

| Old call                             | New equivalent                                     |
|--------------------------------------|----------------------------------------------------|
| `GET /api/onboarding/status`         | `GET /api/onboarding` (returns the same fields + more) |
| `POST /api/onboarding/domain {...}`  | `PATCH /api/onboarding { domain }`                 |
| `POST /api/onboarding/packages {...}`| `PATCH /api/onboarding { enabled_packages }`       |
| (dns had no server route in v0.1)    | `PATCH /api/onboarding { dns_mode }`               |

Deprecated routes log an audit event tagged `onboarding.deprecated.*`.
Watch `/api/audit` in v0.2+ if you want to know when it's safe to
delete them.

Follow-up: exact source-line references for the router guard
(`packages/dashboard/frontend/src/router/index.ts`), `hydrate()`
(`stores/onboarding.ts`), the controller `patch()`
(`controllers/OnboardingController.java`), and the `OnboardingDraft`
record (`services/OnboardingService.java`) are omitted here because they
shift with routine refactors. Grep by symbol name — the shapes are
stable, the line numbers are not. Frontend refactor shipped in `ce2f6be`.
