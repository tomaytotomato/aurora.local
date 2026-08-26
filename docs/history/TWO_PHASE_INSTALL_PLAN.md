# Aurora Install — Two-Phase Redesign Plan

**Status:** IMPLEMENTED (2026-08-25) — both passes landed on `feat/two-phase-core-sso`.
See commits: Authelia→core migration + Phase-1 non-interactive bootstrap. Backend
821 tests green, frontend 533 tests + typecheck + build green, shellcheck + compose
config + manifest schema + openapi checks green.
**Goal:** collapse the confusing overlap between `bootstrap.sh` (CLI) and the web
onboarding wizard into two clean, non-overlapping phases with a single owner each.

## Locked decisions

- **D1 — Phase 1 is fully non-interactive.** Auto-detect hostname (`hostname`),
  user (`$USER`), LAN CIDR + LAN IP. Zero prompts. Phase 1 is "run one command."
  The web wizard is the *only* place a human answers questions.
- **D2 — `core/.env DOMAIN` is the sole runtime domain.** `group_vars/all.yml domain`
  defaults permanently to `aurora.local` (host/ansible only). The wizard owns the
  live domain via `core/.env`.
- **D3 — No package selection during onboarding.** The wizard does NOT show a
  package picker. Phase 1 brings up only `core` + `dashboard`; onboarding configures
  the box; on success the user lands on the **dashboard**, where they browse the
  catalogue and install packages at their own pace. Don't overwhelm — get core
  running, then hand off to the dashboard.
- **D4 — SSO/Authelia is CORE and always-on from Day 0.** *(Reversed — Bruce, 2026-08-25.)*
  Authelia is migrated INTO `core` and runs on every fresh box immediately, fully
  integrated. The standalone `identity` package concept is **removed**. SSO is not a
  wizard toggle or a day-2 install — it is part of the base platform. The onboarding
  **admin** step still creates the dashboard's own DB-backed login
  (`createInitialAdmin`, BCrypt), which then projects into Authelia's `users_database`
  via the existing `UserChangedEvent → AutheliaService.reconcile()` chain. See the
  **"Authelia → core migration"** section below for the full file-level plan.
- **D5 — Only `core` is mandatory.** `storage` is deferred. Change
  `MANDATORY_PACKAGES` from `[core, storage]` to `[core]`. Users add `storage` (and
  everything else) from the dashboard catalogue day-2.

---

## The problem today

`bootstrap.sh` and the web wizard both collect and write overlapping config
(domain, package selection, `.state.yml`, secrets). Neither fully owns the flow.
A user is prompted for the same things twice, files can silently disagree
(`group_vars/all.yml domain` vs `packages/core/.env DOMAIN`), and it's unclear
where "install" ends and "onboarding" begins.

Key facts established by recon:

- **The dashboard container CAN start other containers.** `LaunchService` runs
  `bash scripts/up.sh <pkgs>` *inside* the dashboard container (docker.sock + repo
  root are bind-mounted). The Done screen's "Start services" button already does
  the real launch with a streamed SSE log. There is **no need for a host watcher
  or command queue** — Phase 2 actions already execute in-container.
- The **only** thing that fundamentally needs the host is: (a) root Ansible host
  prep, and (b) bringing up the **dashboard container itself** the first time.
- `POST /onboarding/install`'s `host_command: "cd ~/aurora.local && ./scripts/up.sh"`
  is **vestigial** — superseded by in-container `/launch`. It should be removed.
- Bootstrap signals differ by design: CLI keys off `.state.yml` presence; the
  wizard keys off the DB (`users.count()==0` = bootstrap_mode;
  `settings[onboarding.complete]` = done).

---

## Target model: two phases, one owner each

```
┌─ PHASE 1 — BASE SETUP (host, root) ─────────────────────────────┐
│  Owner: bootstrap.sh  — FULLY NON-INTERACTIVE (D1)              │
│                                                                 │
│  1. Auto-detect host facts (NO prompts):                        │
│       hostname=$(hostname), user=$USER, LAN CIDR + LAN IP auto  │
│  2. Write inventory.ini + group_vars/all.yml                    │
│       (domain defaulted to aurora.local — D2)                   │
│  3. Write a minimal .state.yml (hostname; enabled=[core])       │
│  4. Run Ansible host prep (host/site.yml):                      │
│       docker, docker group, UFW, avahi/mDNS, dns-stub, swap,    │
│       storage mounts, ssh-hardening, fail2ban, unattended-up.   │
│  5. Bring up ONLY: core (Caddy proxy) + dashboard               │
│       → scripts/up.sh core dashboard                            │
│                                                                 │
│  End state: browser can reach the Aurora wizard. Nothing else.  │
└─────────────────────────────────────────────────────────────────┘
                          │  user opens https://<host>/ or aurora.local
                          ▼
┌─ PHASE 2 — AURORA ONBOARDING (web wizard, in-container) ────────┐
│  Owner: /api/onboarding/* + LaunchService                       │
│  NO package picker (D3) — configure the box, then hand off.     │
│                                                                 │
│  welcome  → host facts (read-only) + "base setup done"          │
│  admin    → create first admin (flips bootstrap_mode off, +tz)  │
│  domain   → .state.yml domain + packages/core/.env DOMAIN       │
│  secrets  → rotate/manage core secrets                          │
│  dns      → dns_mode                                            │
│  tls      → root CA trust guidance                              │
│  review   → confirm settings                                    │
│  done     → /launch re-converges core+dashboard (SSE log),      │
│             /complete, then REDIRECT to the dashboard           │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─ POST-SETUP — DASHBOARD (day-2, at the user's pace) ────────────┐
│  Catalogue: browse + install packages (media/photos/git/...)   │
│  Each install → /launch in-container. No overwhelm up front.    │
└─────────────────────────────────────────────────────────────────┘
```

**One-line rule:** Phase 1 = "one command makes the box able to run Aurora and show
me the wizard." Phase 2 = "the wizard configures core, then drops me on the
dashboard, where I add packages whenever I want."

---

## Ownership matrix (who writes what — after redesign)

| File / field                        | Phase 1 (bootstrap) | Phase 2 (wizard) |
|-------------------------------------|:-------------------:|:----------------:|
| `inventory.ini`                     | ✅ owns             |                  |
| `group_vars/all.yml` (host/ansible) | ✅ owns (domain=aurora.local default) | |
| `.state.yml` hostname               | ✅ owns             |                  |
| `.state.yml` domain                 |                     | ✅ owns          |
| `.state.yml` enabled[] / profiles   | seed `[core]` only  | ✅ owns          |
| `packages/core/.env DOMAIN`         |                     | ✅ owns (runtime truth) |
| `packages/<pkg>/.env` secrets       | up.sh seed defaults | ✅ owns (manage) |
| Host prep (docker/ufw/avahi/...)    | ✅ owns             |                  |
| Bring up core + dashboard           | ✅ owns             |                  |
| Bring up all other packages         |                     | ✅ owns (dashboard catalogue, day-2) |

The double-entry (domain, packages, timezone) is eliminated by moving each to a
single owner.

---

## Work items

### A. Phase 1 — make `bootstrap.sh` a single non-interactive command (D1)

1. **Remove ALL interactive prompts** from `_interactive_install`. Delete the
   `prompt_inputbox` calls (hostname/domain/tz/user/CIDR/IP) and the
   `prompt_checklist` package picker. Collapse `_interactive_install` +
   `_noninteractive_install` into one non-interactive path.
2. **Auto-detect host facts:** `hostname=$(hostname -s)`, `user=$USER` (or
   `SUDO_USER`), LAN CIDR + LAN IP derived from the default route
   (`ip route get 1.1.1.1`). Keep env-var overrides for CI/headless
   (`HOSTNAME/HOME_USER/LAN_CIDR/LAN_IP`), but no prompting ever.
3. **`_write_configs`:** default `group_vars/all.yml domain` to `aurora.local` (D2).
   Never prompt for or write a competing runtime domain.
4. **`_run_up` change:** on first install call `scripts/up.sh core dashboard`
   (explicit), not the full enabled set. Ensure `packages/dashboard/.env` exists so
   up.sh's dashboard orphan-guard forces it in. Fix the tracked bug where no preset
   adds `dashboard` to `.state.yml`.
5. **`state_init`:** write hostname + `enabled: [core]`. Do not write domain here.
6. Keep `add`/`remove`/`list`/`status` subcommands as day-2 CLI escape hatches.

### B. Phase 2 — trim the wizard to config-only (D3)

7. **Remove the packages step entirely** from the wizard. Frontend `STEPS` and
   backend `VALID_STEPS` become the same canonical list:
   `welcome, admin, domain, secrets, dns, tls, review, done`.
   (Also removes the current frontend/backend mismatch where frontend has `sso`
   and neither has a coherent `packages`.)
8. **Remove the `sso` step (D4).** Delete the `OnboardingSso.vue` step from the
   frontend `STEPS` and drop the `sso` route — SSO is no longer an onboarding
   *choice*, it's always-on core (D4). The `POST /sso` enable/disable toggle becomes
   obsolete; see the migration section for its fate. The onboarding **admin** step
   remains and now feeds Authelia's user db via the existing reconcile chain.
9. **`/install` becomes trivial (D5):** change `MANDATORY_PACKAGES` from
   `[core, storage]` to `[core]`. With no picker, the enabled set is just `core`
   (+ `dashboard`, forced by Phase 1). Remove the vestigial `host_command` from the
   `/install` response and any "SSH and run up.sh" copy.
10. **Done screen → redirect to dashboard.** After `/launch` succeeds and
    `/complete` runs, route the user straight into the dashboard app shell (not a
    static "you're done" page). This is the D3 hand-off.
11. **Domain single-source:** confirm wizard writes both `.state.yml domain` and
    `core/.env DOMAIN`; nothing in Phase 1 writes a competing runtime domain.
12. **Hostname label:** the Domain step currently says "Hostname & domain" but only
    writes domain. Make hostname read-only/informational (it's Phase 1's job).

### C. Cross-cutting / polish

13. **Welcome screen** copy: "Base setup is complete — let's configure Aurora,"
    making the two-phase model explicit.
14. **Dashboard catalogue** is the post-onboarding install surface. Verify the
    catalogue → install → `/launch` path works for a user who onboarded with only
    `core` running (this is now the primary way packages get added).
15. **Docs:** rewrite `README.md` Quick start to two steps: (1) run `bootstrap.sh`
    (one non-interactive command), (2) open the browser, finish onboarding, land on
    the dashboard, add packages there. Drop any implication that the user runs
    `up.sh` manually after the wizard.

---

## Explicit non-goals

- No host-side command queue / watcher daemon. Phase 2 already runs in-container.
- No change to the Ansible host roles themselves (already Phase-1 correct).
- No package picker anywhere in onboarding (D3).

---

## Suggested sequencing

1. **B7/B9/B10** — strip the packages step, trim `/install`, redirect Done →
   dashboard. Makes the wizard honestly config-only.
2. **A1–A5** — collapse bootstrap to one non-interactive command; first-run brings
   up `core + dashboard` only.
3. **B8** — resolve SSO placement (Q4).
4. **C13–C15** — messaging, catalogue verification, README.
5. Validate end-to-end on a clean box: one `bootstrap.sh` command → wizard → land on
   dashboard → install a package from the catalogue → it comes up. No duplicate
   prompts; `.state.yml`/`core/.env` consistent.

---

## Resolved questions

- **Q4 — SSO placement → RESOLVED (D4, REVERSED):** Authelia is migrated INTO `core`
  and runs always-on from Day 0. The `identity` package is removed. Not a wizard
  step, not a day-2 install — base platform. Full plan in the migration section below.
- **Q5 — `storage` mandatory → RESOLVED (D5):** deferred. `MANDATORY_PACKAGES` = `[core]`.

---

## Authelia → core migration (D4)

Fold `packages/identity/` into `packages/core` so SSO is live on every fresh box.
Recon confirmed the migration is mostly mechanical; two spots are semantically
tricky (secret-seeding timing, Day-0 admin), and both already have safe seams.

### Why this is safe (the lock-out question)
The apex dashboard vhost is **deliberately NOT behind Authelia** and stays that way:
- `authelia/configuration.yml access_control` **bypasses** the apex `{DOMAIN}`
  (dashboard) and `auth.{DOMAIN}` (portal). Default for `*.{DOMAIN}` is `two_factor`.
- `packages/core/caddy/Caddyfile` apex vhost `reverse_proxy aurora:8090` has **no**
  `import authelia`.
- So the wizard + dashboard login remain reachable with zero Authelia session. The
  dashboard keeps its own BCrypt/session login; Authelia fronts *other* subdomains.
- **Invariant to preserve on the move:** apex bypass in `configuration.yml` + no
  `import authelia` on the apex/dashboard vhost.

### Day-0 secret seeding (already works, just repoint)
- `up.sh` seeds each `packages/<p>/.env` from `.env.example`, then runs
  `scripts/rotate-secrets.sh --apply`, whose generic `*SECRET*/*KEY*` matcher already
  fills `AUTHELIA_JWT_SECRET / SESSION_SECRET / STORAGE_ENCRYPTION_KEY`.
- **Action:** put the 3 blank `AUTHELIA_*` keys in `packages/core/.env.example`. Then
  core bring-up seeds them Day-0 — no wizard needed. `IdentitySecretsService.onReady()`
  seeds unconditionally (drop the `identityEnabled()` gate) as belt-and-braces
  (idempotent; bash wins first, Java no-ops).

### Day-0 admin (the hardest problem — already has a seam)
- `render_identity_seed` copies `users_database.example.yml` (placeholder admin) so
  Authelia can boot (it crash-loops on empty `users:`).
- Onboarding admin creation → `UserChangedEvent.CREATE` → `AutheliaService.reconcile()`
  atomically overwrites `users_database.yml` with the real admin (BCrypt cost 12 on
  both sides — no rehash); Authelia `watch:true` reloads.
- Residual: placeholder admin can reach only the `auth.` portal until the real admin
  projects over it; `two_factor` gates every protected subdomain regardless. Same
  window as today's "mandatory identity." Acceptable; note in follow-ups.

### File moves (mechanical)
| From | To |
|------|----|
| `packages/identity/compose.yml` (authelia service) | 2nd service in `packages/core/compose.yml` |
| `packages/identity/authelia/` (configuration.yml, users_database.example.yml) | `packages/core/authelia/` |
| `packages/identity/caddy.snippet` | `packages/core/caddy.snippet` (rendered into `data/caddy/snippets/`) |
| `packages/identity/.env.example` (3 AUTHELIA_* + SMTP) | merge into `packages/core/.env.example` |
| `packages/identity/pins.env.example` (IMAGE_AUTHELIA) | merge into `packages/core/pins.env.example` |
| `packages/identity/manifest.yml` (required_env, probe container `authelia`, port 9091) | merge into `packages/core/manifest.yml` |
| `packages/identity/` | **DELETE** |

Compose path rewrites: `../identity/authelia:/config` → `./authelia:/config`; the
data volume becomes `../../data/authelia:/data` (Q6 rename). **Preserve verbatim:**
`X_AUTHELIA_CONFIG_FILTERS=template`, the `AURORA_IDENTITY_JWT_SECRET=${AUTHELIA_JWT_SECRET}`
rename hack, the 5 bare SMTP passthrough entries.

### Backend repointing (mechanical)
- `IdentitySecretsService`: `IDENTITY_PACKAGE "identity"→"core"`, `envPath()`/`envExamplePath()`
  → core; drop `identityEnabled()` gate (seed always).
- `scripts/lib/render.sh render_identity_seed`: gate `"identity"→"core"`, repoint source
  to `packages/core/authelia/users_database.example.yml`.
- `AutheliaService.usersDbPath()` + audit string, `UserChangedEvent` doc: rename
  host path `data/identity/authelia/...` → `data/authelia/...` (Q6, clean slate).
- `AuthController` logout `next` gate: `enabled().contains("identity")` → always-on
  (core), so always return the Authelia logout URL when domain is set.
- `dashboard/manifest.yml`: drop `recommends: identity`.
- `OnboardingService`: remove identity from any mandatory/dep logic; MANDATORY = `[core]`.

### `sso.protect` mechanism — KEEP
The per-app protect mechanism stays valuable: `CaddySnippetService` still injects
`import authelia` into a package's vhost when its manifest declares `sso.protect:true`
(`SsoBlock`, `Role` cascade). Migration keeps `SsoBlock`/`Role`/`CaddySnippetService`
unchanged — only the `(authelia)` named route's *source* moves from identity to core.

### Tests + fixtures to update
- Repoint invariants tests' `SOURCE_FILE`: `AutheliaConfigurationInvariantsTests`,
  `AutheliaCaddySnippetInvariantsTests` → `packages/core/...`; move fixtures
  `src/test/resources/identity/*` → `resources/core/*`; move
  `fake-repo/packages/identity/manifest.yml` → `fake-repo/packages/core/`.
- Rewrite/remove `OnboardingControllerSsoTests` (toggle semantics gone) + update
  `OpenApiConformance` if `/sso` changes.
- Path-keyed tests (`AutheliaServiceTests`, `UsersPropagationChainTests`) update
  their `data/identity/authelia` assertions to `data/authelia` (Q6 rename).

## Follow-ups to verify during implementation

- Fresh-box E2E: core up → Authelia healthy with seeded secrets + placeholder admin
  → wizard creates real admin → `users_database.yml` reconciled → log into `auth.`
  portal + enrol 2FA → a protected subdomain (e.g. a sso.protect package) enforces it.
- Confirm the apex/dashboard vhost is never fronted by Authelia after the move (no
  lock-out), and logout redirects correctly.

## Migration decisions (resolved)

- **Q6 — host data path → RESOLVED: RENAME to `data/authelia/`.** Clean slate; the
  "identity" concept is gone, so the data path drops `identity/` too. Repoint
  `AutheliaService.usersDbPath()` + audit string, `UserChangedEvent` doc,
  `render.sh render_identity_seed` dst, and update `AutheliaServiceTests` +
  `UsersPropagationChainTests` assertions to `data/authelia/users_database.yml`.
  Compose volume becomes `../../data/authelia:/data`.
- **Q7 — `POST /sso` endpoint → RESOLVED: DELETE.** SSO is always-on core; the
  enable/disable toggle is meaningless. Remove the endpoint, delete
  `OnboardingControllerSsoTests`, and update `OpenApiConformance`/OpenAPI spec.
  Per-app protect stays manifest-driven (`sso.protect` → `CaddySnippetService`),
  not an onboarding call.
