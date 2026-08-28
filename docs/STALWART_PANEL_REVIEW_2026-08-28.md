# Stalwart mail-server panel — review (2026-08-28)

Reviewer: Product Manager, dual lens (UX + mail-server ops).
Page: `/apps/core/services/stalwart`
Frontend: `packages/dashboard/frontend/src/views/CoreServiceDetail.vue` (the `isStalwart` sections)
Backend: `StalwartController.java`, `StalwartMailClient.java` (JMAP), `StalwartProvisionService.java`
API client: `packages/dashboard/frontend/src/api/stalwart.ts`

---

## Executive summary

The panel does three things well — reveal/rotate the recovery-admin password, and create a mailbox with a one-time password — but it is **write-only**: you can mint mailboxes and never see them again. There is no list, so the admin has no idea how many accounts exist, whether the one they just created is still there, or any way to reset/disable/delete an account short of driving into Stalwart's own console. That is the headline gap and Bruce is right to call it: **a mailbox list is the single highest-value addition.** Secondarily, the page hides the two facts a homelab mail admin reaches for first — the mail domain's send/receive health (DNS/MX/SPF) and the client-connection details (hostname + ports for SMTP/IMAP) — while spending its whole header on a CTA ("Open mail admin") that dumps the user into the very raw console this dashboard is supposed to replace.

---

## Findings

Ranked within each severity by user impact.

### Blockers

**F1. [MISSING] [blocker] No mailbox list — the panel is write-only.**
`CoreServiceDetail.vue` (mailbox panel, ~L767–866) can *create* a mailbox but nothing anywhere renders existing mailboxes. Consequences for the admin:
- After the one-time password Alert is dismissed (`dismissMailboxResult`, L334), there is **zero evidence the mailbox exists**. No list to confirm against.
- No way to answer "did I already make a `sarah@` account?" — so the create form's 409 path (`A mailbox "…" already exists. Pick a different name.`, L312) becomes the *only* way to discover an existing account. That is discovery-by-collision.
- No reset-password, no disable, no delete anywhere in the dashboard. The only path to those is the raw Stalwart console — which is exactly the "shell in / raw admin UI" outcome the backend Javadoc says this surface exists to prevent (`StalwartController` class comment: "operators end up shelling in… which defeats the point of having a dashboard").

This is a blocker, not friction: managing mail without being able to see the accounts is not a degraded experience, it's a missing product. Backend has `createMailbox` but **no list endpoint** — spec'd in full in the Build order below.

**F2. [MISSING] [blocker] No send/receive health — the admin can't tell if mail actually works.**
The header sells "`send and receive mail from day one`" (`core-services.ts` L130) and the container pill says `RUNNING` — but on a real box, `RUNNING` and "mail actually flows" are different things. Whether inbound mail arrives depends on MX/A DNS records pointing at this box and port 25 being reachable; whether outbound isn't spam-foldered depends on SPF/DKIM/DMARC. **None of that is surfaced.** A homelab admin's first question after "it's running" is "is it receiving?" and the panel has no answer. At minimum the panel should show the mail domain, its verified/receiving state, and DNS records the user still needs to set. Ranked blocker because a mail server that silently doesn't receive is the #1 homelab mail failure and this panel actively implies success.

### Friction

**F3. [REDUNDANCY] [friction] The whole header is a door into the tool Aurora is replacing.**
The hero's only action is **"Open mail admin ↗"** (`core-services.ts` L138–139) → `https://mail-admin.{domain}/`, the raw Stalwart console. Every capability this panel *should* own (list, reset, disable, DNS status) currently lives only on the far side of that link. As we add native panels, that CTA should demote from "the primary thing to do here" to a labelled escape hatch ("Advanced: open Stalwart console") so the dashboard is the default and the raw console is the exception — not the reverse. Nit-adjacent today, but it will actively fight the new list view for primacy if left as the hero CTA.

**F4. [MISSING] [friction] No client-connection details (hostname + ports).**
The description name-drops "`SMTP, IMAP, JMAP`" (`core-services.ts` L130) but never tells the admin *how to connect a mail client*: what hostname, which ports (587 submission / 993 IMAPS / 465 SMTPS), STARTTLS vs implicit TLS. A homelab admin setting up Thunderbird or their phone needs exactly this and currently has to go dig in `packages/core/compose.yml`. A small static "Connect a mail client" facts block removes a guaranteed support question. Pragmatic and self-contained — the ports are fixed by the compose file, not dynamic.

**F5. [MISSING] [friction] No domains surfaced, though the backend already knows them.**
`StalwartMailClient` has `listDomainIds()` and `domainIdFor()` and `StalwartProvisionService.mailDomain()`, but the UI only ever interpolates `system.info.domain` into the `@{{ mailDomain }}` suffix (L284). The admin never sees the *actual provisioned* domain list from Stalwart — so if provisioning silently failed, the create form still cheerfully shows `@aurora.local` and the create call 502s/409s with no upstream explanation. Show the real domain(s) Stalwart holds and their state.

### Polish

**F6. [NICE-TO-HAVE] [polish] Mail queue / deliverability at a glance.**
A tiny "Outbound queue: 0 waiting" line (and a nudge if items are stuck) is the classic mail-ops reassurance signal. Genuinely useful, but single-box homelab volume is low — polish, not friction. Defer until F1/F2 land.

**F7. [NICE-TO-HAVE] [polish] Per-mailbox quota/usage.**
Nice column in the list once it exists (see Build order). On a single box with plenty of disk it's rarely load-bearing, so treat as an enhancement to the list, not a blocker for shipping it.

**F8. [REDUNDANCY] [polish] Two adjacent one-time-password flows with near-identical copy.**
The recovery-admin reveal and the mailbox create both render a `font-mono` secret + Copy button + "shown once" framing. Not wrong, but as the page grows they'll read as repetitive. When the list ships, fold "reset password" into the row action so there's one mental model ("secrets show once, copy now"), not three scattered instances. Pure polish.

**F9. [NICE-TO-HAVE] [polish] `Up 3 hours (healthy)` is raw Docker status.**
The Status facts field (`container.status`, L582) shows Docker's own string. Fine for now; a friendlier "Healthy · up 3h" is polish-tier and shared with every core service, so not Stalwart-specific.

---

## Build order for the implementer

Do these in order. Each is self-contained; do not batch F2/F3 work into the F1 PR.

### 1. Mailbox list view — **the headline feature** (backend + frontend)

**Definition of done:** an admin loading `/apps/core/services/stalwart` sees a table of every mailbox on the box's domain, with per-row reset-password / disable / delete, and correct empty / loading / error states — no navigation to the raw console required for day-to-day account management.

#### 1a. Backend — new list endpoint

**File:** `StalwartMailClient.java` — add `listMailboxes()`.
JMAP has no single "list accounts with detail" call, so do the standard two-step (query → get), reusing the existing `jmapCall` / `post` / `methodArgs` seam:

1. `x:Account/query` with a filter for user-type accounts (mirror the `x:Domain/query` shape already in `listDomainIds()`), returning ids.
2. `x:Account/get` with those ids, requesting properties: `id`, `name` (local part), `description`, `emails`/aliases if present, `quota`, `usedQuota` (if the server exposes it), and the enabled/disabled flag Stalwart carries on the principal.

> Note for implementer: the exact property names for quota/used/enabled must be verified against the live v0.16.19 the same way `createMailbox`'s `credentials` map shape was ("verified against a live v0.16.19" — `StalwartMailClient` class comment). Request only properties you've confirmed the server returns; **omit a column rather than render `undefined`.** `domainIdFor()` already gives you the domain id to scope the query.

Return a list of a new record, e.g.:
```
MailboxSummary(
  String id,
  String address,      // name@domain, assembled like createMailbox does
  boolean enabled,
  Long quotaBytes,     // nullable — omit column if null
  Long usedBytes,      // nullable
  String createdAt     // nullable if server doesn't expose it
)
```

**File:** `StalwartController.java` — add `GET /api/services/stalwart/mailboxes`.
- `requireAdmin()` (same gate as every other method here).
- Returns `List<MailboxSummary>` (200) — empty list is a valid 200, **not** a 404.
- On `StalwartApiException` unreachable/JMAP-fail, map to **502** with `"the mail server is not reachable right now"` — reuse the exact string already used in `createMailbox`'s 502 branch so the frontend copy stays consistent.

**File:** `stalwart.ts` — add `StalwartApi.listMailboxes(): Promise<MailboxSummary[]>` and export the `MailboxSummary` interface mirroring the backend record. `{ toast: false }` (inline error surface, same as the other calls).

#### 1b. Frontend — the list panel

**File:** `CoreServiceDetail.vue`, new `v-if="isStalwart"` panel placed **above** "Create a mailbox" (you manage the many, then add one). Heading: **"Mailboxes"**.

**Columns (in this order):**
| Column | Source | Notes |
|---|---|---|
| Address | `address` | `font-mono`, `select-all` (matches existing secret styling) |
| Status | `enabled` | pill: `Enabled` (running-tone) / `Disabled` (muted). Follows principle 2 (status always visible). |
| Usage | `usedBytes` / `quotaBytes` | e.g. `12 MB / 1 GB`, or `12 MB` if no quota. **Hide the whole column** if the server returns neither. |
| Created | `createdAt` | via existing `formatDate()` (already in this file, L~485). Hide if null. |
| Actions | — | right-aligned button group, see below |

**Row actions (each admin-only, each with a confirm for destructive ones):**
- **Reset password** → new `POST /api/services/stalwart/mailboxes/{id}/reset-password`; returns a one-time password reusing the exact `MailboxCreated`-style one-time reveal UI already on the page (do not invent a second reveal pattern — see F8).
- **Disable / Enable** → new `PUT /api/services/stalwart/mailboxes/{id}` toggling the principal's enabled flag. Optimistic-off is fine; re-fetch list on success.
- **Delete** → new `DELETE /api/services/stalwart/mailboxes/{id}`. **Confirm dialog required**, naming the address, with copy that spells out consequence: "Deleting `sarah@aurora.local` permanently removes the mailbox and all its mail. This cannot be undone." Re-fetch on success.

> These three write endpoints are additional backend work (`x:Account/set update` for disable, `x:Account/set destroy` for delete, `x:Account/set update` with a new `credentials` map for reset). If scope needs trimming for a first PR: **ship read-only list + reset-password first** (reset reuses the existing generate-password machinery), then disable/delete in a fast-follow. Do **not** ship the list without at least reset-password, or you've built a read-only table next to a create form and left "I need to change a password" still routing to the raw console.

**Required states (principle 5, tangible progress):**
- **Loading:** `Skeleton` rows (the file already imports `Skeleton`; match the facts-card skeleton pattern at L563).
- **Empty:** not a bare "No mailboxes." Copy: **"No mailboxes yet. Create the first one below."** with the eye pointed at the create panel. This is the fresh-box state and should feel like a next-action, not an error.
- **Error (502 / unreachable):** `Alert variant="destructive"` with the shared string **"The mail server is not reachable right now. Try again in a moment."** plus a **Retry** button that re-calls `listMailboxes()` (principle 4 — errors are actionable, offer retry). Do not silently render an empty table on error — that would read as "0 mailboxes" and is a lie.

**Data-test hooks:** `stalwart-mailbox-list`, `stalwart-mailbox-row`, `stalwart-mailbox-row-address`, `stalwart-mailbox-reset`, `stalwart-mailbox-disable`, `stalwart-mailbox-delete`, `stalwart-mailbox-list-empty`, `stalwart-mailbox-list-error`, `stalwart-mailbox-list-loading` — match the existing `stalwart-*` naming.

**Refresh contract:** after a successful create (`createMailbox`, L295) or any row action, re-fetch the list so the table is the single source of truth. This also fixes F1's discovery-by-collision: the admin sees the account instead of guessing.

---

### 2. Send/receive health strip (F2) — backend + frontend

**Definition of done:** the panel shows, near the top, the mail domain and whether the box can actually receive/send, plus any DNS records still needed — no green "RUNNING" without a reality check.

- **File:** `StalwartMailClient.java` / a small new service — surface: provisioned domain(s) (already have `listDomainIds`/`domainIdFor`), and a best-effort DNS check (MX/A pointing at the box, SPF TXT present). Keep it best-effort and fail-soft — a failed lookup shows "couldn't check", never blocks the page.
- **File:** `StalwartController.java` — `GET /api/services/stalwart/health` → `{ domain, receiving: bool|unknown, dnsRecords: [{type, name, expected, ok}] }`.
- **File:** `CoreServiceDetail.vue` — a facts/status block under the header: domain, a receiving pill, and a collapsible **"DNS to set"** list (principle 6 — advanced detail behind a disclosure). Copy for the not-yet-receiving case must be actionable: which record, where, what value.

Ship **after** the list. Do not fold into PR 1.

---

### 3. "Connect a mail client" facts block (F4) — frontend only

**Definition of done:** an admin can configure Thunderbird/phone without leaving the page or opening a compose file.

- **File:** `CoreServiceDetail.vue` — static facts block (values are fixed by `packages/core/compose.yml`, no new endpoint needed): hostname (`mail.{domain}` or the box domain), IMAP `993 (SSL/TLS)`, SMTP submission `587 (STARTTLS)`, and JMAP URL if exposed. `font-mono`, copy-able. Behind an "Advanced" disclosure is fine (principle 6).
- Verify the exact ports against `packages/core/compose.yml` before hardcoding — do not guess.

---

### 4. Demote the raw-console CTA (F3) — frontend/config only

**Definition of done:** the native panels are the default; the raw Stalwart console is a clearly-labelled escape hatch, not the hero action.

- **File:** `core-services.ts` L138–139 and/or `CoreServiceDetail.vue` header — once the list ships, relabel/relocate "Open mail admin" so it reads as advanced ("Open Stalwart console ↗", secondary styling, near the facts or in an Advanced area), not the primary hero CTA. Small change; do it in the same PR as the list so the two don't compete for the user's eye on day one.

---

## Explicitly out of scope for now
- Aliases / catch-all / distribution lists — real Stalwart features, but past the "simple list view" Bruce asked for. Revisit after the CRUD list lands.
- DKIM key management UI — deliverability matters but key rotation is a sharp edge; leave in the raw console for now and just *report* DKIM presence in the health strip (PR 2).
- Full queue management UI (F6) — report a count at most; don't build queue admin.

---

## Implementation status (2026-08-28, same day)

Actioned immediately after the review. Shipped:

- **#1 Mailbox list — DONE + verified live.** `GET /api/services/stalwart/mailboxes` (JMAP `x:Account/get`; real property names `emailAddress`/`createdAt`/`usedDiskQuota`/`quotas` verified against live v0.16.19), plus **reset-password** (`POST .../{id}/reset-password`) and **delete** (`DELETE .../{id}`). Frontend: a Mailboxes table above Create, columns Address / Usage / Created / Actions (Usage + Created auto-hide when the server returns null — the "omit rather than render undefined" rule), row actions Reset-password (reuses the one-time reveal) and Delete (confirm dialog naming the address + irreversible copy), and loading / empty ("No mailboxes yet. Create the first one below.") / error+retry states. Refreshes after create + delete. **Disable/enable was intentionally NOT built** — v0.16.19's principal object carries no enabled flag, and the review's own rule was to omit unconfirmed fields rather than invent them.
- **#3 CTA demoted — DONE.** Header now reads "Open Stalwart console ↗"; the raw console is also linked as a secondary escape hatch from the new facts block.
- **#4 Connect-a-mail-client facts block — DONE.** Static block: server `mail.<domain>`, IMAP 993 (SSL/TLS), SMTP 587 (STARTTLS), plus the secondary ports. Ports verified against packages/core/compose.yml.

Verified live end-to-end in the browser: the list rendered `terry@aurora.local`; created + deleted `deleteme@aurora.local` through the row action + confirm dialog and watched the table refresh. 974 backend + 641 frontend tests green.

**Deferred (as the review advised):** #2 send/receive health strip (needs a DNS-check backend, its own PR), #5 real-domain surfacing, and the polish tier (queue count, per-mailbox quota once meaningful, status-string friendliness). These are the honest next PRs, not part of "the simple list view Bruce asked for".
