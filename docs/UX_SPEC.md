# Aurora UX Spec — Sarah persona

**Audience:** worker agents implementing v0.3 onboarding + first-run dashboard, and
QA writing Playwright coverage.
**Scope:** the wizard (`OnboardingWelcome → OnboardingAdmin → OnboardingDomain →
OnboardingPackages → OnboardingSecrets → OnboardingDns → OnboardingTls →
OnboardingReview → OnboardingDone`) and the first-load `DashboardHome`.
**Source of truth for behaviour:** `docs/ONBOARDING_V0.2.md` (API + store
architecture), `docs/DASHBOARD_BRIEF.md` (product principles), this file (UX
acceptance).

Every criterion below is written so a Playwright test can implement it directly
against `/onboarding/*` routes.

---

## 1. Persona: Sarah

Sarah is a paediatric nurse in her mid-thirties. She has a spare mini PC on a
shelf next to the router. She wants three things: a Netflix alternative her
partner and kids can use, AdGuard blocking ads on every device in the house,
and photos backing up automatically from both phones. She has never opened a
terminal. She has a password manager because her hospital forces one on her.
She bought a Synology last year and returned it inside the two-week window
because the setup screen asked her what a "storage pool" was and she closed
the browser tab and never came back.

Sarah judges Aurora in the first thirty minutes. The moment Aurora tells her
to type a command, or SSH somewhere, or edit a file, or "consult your
router's documentation", she uninstalls. She will not Google. She will not
read a wiki. If Aurora does not feel like Sonos — plug it in, tap through
setup, it works — she deletes it and posts one line on a subreddit. If Aurora
feels like Sonos, she recommends it to two friends before Christmas.

---

## 2. The 30-minute journey

Timeline from unboxing to daily use. Times are targets, not maxima. Any step
that runs long without an on-screen acknowledgement is a P0 dead-air defect.

| T+     | Location            | Action                                                                                                            | Feels like    |
|--------|---------------------|-------------------------------------------------------------------------------------------------------------------|---------------|
| 0:00   | Living room         | Plug mini PC into power + ethernet. Ubuntu preinstalled by seller / partner / any prior instruction sheet.        | Setup a router |
| 2:00   | Laptop, Safari      | Types `aurora.local` in address bar. Landing page loads within 2s. Big "Set up Aurora" button.                    | Sonos app     |
| 3:00   | `/onboarding/welcome` | Sees hostname, LAN IP, CPU/RAM/disk cards. Clicks Continue.                                                       | Confidence    |
| 4:30   | `/onboarding/admin` | Username prefilled `aurora`, password prefilled (24 chars). One click to copy to password manager, tick "Saved", Continue. | Password manager flow |
| 6:00   | `/onboarding/domain` | Default `aurora.local` accepted. Continue.                                                                       | Trust default |
| 7:00   | `/onboarding/packages` | Clicks "Media server" preset. Warnings surface live (none for her box). Toggles `photos` on. Continue.          | App store     |
| 10:00  | `/onboarding/secrets` | Sees "Aurora generated 14 secrets for you." Continue.                                                            | Magic         |
| 11:00  | `/onboarding/dns`   | Default "AdGuard on this box" tab. Reads two sentences. Continue.                                                | Recommended   |
| 12:00  | `/onboarding/tls`   | Downloads root CA, follows animated macOS instructions with screenshots. Or clicks "Skip, I'll do this later."   | Small chore   |
| 14:00  | `/onboarding/review` | Sees plan diff: 6 packages, 12 vhosts, 0 host ports opened. Clicks Install. Watches live log for ~90 s.         | Progress bar  |
| 16:00  | `/onboarding/done`  | Every enabled package shows a status pill. AdGuard, media, photos each have a "Finish setup" button.             | Checklist     |
| 17:00  | AdGuard first-run   | Opens in new tab from Aurora. Sets AdGuard admin password (Aurora reminds her to save it).                       | Two-step      |
| 22:00  | `/` (Aurora home)   | Returns. Sees dashboard with running-status pills, "3 packages need first-run setup" banner.                    | Home          |
| 27:00  | Phone               | Installs Immich app. Points at `photos.aurora.local`. Signs in. Turns on background backup.                     | Google Photos |
| 30:00  | Living room         | Opens Jellyfin on the TV via existing Jellyfin app. Signs in with the admin account Aurora printed for her.     | Netflix       |

**Non-goals for the 30-minute window.** Sarah does not configure Prowlarr
indexers, does not set up VPN provider credentials, does not touch backup
retention policy, does not add second admin. Aurora ships opinionated
defaults for all of these; she can revisit later or never.

---

## 3. Screen-by-screen acceptance criteria

Every check below is Playwright-implementable. Where a criterion references
"shell text", the concrete regex is
`/\b(sudo|ssh|scp|cd\s+~|\.\/scripts\/|docker\s+compose|apt(-get)?|systemctl|curl\s+\|)\b/`.

### 3.1 Global (applies to every wizard step)

- **G1** Step header text matches `/^Step \d of 9$/`.
- **G2** `document.querySelectorAll('pre').length === 0` on every wizard route.
  (No `<pre>` blocks anywhere in the wizard. Live install log on `/review`
  is the sole exception and lives in a `<div role="log">`, not a `<pre>`.)
- **G3** No `<code>` element on any wizard route has `textContent` matching
  the shell-text regex above.
- **G4** No visible text in the wizard contains the substrings `"SSH"`,
  `"ssh"`, `"terminal"`, `"command line"`, `"shell"`, or `"CLI"` outside a
  documentation link explicitly labelled "For advanced users".
- **G5** Every step has exactly one primary CTA (`button[data-cta="primary"]`)
  and it is the rightmost focusable element in the step footer.
- **G6** Every step except Welcome has a Back button that returns to the
  previous step within 300 ms and preserves entered values.
- **G7** After any button click that triggers a network request, either
  (a) the button enters a loading state with `aria-busy="true"` within
  100 ms, or (b) the resulting view renders within 300 ms. Never both silent
  and >300 ms.
- **G8** The sidebar step list marks each completed step with
  `data-state="complete"` and the current step with `data-state="active"`.
- **G9** Every wizard route survives a hard refresh: after `page.reload()`
  the URL is unchanged, previously entered fields are prefilled, and no
  network error alert is visible.

### 3.2 `/onboarding/welcome` (Step 1)

- **W1** Hostname, LAN IP, distribution, kernel, Docker version each render
  a value or the literal `—`. No `null`, no `undefined`, no `[object Object]`.
- **W2** CPU / RAM / Disks cards each render with real values within 2 s of
  route entry, or the visible fallback `—`.
- **W3** If `env.distro` does not match `/debian|ubuntu/i`, a `tone="warn"`
  Alert is rendered with the exact heading `"Untested distribution"`.
- **W4** Continue is enabled unconditionally (welcome is read-only).
- **W5** Clicking Continue navigates to `/onboarding/admin` and issues no
  `PATCH` request (welcome has nothing to persist).
- **W6** No copy on this screen contains the word `"L2"`, `"L3"`, `"L4"`,
  `"Ansible"`, or `"YAML"`. Sarah does not know what those are.

### 3.3 `/onboarding/admin` (Step 2)

- **A1** Username input is prefilled with `"aurora"` (or the stored draft
  value if hydrating).
- **A2** Password input is prefilled with a generated value of length ≥ 20.
- **A3** A "Copy" button is within 200 px of the password input. Clicking it
  writes the password to the clipboard; the button label transitions to
  "Copied" within 300 ms.
- **A4** A checkbox with label matching `/saved this password/i` is present.
  Continue is disabled until it is ticked.
- **A5** A "Generate new" button regenerates the password and unchecks A4.
- **A6** The screen contains no text matching `/SSH|ssh|terminal|reset-admin/`.
  **[P0 — currently violated: two SSH mentions in OnboardingAdmin.vue,
  see §4.]**
- **A7** If `bootstrap_mode === false` on hydrate, the create-form is
  replaced by a read-only card that shows the saved username. The
  recovery hint must read "Recover this account from **Settings →
  Admin**" and must not contain the string `"SSH"`.
- **A8** Clicking Continue calls `POST /api/onboarding/admin` exactly once.
  On 200, navigates to `/onboarding/domain`. On 409, shows an inline error
  and stays on the page.

### 3.4 `/onboarding/domain` (Step 3)

- **D1** Input is prefilled from `store.domain` (default `"aurora.local"`).
- **D2** Invalid domain (`"not a domain"`) blocks Continue with an inline
  `tone="err"` Alert. No network request is issued.
- **D3** The helper strip lists at most 5 short bullets under "What changes
  if you edit this". Each bullet ≤ 80 characters.
- **D4** No bullet references a filename, a shell command, or the token
  `${DOMAIN}` in raw form. (Rendering it as a code chip is fine; instructing
  the user to touch it is not.)
- **D5** Continue calls `PATCH /api/onboarding` with body
  `{ domain, step: "packages" }` exactly once, then navigates.

### 3.5 `/onboarding/packages` (Step 4)

- **P1** Three preset buttons are present with labels exactly:
  `"Safe default"`, `"Media server"`, `"Personal cloud"`.
- **P2** The `core` package card is always selected and disabled. Attempting
  to click it does not toggle selection.
- **P3** After a selection change, `GET /api/onboarding/plan?enabled=…`
  fires within 300 ms (debounced 250 ms). The `checking…` indicator appears
  and disappears within 1500 ms on a healthy backend.
- **P4** Warning strings from the plan appear as `tone="warn"` Alerts under
  the grid. Each warning is a full sentence, ends with punctuation, and does
  not contain the substring `"ram_below_mb"` or any other rule-id token
  (rule ids are internal; renderers must map to prose).
- **P5** The Continue button label matches
  `/^Continue with \d+ packages?$/` and the number matches the checked-card
  count.
- **P6** If the user has not touched selection, the "Safe default" preset is
  applied on first render (empty selection is never a valid state past this
  step).
- **P7** Selecting the `ai` package on a CPU-only box surfaces at least one
  warning containing the substring `"GPU"` within 1500 ms.

### 3.6 `/onboarding/secrets` (Step 5)

- **S1** The screen names the count of secrets Aurora will generate, e.g.
  `"Aurora will generate 14 secrets for you."` The number matches the
  server's count from `GET /api/onboarding/plan`.
- **S2** The screen must not contain the substring `"visual stub"`,
  `"ships with M2"`, or `"landing in the next slice"`. Sarah does not care
  what milestone we are in. **[Currently violated: OnboardingSecrets.vue
  says "Landing in the next slice", see §4.]**
- **S3** An "Advanced" disclosure toggle reveals a per-package secrets
  editor. Casual users never see it. Playwright: the editor's root element
  has `hidden` until the toggle is clicked.
- **S4** Continue is always enabled — every secret is either auto-generated
  or already present.
- **S5** No text on this screen instructs the user to edit `.env` files,
  view them, or know they exist. `.env` is Aurora's implementation detail.

### 3.7 `/onboarding/dns` (Step 6)

- **N1** Three tabs render with values exactly matching the enum
  `adguard | router | mdns`. Default tab is `adguard` unless
  `store.dnsMode` says otherwise.
- **N2** The `adguard` tab body must not mention `dnsmasq`, `iptables`, or
  any router firmware name outside a plain-English sentence.
- **N3** The `router` tab shows brand-specific instructions for at minimum:
  `UniFi`, `pfSense`, `ASUS`, `Netgear`, `TP-Link`. Each brand has a
  screenshot or animated GIF of the exact settings page, not a text
  description. **[Currently under-served: OnboardingDns.vue says
  "consumer ASUS / Netgear: no — add each subdomain individually" with no
  instructions. Friction, see §4.]**
- **N4** The `mdns` tab clearly labels itself as advanced ("Advanced —
  laptops only, not for a household") and does not appear on mobile
  viewports narrower than 720 px.
- **N5** Continue calls `PATCH /api/onboarding` with body
  `{ dns_mode, step: "tls" }` and navigates on 200.

### 3.8 `/onboarding/tls` (Step 7)

- **T1** A "Download root CA" button is visible and downloads a file with
  the exact filename `caddy-root.crt`. Playwright: assert
  `download.suggestedFilename() === 'caddy-root.crt'`.
- **T2** Per-OS instructions are shown in tabs, not stacked. Default tab
  matches the `User-Agent` OS (`macOS`, `Windows`, `iOS`, `Android`,
  `Linux`).
- **T3** The macOS and Windows tabs contain no `<code>` element with shell
  text — they are click-by-click UI instructions with numbered steps.
- **T4** The Linux tab contains at most one `<code>` element and it is
  clearly framed as "on your laptop, not on the Aurora box". Copy button
  present. **[Currently a nit: `sudo cp caddy-root.crt …` shown to Linux
  clients without the "your laptop" framing.]**
- **T5** A "Skip for now — I'll do this later" secondary action is present.
  Clicking it navigates to `/onboarding/review` and marks TLS trust as
  pending in the store.
- **T6** Continue navigates to `/onboarding/review` and does not require
  the download to have completed.

### 3.9 `/onboarding/review` (Step 8)

- **R1** A summary table renders exactly six rows:
  `Domain, Admin, DNS, Packages, vhosts, Ports`. Any missing value renders
  as `—`, never `null`.
- **R2** Warnings from `GET /api/onboarding/plan` appear as `tone="warn"`
  Alerts above the Install button, one Alert per warning.
- **R3** The Install button is `variant="accent"` and its label is the
  single word `"Install"`.
- **R4** Clicking Install causes the following, in order:
  1. Within 100 ms, button becomes `aria-busy="true"` and shows a spinner.
  2. A live log region appears with role `"log"` and streams at minimum
     four lines: `Persisting draft…`, `Applying configuration…`, per-file
     apply lines, `Committing…`.
  3. Never more than 3 s of dead air between lines. If the backend is
     silent, Aurora emits a heartbeat line `…` on a 1 s timer.
  4. On success, navigates to `/onboarding/done` within 500 ms.
  5. On failure, an inline `tone="err"` Alert renders with the failure
     reason and a `"Retry"` button. Retry re-runs install without leaving
     the page.
- **R5** The log region is a `<div>`, not a `<pre>`. Font may be monospace;
  the tag matters for accessibility and for our "no `<pre>` in the wizard"
  rule (G2).

### 3.10 `/onboarding/done` (Step 9)

**This screen currently violates every principle we hold. See §4.**

- **X1** No `<pre>` element renders. No `<code>` element contains text
  matching the shell-text regex (G3). **[Currently violated: `<pre>` with
  `cd ~/aurora.local && ./scripts/up.sh`.]**
- **X2** No visible text contains `"SSH"`, `"ssh"`, `"scripts/up.sh"`,
  `"scripts/down.sh"`, `"host"`, `"operator"`, `"terminal"`.
- **X3** For every enabled package (per `store.installResult` or the store
  selection), a card renders with:
  - The package display name (not the raw name).
  - A status pill with `data-status` in
    `running | needs-config | failed | not-started | starting`.
  - A primary action button whose label is one of:
    `"Open"`, `"Finish setup"`, `"Retry"`, `"Waiting…"`.
  - No secondary text longer than 140 characters.
- **X4** If any package status is `not-started` or `failed`, a banner at the
  top of the page reads "Aurora is bringing your packages up." with a
  progress indicator. Aurora runs `up.sh`-equivalent work **on the user's
  behalf via the backend** — Sarah never sees a shell command.
- **X5** A single primary CTA at the bottom reads `"Go to my dashboard"` and
  navigates to `/`. No secondary CTA competes for attention.
- **X6** The AdGuard, media (Sonarr/Radarr/Seerr), photos (Immich), and
  storage (Samba) cards each contain a specific next-action button. The
  action is contextual:
  - `privacy` → "Finish AdGuard setup" opens AdGuard first-run in a new tab.
  - `photos` → "Get the Immich app" links to iOS/Android stores.
  - `media` → "Open Prowlarr" (indexer manager, the correct first stop).
  - `storage` → "How do I connect my laptop?" opens an in-app modal with
    Finder / Explorer instructions and screenshots, no shell.
- **X7** Copy button next to the admin username and password is present so
  Sarah can lodge credentials in her password manager one last time. (The
  password itself must have been shown ONLY once on `/onboarding/admin`;
  if the store no longer has it, the copy button hides.)

### 3.11 First-load dashboard (`/`)

The dashboard is the first thing Sarah sees after the wizard ends. See §5.

- **H1** Loads within 2 s on a mid-range mini PC with 15 running containers.
- **H2** Every enabled package renders as a tile with a status pill matching
  the enum `running | needs-config | failed | not-started`.
- **H3** A "Get started" checklist appears at the top of the page if any
  package is in `needs-config` or `failed` state. The checklist is dismissible
  but returns automatically if new items appear.
- **H4** No metric card, chart, or log widget appears above the checklist.
  Fixes always outrank telemetry on the first load.
- **H5** Every card has a one-click primary action: `Open`, `Finish setup`,
  or `Retry`. Never a card without an action.
- **H6** SSE events update the status pills within 2 s of a docker event
  without a page reload.

---

## 4. P0 defects in the current build

Ranked by user impact. **Every P0 is a "Sarah deletes Aurora" moment.**

### P0-1 — `OnboardingDone.vue` tells the user to SSH into the box

```
SSH into the box and run:
cd ~/aurora.local && ./scripts/up.sh
```

**Location:** `packages/dashboard/frontend/src/views/onboarding/OnboardingDone.vue`,
the "Action required on the host" card. Renders inside a `<pre>` block.

**Why it's P0.** Principle 1 says "No CLI, ever." This is the exact
violation. Sarah has completed the wizard, thinks she is done, hits Done,
and Aurora tells her the last step is a terminal command she cannot execute
and does not understand. She closes the tab. She uninstalls tomorrow.

**Fix outline (worker instruction — do NOT scope-creep into this spec):**
- Backend: `POST /api/onboarding/install` must actually bring packages up.
  Options:
  (a) ship the aurora image with `docker` client + docker.sock mount and
      shell to `docker compose -p aurora up -d` from Java, or
  (b) run `scripts/up.sh` via a privileged sidecar container Aurora already
      controls, or
  (c) drop a systemd unit at first boot (installed by `bootstrap.sh`) that
      watches `.state.yml` and runs `up.sh` on change.
  Recommend (a) — it's the shortest path and matches the brief's
  "docker socket access is via docker-java" pattern.
- Frontend: replace the `<pre>` card in `OnboardingDone.vue` with the
  per-package status pill + action grid described in §3.10 X3–X6.
  Backend feeds live status via SSE (`/api/events`), same channel the
  dashboard uses.

### P0-2 — `OnboardingDone.vue` tells the user to run `./scripts/down.sh`

```
… run ./scripts/down.sh <pkg> on the host to stop them.
```

**Location:** same file, `<Alert>` for `toStop.length > 0`.

**Why it's P0.** Same as P0-1. Deselecting a package should stop it. Aurora
is the operator.

**Fix outline.** Backend adds "packages to stop" to the install action.
Frontend surfaces this as a single sentence: "Aurora stopped these packages
you deselected: …" — past tense, no user action required.

### P0-3 — `OnboardingAdmin.vue` tells the user to SSH to reset admin

```
If you've lost it, SSH into the box and run aurora reset-admin.
```

**Location:** `OnboardingAdmin.vue`, the "existing admin" branch card.

**Why it's P0.** Sarah refreshes on this step (kids interrupt her). She
comes back, sees a card telling her that if she lost the password she needs
to SSH. She Googles "how to SSH into a mini PC". Twenty minutes later she
gives up.

**Fix outline.** Replace with an in-app "Reset admin password" flow gated
by a physical-presence proof (button press on the box's power button, or a
reset code printed to an attached HDMI display, or a one-time recovery code
Aurora showed her during step 2 and told her to save). The wizard copy
should read: "Recover this account from **Settings → Admin** using your
recovery code."

### P0-4 — `OnboardingAdmin.vue` sets the "SSH is the deal" tone in copy

```
If you lose the password, you SSH in and reset it — that's the deal.
```

**Location:** `OnboardingAdmin.vue`, `bootstrap_mode=true` branch, second
paragraph.

**Why it's P0.** Even if the actual recovery path is fixed, this line
tells Sarah in her first ninety seconds that Aurora considers SSH a normal
part of using the product. She has already returned a Synology for less.
This copy is Aurora branding itself as Linux. Delete on sight.

**Fix outline.** Replace with: "One account, one password. Save it in your
password manager. If you lose it, Aurora can print a recovery code you can
use from any device on your network."

---

## 5. Post-onboarding dashboard requirements

When Sarah lands on `/` for the first time after finishing the wizard, this
is what she must see. Ranked from top of the page to bottom.

### 5.1 The "Get started" checklist (top of page, always if pending)

- Renders as a full-width card above the bento grid.
- Title: `"Finish setting up your box"` — matches the wizard's tone.
- One row per pending action, ordered **blockers first, optional last**:
  1. `failed` packages (red pill, "Retry" button, one-line reason).
  2. `not-started` packages (grey pill, "Start" button, tap to start).
  3. `needs-config` packages that block usability (privacy/AdGuard,
     media/Prowlarr, photos/Immich admin creation) — orange pill, contextual
     button label ("Finish AdGuard setup", "Create Immich admin", etc.).
  4. `needs-config` polish items (TLS trust not confirmed, backup schedule
     not set) — grey pill, "Set up" button.
- Each row is a single clickable target (whole row, not just the button).
- Dismissed rows return automatically when the underlying condition
  re-appears (e.g. TLS trust drift after cert rotation).
- Empty state: card collapses to a one-line "You're set up. Nice." with a
  small green check.

### 5.2 Package tiles (below the checklist)

- Grid of tiles, one per enabled package, grouped by manifest `category`.
- Each tile shows:
  - Icon (from `selfh.st/icons` — see brief §18.4).
  - Package display name (not raw name).
  - Status pill: `running | needs-config | failed | not-started | starting`.
    Colour: green / orange / red / grey / blue-pulsing.
  - Primary action: `Open` (for running packages, deep-links to service URL)
    or the contextual "Finish setup" / "Retry" from the checklist.
  - Secondary action (overflow menu): Restart, View logs, Config, Remove.
- Status pills update within 2 s of the underlying docker event via SSE.

### 5.3 System summary (small, below the tiles)

- Memory / disk / container-count bar. One line. No graphs above the fold.
- "Everything looks healthy" plain-English summary. If a threshold is
  exceeded, say what and what to do — never just a red number.
- Link: "See detailed metrics →" (navigates to `/metrics`, out of scope
  for v0.3).

### 5.4 Explicit non-requirements for the first-load dashboard

- **No CPU/mem/disk chart above the fold.** Sarah is not an SRE. Charts
  live on `/metrics` behind a link.
- **No docker events log widget.** It exists on `/health`, not here.
- **No "Reconcile" button on first load.** Reconciliation should be
  automatic; the button only appears if reconciliation fails.
- **No security score above the fold.** Security posture lives on
  `/security`; only failed findings raise a checklist row.

---

## 6. Anti-patterns explicitly banned

Numbered so worker agents can cite them in PR review comments.

1. **Ask the user to open a terminal.** Any string containing `ssh`, `sudo`,
   `terminal`, `command line`, `shell`, `CLI`, or `PowerShell` in an
   instruction (as opposed to a documentation aside) is a P0. Exemplar:
   OnboardingDone.vue's `<pre>` block.
2. **Ship a `<pre>` block containing shell text** to a non-technical user.
   `<pre>` is a code smell — literally. If it renders shell in the wizard,
   the wizard is broken.
3. **Show a raw filename or path as the primary instruction.** Sarah does
   not care that `.state.yml` exists. Aurora edits YAML on her behalf.
4. **Say "consult your router's documentation".** If Aurora needs the user
   to do something on the router, Aurora ships the instructions with
   screenshots per brand. See §3.7 N3.
5. **Return "Failed" without a reason and a Retry button.** Every failure
   state renders (a) what happened in plain English, (b) what to do next,
   (c) a Retry button. `installErr.value = "Install failed"` is banned.
6. **Silence longer than 3 s during a background action.** Emit a heartbeat
   line, a shimmer, a progress dot — anything. Dead air makes Sarah
   refresh, and refresh mid-install is our worst-case race.
7. **Batch a friction with a blocker on the same screen.** If TLS trust is
   optional and photos-backup is blocking, do not put them under one
   "next steps" heading. Blockers get their own visual weight.
8. **Use jargon in the primary flow.** Banned tokens in casual copy:
   `vhost`, `compose`, `docker`, `container`, `bind mount`, `network
   namespace`, `mDNS`, `RFC1918`, `TLS SNI`, `reverse proxy`. Move any of
   these behind an "Advanced" disclosure.
9. **Reveal a milestone name to the user.** `"Landing in the next slice"`,
   `"ships with M2"`, `"v0.2"`, `"v0.3"`, `"coming soon"` all leak our
   internal roadmap. Sarah paid attention for the wizard, not the release
   train. Either the feature works today or the UI does not mention it.
10. **Ship a step-header count that does not match reality.** `"Step 5 of
    9"` on `OnboardingSecrets.vue` while the step is a stub is worse than
    removing the step. Either the step does work or the step count drops
    to 8.
11. **Make the recovery path require the failure mode.** If losing the
    password means SSH, then the password IS SSH; we lied about "no CLI
    ever" the moment Sarah lost her password. Recovery must work in-app or
    with a physical-presence signal, not a shell.
12. **Auto-apply mutations on step change.** PATCH on Continue is fine;
    PATCH on tab switch or on typing is not. Sarah scrolls the tabs on DNS
    to compare — she is not committing yet.
13. **Show two primary CTAs on one screen.** A step has one Continue. The
    dashboard has one "Go to next action". Competing primaries mean no
    primary.

---

## 7. Cross-cutting acceptance (test harness scaffolding)

Playwright fixtures every wizard test can reuse:

- `expect(page).toHaveNoPreOrShell()` — asserts G2 + G3 across the current
  page's DOM. Ship as a custom matcher in the E2E project so worker agents
  cannot forget it.
- `expect(page).toHaveOneVisiblePrimary()` — G5.
- `expect(page).toSurviveReload()` — reloads, waits for hydrate, asserts
  URL + fields unchanged (G9).
- `expect(pkg).toHaveStatusPill()` — asserts a `[data-status]` matches the
  enum in §3.10 X3.

Every new PR that adds or edits an onboarding view must add or update tests
using these matchers. A PR that touches `OnboardingDone.vue` without a test
citing X1 through X6 is not reviewable.

---

## 8. Definition of done for the fix

- All four P0s in §4 are gone from `main`. Verified by Playwright asserting
  the strings in A6, X1, X2 are absent from the DOM on a clean install
  running against a real backend.
- The 30-minute journey in §2 completes without opening a terminal, without
  reading a docs page, and without the user typing anything beyond their
  domain (optional) and their password-manager master password.
- `/` on first load matches §5.1 through §5.4 exactly.
- The 13 anti-patterns in §6 are enumerated in `docs/UX_REVIEW_CHECKLIST.md`
  (out of scope for this spec — worker step 2 owns it) and every PR review
  cites them by number.

If any of the above fail, we ship a build that Sarah returns. That is the
only failure mode we care about.
