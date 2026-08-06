# Dashboard competitive analysis

A look at fourteen self-hosted homelab tools, what each does well, and what that means for Aurora. Aurora's premise is deliberately narrow: one opinionated path from a fresh Debian/Ubuntu box to a running, secured home server, with a fixed catalogue of packages rather than a general app store. That framing decides which competitor features are worth copying and which aren't — a Kubernetes app catalogue is not a gap for Aurora, it's a different product.

## What Aurora already has

For context, so the gaps below read as gaps and not as ignorance of the existing build:

- A guided onboarding wizard (domain, packages, DNS, TLS, admin account, review) — few competitors go this deep.
- A fixed, dependency-aware package catalogue (`depends_on` / `recommends` in each `manifest.yml`) rather than an open app store — avoids the "installed twelve things, three of them conflict" problem.
- Live per-service status over SSE, container logs, host metrics, and a dedicated security posture view — no other tool on this list has a standing security-findings panel.
- Caddy with auto-reload on snippet change, plus mDNS aliases published per package automatically.
- A backup package (Kopia) and a monitoring package (Prometheus, Grafana, Uptime-Kuma) already in the default catalogue, not left as an exercise for the user.
- Updates exist (`scripts/update.sh` / `scripts/pin.sh`) but are CLI/cron-only — nothing about this surfaces in the dashboard yet. That gap is the single biggest theme below.

## Competitor scan

For each tool: one or two things it does well that Aurora currently lacks.

| Tool | Does well | Aurora gap |
|---|---|---|
| **Umbrel** | Polished dashboard with live storage/memory/temperature widgets; app store flags what permissions/dependencies an app needs before install. | No update-available signal anywhere in the UI; no pre-install disclosure of what a package will touch (ports, volumes, other packages) beyond the manifest description text. |
| **CasaOS** | Built-in file manager (browse, upload, share links) reachable from the dashboard itself. | No file browser at all — Samba covers LAN access but there's no in-browser file view for e.g. checking a package's config before editing it. |
| **Cosmos Cloud** | "SmartShield": per-route rate limiting, geo-blocking and bot detection sitting in front of every exposed service, on by default. UI-driven reverse-proxy config (pick a container from a list, subdomain auto-fills). | Aurora's Caddy setup is config-as-code (a real strength — it's git-diffable), but there's no active edge protection layer and no UI shortcut for "expose this container at this subdomain" without hand-editing a `caddy.snippet`. |
| **Runtipi** | One-click update per app from the dashboard; custom/community app stores for power users who outgrow the official catalogue. | No update UI (see above); no supported way for an advanced user to add a stack outside the curated catalogue without editing the repo directly. |
| **YunoHost** | True SSO portal users see and click through, not just an auth gate in front of each app; scheduled backups baked into the base OS. | Authelia SSO is in progress (Phase D), which covers the gate — but there's no user-facing portal listing "what you have access to", and backup is a package the user has to reach for, not something scheduled and status-checked by default. |
| **CapRover** | One-click HTTPS toggle per app; horizontal scaling and zero-downtime redeploys. | Out of scope for Aurora — single-box home server, not a PaaS cluster. No action needed. |
| **Portainer** | RBAC and OAuth-scoped access to specific containers; multi-host/Swarm/Kubernetes management. | Multi-host is out of scope. RBAC is relevant once Authelia identity work lands — worth revisiting then, not now. |
| **Dockge** | Edits the real `docker-compose.yml` on disk, no hidden database state, live deploy log streamed to the UI as it happens. | Aurora already keeps compose files as the source of truth (good — same philosophy), but there's no live "deploying..." log stream when a package starts; the SSE status only reports steady-state up/down. |
| **Homepage** | Huge (100+) service-widget library; docker-label auto-discovery of services. | Not a gap by design — the dashboard manifest explicitly delegates the tile-grid/widget job to the `core` package's Homepage instance. Aurora's own admin UI should not try to re-build this. |
| **Homarr** | Drag-and-drop dashboard building with zero YAML; 40+ live integrations (VPN status, DNS-hole control, media library stats). | Same as Homepage — intentionally out of scope for Aurora's *own* UI. Worth checking Aurora ships a good `homepage.yml` fragment per package so the delegated tile grid stays rich (most packages already do). |
| **Heimdall** | Near-zero setup time for a clean launcher tile grid. | Not applicable — delegated to Homepage, as above. |
| **Dashy** | Encrypted config backup/restore, so a dashboard's whole layout survives a reinstall. | Aurora's equivalent would be onboarding state/settings backup — currently there's a `StateFileService` but no export/import of dashboard settings shown to the user. |
| **Cockpit** | Systemd timer editor and firewall/network config from the browser, zero idle resource cost. | Aurora doesn't expose host-level systemd/firewall config at all — deliberately, most likely, since packages own their own networking. Low priority; flagging for completeness only. |
| **TrueNAS SCALE** | Per-app resource limits (CPU/RAM caps) set at install time, not just usage graphs after the fact; official curated catalogue *and* third-party catalogues side by side. | No resource capping anywhere in Aurora — a runaway container can eat the box. Also reinforces the "advanced: add your own stack" gap already flagged under Runtipi. |

## Prioritised improvements

Ordered by impact to an opinionated single-box home server, with rough effort. Everything here is scoped to be a real ticket, not a theme.

### High impact

1. **Update visibility and one-click update per package.** Compare each running image's digest against its registry tag (or just track "known latest tag pinned in this package's `compose.yml`" if a registry check is too heavy) and show an "update available" badge on `PackagesList.vue` / `PackageDetail.vue`. A button triggers `scripts/update.sh <package>` server-side and streams the result. This is the most commonly praised feature across Umbrel, CasaOS, Runtipi and TrueNAS, and it's the biggest visible gap between what the CLI can already do (`update.sh`, `pin.sh`) and what the dashboard shows. Effort: **M**.

2. **Active edge protection for anything exposed past the LAN.** Rate limiting, geo-blocking and basic bot detection sitting in Caddy in front of exposed vhosts, togglable per package from `SecurityPosture.vue` (default on for anything with a public DNS record). Cosmos Cloud's SmartShield is the standout example — no other tool on the list does this, and it is squarely in Aurora's stated lane ("sane security posture out of the box"). Effort: **L**.

3. **Backup health surfaced in the dashboard, not just in Kopia's own UI.** Pull last-run status, size and restore-point count from Kopia's API and show it on `DashboardHome.vue`, plus add a `SecurityFindingsService` check for "no successful backup in N days." The backup package already exists — this is about making its state visible without a second login. Effort: **S/M**.

4. **Notification channel for events that matter.** A webhook target (ntfy, ntfy self-hosted, Discord, or a generic webhook URL) configured once in `SettingsView.vue`, fired on: security finding raised, backup failure, service down (already detected by `StatusProbeService`), update available. Every polished competitor (Umbrel, Runtipi, Homarr) has some version of this; Aurora currently detects all of these things and tells no one. Effort: **M**.

### Medium impact

5. **Per-container resource limits at install/enable time**, not just usage graphs afterwards. TrueNAS SCALE sets this expectation; a single misbehaving container on a home box with no swap can otherwise take the whole host down. Add optional `mem_limit` / `cpus` fields to the package manifest schema, default them sensibly per package, expose an override in `PackageDetail.vue`. Effort: **M**.

6. **"Advanced: add a custom stack" flow**, clearly gated behind a warning, for users who outgrow the fixed catalogue (CasaOS's Compose importer, Runtipi's custom app stores, TrueNAS's custom-app wizard). Keeps the guided path the default and the only *recommended* one, while giving power users somewhere to go that isn't hand-editing the repo. Effort: **M/L**.

7. **In-UI reverse-proxy route editor** for exposing an arbitrary container at a subdomain — pick a container from a list, pick a subdomain, Aurora writes the `caddy.snippet` fragment and Caddy's existing `--watch` reload picks it up. Keeps the file as the source of truth (matches Dockge's philosophy, which is worth keeping) while removing the need to hand-edit YAML for the common case. Effort: **M**.

8. **Live deploy log during package start**, not just steady-state up/down over SSE. Dockge streams the `docker compose up` output as it happens; Aurora's current SSE status only tells you the end state, which makes a slow first pull (e.g. Immich, Ollama) look stalled. Effort: **S/M**.

### Low impact

9. **A one-line "needs attention" summary strip on `DashboardHome.vue`**: pending updates, open security findings, backup health, disk headroom. This is a better fit for Aurora's own admin home than trying to rebuild Homepage's widget system (which the `core` package already delegates to Homepage on purpose — don't duplicate that). Effort: **S**.

10. **Optional lightweight file-manager package** (e.g. FileBrowser) added to the catalogue for ad hoc file browsing, following the existing package contract. Samba already covers LAN file access; this covers the "just let me look at a file from the browser" case CasaOS handles natively. Effort: **S**.

11. **Settings export/import** for onboarding/dashboard state (`StateFileService` already holds it), so a reinstall doesn't mean redoing the wizard from scratch. Dashy's encrypted config backup is the model; Aurora's version doesn't need encryption since it's local-only, just an export button. Effort: **S**.

## Explicitly out of scope

Multi-host/Swarm/Kubernetes management (Portainer, TrueNAS's Helm layer), PaaS-style git-push deploys and horizontal scaling (CapRover), and a general third-party app store replacing the curated catalogue (Umbrel, CasaOS, Runtipi at full extent). All three run against Aurora's actual premise — one box, one opinionated path, a fixed and vetted set of packages. Copying them would make Aurora a worse version of tools that already do that job well.

## Already in flight

Two items above overlap with work already tracked elsewhere and are not being re-proposed here, just cross-referenced:

- Authelia SSO + RBAC (git history, "Phase D task spec + handover doc") covers the YunoHost-style auth gate; a user-facing SSO portal page and RBAC scoping (Portainer-style) are natural follow-ons once that lands.
- A VPN configuration page (WireGuard default) is already an open item and covers part of the "secure remote access" ground that Umbrel, Cosmos and CasaOS all bundle.
