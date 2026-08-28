# Aurora UX Critique — 2026-08-28

Reviewer: Product Manager. User lens: **Sarah** (nurse, spare mini PC, returned a Synology for being "too much", keeps Aurora only if it feels like Sonos). Severity ranking is strict: **blocker > friction > polish**.

Grounded in a live walkthrough of `http://localhost:8090` (logged in as `pi`) plus reading the frontend source under `packages/dashboard/frontend/src/`. Every finding names the exact `.vue`/`.ts` file and quotes the string it objects to.

---

## 1. Executive summary

Aurora is in genuinely good shape: honest empty states, no raw stack traces in the DOM, actionable error copy with retry buttons, and a "what needs a person" strip that is exactly the right instinct. The bones are Sonos-grade. But three things actively lie to Sarah on the pages she sees first, and each one is the kind of contradiction that makes a non-technical user distrust the whole box.

The biggest theme is **internal contradiction between surfaces that were built at different times**: the Overview page tells Sarah security scanning "hasn't shipped" in one card while the card directly above it lists "3 serious security findings," and the global header pill says "ALL GOOD" while the same screen says "2 things need you · ACTION NEEDED." The second theme is **CLI/jargon leakage in the long tail** — the Marketplace card and the Disks parity empty-state literally instruct the user to set environment variables and edit Ansible `group_vars`, which is a P0 against principle 1. The third theme is **redundancy on Overview and Settings**: package status is stated three ways on the home page, and Settings restates system metadata the Overview already owns. Fix the contradictions first — they are cheap, self-contained copy/logic changes — and Aurora goes from "impressive but occasionally uncanny" to "trustworthy."

---

## 2. Per-page findings

### Overview (`/`) — `views/DashboardHome.vue`, `components/AttentionStrip.vue`, `composables/useHealthPill.ts`

- **[REDUNDANCY] [blocker] The Security bento contradicts the live findings above it.** `DashboardHome.vue` — the `data-card="security"` bento renders the permanent stub *"Watching for common misconfigurations / Aurora will start scanning your box once the security module ships."* But the `AttentionStrip` at the top of the same page says *"3 serious security findings are open → Security"* and `/security` renders live findings (the scanner capability is on). Verified live. Sarah reads "scanning hasn't shipped" and "3 serious findings are open" three inches apart. That is the single most trust-destroying thing on the box.
  **Fix:** Delete the stub Security bento entirely (the AttentionStrip already carries the real signal), OR make it render the live `counts.high/medium/low` summary with a `Review checks →` link. Do not ship both a "not shipped yet" card and a live findings feed.

- **[REDUNDANCY] [friction] The header "ALL GOOD" pill contradicts "2 things need you · ACTION NEEDED."** `composables/useHealthPill.ts` computes the header pill purely from package running-state (`running === xs.length → 'All good'`) and ignores security, disks, and backup entirely. So the green **ALL GOOD** pill sits centered in the top bar while the page below screams action-needed. For Sarah, a green all-clear badge that's wrong is worse than no badge.
  **Fix:** Either feed the attention-item worst-tone into the pill (if any `err` attention item exists, the pill cannot say "All good"), or rename the pill to something scoped and honest like "Apps: all running" so it stops claiming to be a global verdict.

- **[REDUNDANCY] [polish] Package status is stated three times on one screen.** `DashboardHome.vue` — the Apps bento shows (a) a green `ALL GOOD` badge, (b) the subtitle "All 3 running", and (c) a per-app list each ending "Running"; then the **"Bring your box online"** `DoneChecklist` at the bottom re-renders the same per-package state a fourth time. That onboarding checklist reading "Bring your box online" on a box that has been up "4d 12h" is stale framing.
  **Fix:** Drop the `DoneChecklist` block from the permanent Overview (it belongs in onboarding/Done), or gate it to only appear while packages are still `starting`. Keep the Apps bento as the single home-page status surface.

- **[NICE-TO-HAVE] [polish]** The Apps bento subtitle "All 3 running" plus a redundant `ALL GOOD` badge is belt-and-braces. Pick one.

### Apps catalogue + Marketplace tab (`/apps/catalogue`) — `views/PackagesCatalogue.vue`

- **[MISSING] [friction] Two different things are both called "Marketplace," and neither installs what Sarah wants.** The in-page tab is `Marketplace 15` (the bundled catalogue), while Settings has a separate **"App marketplace"** card (the hosted signed catalogue). Same word, two meanings, two pages. Worse for Sarah: her three stated goals — a Netflix alternative, AdGuard, phone photo backup — are exactly what she'd hunt for here, and the catalogue gives her no way to search or filter by outcome ("Watch", "Block ads", "Photos").
  **Fix:** Rename the in-page tab to **"Available"** (it already uses `available` badges) to disambiguate from the Settings hosted marketplace. Add a search/filter box and outcome-oriented category chips to the catalogue grid.

- **[REDUNDANCY] [polish]** The empty state *"Nothing left to add — everything is installed."* is good, but the Docker/Compose legend footer (`— one container.` / `— multiple services running together.`) renders on every visit. For Sarah, "container vs compose" is implementation trivia she will never act on.
  **Fix:** Move the Docker/Compose legend behind an "Advanced" disclosure or drop it from the catalogue; it's operator-facing detail on a consumer surface.

### Core apps (`/apps/core`) — `views/PackagesCore.vue`

- **[NICE-TO-HAVE] [polish]** Solid page. The `you are here` badge on the Aurora card and `not running` for absent core services are clear. Header copy *"The essentials aurora needs to run"* lowercases "aurora" mid-sentence — nit, but it's the product name.
  **Fix:** Capitalize "Aurora" in the Core subtitle.

### App detail (`/apps/backup`) — `views/PackageDetail.vue`

- **[NICE-TO-HAVE] [polish] Strong page overall** — the "Open Backup (Kopia)" primary CTA is correctly separated from the Restart/Update/Disable/Uninstall maintenance row, the install-impact confirm dialog is exactly right, and the backup "what gets protected" card with the data-consistency warning is genuinely excellent. No blockers.
- **[REDUNDANCY] [polish]** The "At a glance" Details card repeats the Docker structure badge that's already in the control-panel row directly above it, and `Depends on` is shown both here and in the Related tab.
  **Fix:** Drop the duplicate `Docker` row from the Details card (the control panel already shows it) and let Related own dependencies.

### Security (`/security`) — `views/SecurityPosture.vue`

- **[MISSING] [blocker] The findings are unactionable developer jargon with no one-click fix — only "Dismiss."** The live "3 HIGH" findings all read like *"Container authelia is not digest-pinned … Pin the compose file to a specific `@sha256:…` digest to get reproducible restarts."* Sarah cannot pin a digest, does not have a compose file open, and has no Fix button — her only control is a snooze dropdown and **Dismiss**. This violates principle 4 (errors must say what to do AND offer a fix Aurora can perform). Teaching a non-technical user that the security page's answer is "dismiss the red things" is the opposite of the goal.
  **Fix (frontend-safe portion):** For any finding whose only remediation is a code change the user can't make, replace the raw text with plain-language impact + an Aurora-performable action or an honest "Aurora will fix this in an update" state — and hide the per-finding `Dismiss` on HIGH findings a homeowner can't evaluate. (Backend rule-copy rewrite is a separate worker task; the copy/affordance change is safe here.)

- **[NICE-TO-HAVE] [polish]** Snooze durations up to "90 days" and "Permanent" on a security finding invite Sarah to permanently silence something she doesn't understand. Consider limiting consumer-facing snooze to short durations.

### Disks (`/disks`) — `views/DisksView.vue`

- **[MISSING] [blocker] CLI / Ansible jargon leak — principle 1 violation.** The Parity empty state says *"enable the `snapraid` role in group_vars to set it up"* and the aborted-sync alert references *"one bad `rm -rf`"*. `group_vars` and `rm -rf` are exactly the Linux-operator vocabulary that makes Sarah delete the box. She has never SSHed anywhere; `group_vars` is meaningless and slightly frightening.
  **Fix:** Rewrite the Parity empty state to consumer language ("Aurora can protect against a single drive failure once a spare drive is added — ask whoever set this up, or see the guide") with a docs link, no `group_vars`. Remove the literal `rm -rf` from the aborted-sync copy; say "a large deletion."

- **[NICE-TO-HAVE] [polish]** The SMART attribute table (`Reallocated`, `Raw`, `Worst`, `Threshold`) is pure operator detail. It's correctly behind a row-click dialog, so this is fine — just confirm it never surfaces on the main tab.

### VPN (`/vpn`) — `views/VpnView.vue`

- **[NICE-TO-HAVE] [polish] Best page on the box for honesty.** The *"This VPN is configured but not usable yet. It still needs:"* blocker list is a model for the whole product — it says exactly what's missing in plain words. Keep it.
- **[REDUNDANCY] [polish]** The status is rendered three times on the Overview tab: the header `Badge`, the giant `{{ badge.text }}` in the Tunnel card, and the tunnel `dl`. Minor.
  **Fix:** Drop the header badge on the ready state, or the giant text — one hero statement of run-state is enough.
- **[MISSING] [friction]** The endpoint field placeholder `aurora.duckdns.org` and helper "A dynamic-DNS hostname is more reliable than a raw IP" assume Sarah already has dynamic DNS. There's no link to set one up.
  **Fix:** Add a "What's this?" link next to the Endpoint field explaining/linking dynamic-DNS setup.

### Users (`/users`) — `views/UsersView.vue`

- **[NICE-TO-HAVE] [polish] Admin role badge uses an amber `warn` tone.** `badgeToneFor('admin')` returns `'warn'`, so both admin users render with a caution-colored pill that reads as "something is wrong with this user." Nothing is wrong; they're admins.
  **Fix:** Use `neutral` (or a distinct info tone) for the admin badge; reserve amber for actual warnings.
- **[REDUNDANCY] [polish]** Both listed users (`bruce`, `pi`) show `UTC` / `27 Aug 2026` — fine — but the "Time zone" column is empty-ish value real estate for a two-user home box. Not worth a column on the primary view.
  **Fix:** Consider folding Time zone into the row's secondary text rather than a dedicated column.

### Settings (`/settings`) — `views/SettingsView.vue`, `components/MarketplaceCard.vue`

- **[MISSING] [blocker] Marketplace "off" state tells Sarah to set an environment variable — principle 1 violation.** `MarketplaceCard.vue` off-state: *"Set `AURORA_MARKETPLACE_ENABLED=true` and a catalogue URL to enable it."* Verified live. There is no toggle — the instruction is literally "go edit an env var," which for Sarah means SSH. A settings card that describes a feature must let her turn it on with a click or say "ask whoever set this up," never hand her a shell variable.
  **Fix:** Replace the env-var sentence with either an actual enable toggle (backend permitting) or consumer copy + docs link. Remove the `AURORA_MARKETPLACE_ENABLED=true` string from the DOM.

- **[REDUNDANCY] [friction] The System metadata card duplicates the Overview.** `SettingsView.vue` System card shows Hostname / Domain / LAN IP / Kernel / Docker — but Overview's header already shows Docker version and distro, and Overview's System card + `ReachInfo` already shows Domain + LAN IP with Copy buttons. Sarah sees `aurora.local`, `192.168.0.110`, and `Docker 29.6.2` on both pages.
  **Fix:** Trim the Settings System card to only what's unique (Kernel), or drop it — Overview owns identity/reach. If kept, it should not re-list LAN IP/Domain that ReachInfo already surfaces.

- **[REDUNDANCY] [polish]** "Recent activity" (audit log) on Settings and "Recent changes" (container events) on Overview are two adjacent-feeling activity feeds. Not a bug, but confirm they read as distinct ("who did what" vs "what containers did").

- **[NICE-TO-HAVE] [polish]** LAN aliases empty state *"Enable a package that ships a vhost (e.g. Notes, Git, Media) and hit Reconcile"* uses "vhost" — mild jargon. Say "an app with its own web page."

---

## 3. Debrief for the implementing worker

Do these first, in this order. All are frontend-only copy/logic/layout changes — no backend, no schema, no architecture. Each is self-contained and safe.

1. **Kill the Overview Security-bento contradiction.**
   - File: `packages/dashboard/frontend/src/views/DashboardHome.vue`
   - Change: Remove the `data-card="security"` stub bento (the block whose body is *"Aurora will start scanning your box once the security module ships."*). The live signal already comes through `AttentionStrip`. If a Security tile is desired, replace the stub body with the live high/medium/low counts + a `Review checks →` link — never both a "not shipped" message and live findings on the same page.
   - DoD: On a box with open findings, no text on `/` says scanning hasn't shipped; the security signal appears exactly once.

2. **Remove the env-var instruction from the Marketplace card.**
   - File: `packages/dashboard/frontend/src/components/MarketplaceCard.vue`
   - Change: In the feature-off branch, delete the sentence containing `AURORA_MARKETPLACE_ENABLED=true` and the `<span class="font-mono">` env-var markup. Replace with consumer copy ("The hosted app marketplace is turned off on this box. Ask whoever set it up to enable it, or leave it off — the built-in apps still work.") plus the existing docs link.
   - DoD: `grep` of the rendered DOM on `/settings` returns no `AURORA_MARKETPLACE_ENABLED` and no shell/env syntax.

3. **De-jargon the Disks parity + aborted-sync copy.**
   - File: `packages/dashboard/frontend/src/views/DisksView.vue`
   - Change: In the "No parity disk" empty state, remove *"enable the `snapraid` role in group_vars"* → plain-language sentence + docs link. In the aborted-sync `Alert`, replace *"one bad `rm -rf`"* with "a large accidental deletion."
   - DoD: No `group_vars` and no `rm -rf` render anywhere on `/disks` in any state.

4. **Stop the header pill claiming "All good" while findings are open.**
   - File: `packages/dashboard/frontend/src/composables/useHealthPill.ts` (+ its consumer in `TopBar`/`DashboardHome.vue`)
   - Change: Either scope the pill copy to apps ("Apps: all running") so it stops implying a global verdict, or factor the worst attention tone in so an `err`-level item downgrades the pill off "All good."
   - DoD: On a box with a HIGH security finding, the top bar never shows a green "ALL GOOD" pill.

5. **Rename the catalogue "Marketplace" tab to "Available."**
   - File: `packages/dashboard/frontend/src/views/PackagesCatalogue.vue`
   - Change: In `catalogueTabs`, change the `marketplace` tab label to `Available` (keep the `available` badge). Update the empty-state string that says "check the Marketplace tab" to "check the Available tab."
   - DoD: The word "Marketplace" appears in exactly one place in the app UI (the Settings hosted-catalogue card); the in-page tab reads "Available".

6. **Drop the stale "Bring your box online" checklist from the permanent Overview.**
   - File: `packages/dashboard/frontend/src/views/DashboardHome.vue`
   - Change: Remove the bottom `DoneChecklist` section (the `data-test="dashboard-done-checklist"` block), or wrap its `v-if` so it only renders while at least one enabled package is not yet running.
   - DoD: On a fully-running box (uptime > 0, all apps running), the "Bring your box online" checklist does not render; package status is stated once (the Apps bento).

7. **Fix the admin role badge tone.**
   - File: `packages/dashboard/frontend/src/views/UsersView.vue`
   - Change: `badgeToneFor('admin')` returns `'warn'` → return `'neutral'` (or a dedicated info tone). Amber must mean "something needs attention," not "this user is an admin."
   - DoD: Admin users render with a non-amber badge; no user row looks like a warning by default.

8. **Trim the Settings System card so it stops duplicating Overview.**
   - File: `packages/dashboard/frontend/src/views/SettingsView.vue`
   - Change: Remove the LAN IP and Domain rows from the System metadata card (Overview's `ReachInfo` + System card already own reach), and the Docker row (Overview header already shows it). Keep Hostname + Kernel, or drop the card.
   - DoD: LAN IP, Domain, and Docker version each render on exactly one page's primary surface, not two.

Deferred to a follow-up worker (not in this safe batch): rewriting the backend security-rule remediation copy so HIGH findings are homeowner-actionable (finding #Security-blocker), and adding catalogue search/outcome filters (Apps-friction). These touch backend/rule content or new components and are out of scope for a copy/layout pass.

---

## Residual risks

- The security-rule copy is unactionable for a non-technical user; the frontend fix (hide Dismiss on un-actionable HIGH findings, plain-language impact) is a mitigation, not a cure — the durable fix is backend rule-copy + an Aurora-performable remediation, which is a separate task.
- The header health-pill change (#4) depends on where the pill is consumed; verify `TopBar` reads the same composable so both surfaces stay consistent after the edit.
- Renaming the catalogue tab (#5): the tab uses stable `value="marketplace"` (not the label) for `data-test`/logic, so selectors likely survive — but grep specs for the visible label "Marketplace" before shipping and update any that assert on display text.
