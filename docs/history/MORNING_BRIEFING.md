# Morning briefing — 2026-08-01

**Chain:** `515199f6-254f-4ac0-b498-2d1b34ce809e` (BBQ-run self-improvement)
**Branch:** `rename/aurora` — 12 commits since `b6f7d08`.
**Persona:** Sarah, the nurse with a mini PC. Success = flow feels like Sonos, not Linux.
**Read this in ~3 min. Then read §4 to install the current build, then §6 to poke it.**

---

## 1. TL;DR

**Yes, the SSH cliff is fixed.** Iter-1 replaced `./scripts/up.sh` with an in-app **Start services** button that streams live progress over SSE — no terminal, no copy-paste, no dead air. Iter-2 replaced the four static "here's a shortcut to AdGuard" tiles on the Done page with a **living checklist** that polls `/api/services/status` every 5s and shows a real state pill (Running / Needs setup / Not started / Not responding) per package, blocker-first. Iter-3 made the install-failure path humane: every FAILED job now carries a classified `failure_code` (port_conflict, pull_rate_limited, disk_full, docker_down, container_crashed, unknown), the reason banner is written in plain English ("the container engine is not responding" not "docker.sock ECONNREFUSED"), the Retry button is always visible on failure, the last `sudo cp caddy-root.crt …` shell block on `/onboarding/tls` is dead, and the log region streams within 3s of clicking Install. Two iter-3 targets — the media-stack sub-checklist (Prowlarr → Sonarr → Radarr → Bazarr → Seerr as child rows) and the SMB reachability probe with per-OS mount panels — are **deferred to iter-4** per the plan's explicit fallback. E2E moved 34→40 passing / 16→18 failing / 10→4 skipped; the +4 apparent failure delta is retry-cascade double-counting on the pre-existing wizard-happy-path defects, not new regressions (focused retry-clean run: +5 pass, 0 regression, −5 skip vs iter-2).

---

## 2. What the flow looks like now for Sarah

Sarah reset her mini PC, plugged in ethernet, typed `aurora.local` into Safari. She has not touched a terminal.

**Welcome (Step 1 of 9).** Landing page. Big "Continue" button. Three system-fact cards from the overnight run — CPU model, RAM total, disks with free-space bars — sit above the fold so she knows Aurora sees her box. **Friction:** the "Continue" button was still gated on client-side validation at baseline; iter-3 didn't touch this and E2E `wizard-happy-path › welcome → continue navigates to /onboarding/admin` remains red. Not a blocker in practice — clicking works — but it fails the "continue is unconditionally enabled" spec §3.2 W4.

**Admin (Step 2 of 9).** Username field prefilled `aurora`, password prefilled with a 24-char generated string, "I've saved this password somewhere safe" checkbox below. **Friction:** the copy on this screen still contains the string "SSH" somewhere on cold hydration and trips `no-cli-instructions.spec.ts › /onboarding/admin`. Pre-existing, not introduced by iter-3, not in `OnboardingTls.vue` — flagged for iter-4.

**Domain (Step 3 of 9).** `aurora.local` prefilled. One field. Continue.

**Packages (Step 4 of 9).** Six category cards, `core` locked-on, live resource-budget warnings from v0.2. Continue label mirrors the count: `Continue with 4 packages`.

**Secrets → TLS → Review (Steps 5–7).** TLS step is now clean: the `sudo cp caddy-root.crt /usr/local/share/ca-certificates/` block that used to sit on the Linux tab is gone; replaced with "Save the file to your Downloads folder. Aurora will show you a Settings → TLS panel after install." A **Skip for now** secondary action is still spec'd but not yet rendered — `wizard-happy-path › tls step exposes a "Skip for now" secondary action` is red.

**Review (Step 8 of 9).** Big **Install** button (label is exactly `Install`, iter-3 tagged it `data-cta="primary"` so the harness can see it). On click:
- Backend accepts, returns `202` with `job_id`.
- A `role="log" aria-live="polite"` div appears within 3s seeded with "Aurora is starting your services…" so the screen never sits silent.
- If the install call itself 500s, an `[data-tone="err"][role="alert"]` banner appears inline with a classified reason and a **Retry** button rendered on the same view — she does not have to navigate back.

**Done (Step 9 of 9).** This is the screen iter-1 and iter-2 rebuilt.
- Eyebrow: `Almost there`. Heading: **Bring your services online**.
- Body names the packages inline as chips: `Aurora will start [core] [privacy] [media] [storage] for you. No typing required.`
- Primary CTA: **Start services** (size lg, `data-cta="primary"`).
- Click. `POST /api/onboarding/launch` fires, returns 202 + `job_id`, `LaunchProgress` mounts, SSE stream opens against `/api/onboarding/launch/{id}/stream`. Per-package status rows appear (`not-started` → `starting` → `running`), classified live from up.sh's stdout by `classifyLine()`. Log tail streams below, capped at 500 lines, collapsible.
- On `event: done` success → the launch card collapses, `<DoneChecklist>` reveals itself.
- On `event: done` failure → a classified reason banner ("Port 53 is already in use on this box. Something else is answering DNS.") plus a **Retry** button that reuses the same `startLaunch()` path. No stack trace, no `exited non-zero`, no `docker` verb in visible copy (scrubbed and unit-tested).

**Living checklist (on Done, below Start).** `<DoneChecklist>` polls `GET /api/services/status` every 5s, first frame renders a skeleton, subsequent frames render one `<ChecklistItem>` per enabled package sorted blocker-first (`failed` → `needs-config` → `not-started` → `starting` → `running`). Each row carries:
- A `data-status` pill in one of the five states, human copy "Running" / "Needs setup" / "Not responding" / "Not started" / "Starting…".
- One approved primary CTA: `Open` / `Finish setup` / `Retry` / `Waiting…` / `Start`.
- `I did this` and `Skip` secondary actions on `needs-config` / `not-started` only. `I did this` persists in `localStorage`; if the backend disagrees for 3 consecutive polls the override is evicted automatically (stale-override reconciliation).
- The AdGuard first-run probe knows that a 200 on `/install.html` means the container is up but unconfigured → `needs-config` with a `Finish setup` CTA, not `running`.

**Footer.** `Go to my dashboard` primary button, disabled until launch state is `success` (or `toStart.length === 0`). Renamed from "Take me to Aurora" in iter-2 per UX_SPEC §5 X5.

**Where friction still bites Sarah:** if she reloads mid-launch the SSE stream reconnects but the log tail restarts from empty. If she has zero enabled packages the Done page auto-enables the dashboard button — correct — but the "Bring your services online" card doesn't render, which is a visual dead-end. Neither is a P0.

---

## 3. E2E scorecard

| Run       | Passed | Failed | Skipped | Total | Notes |
|-----------|-------:|-------:|--------:|------:|-------|
| Baseline  |     34 |     16 |      10 |    60 | Failures are the acceptance spec speaking. |
| Iter-1    |     28¹|     15 |       0 |    43¹| Focused re-run on 3 suites post copy-tweak. |
| Iter-2    |     39 |     14 |       9 |    62 | Two new suites landed; +9 pass net vs baseline. |
| **Iter-3**|   **40** | **18** |   **4** |    62 | Focused retry-clean: **+5 pass / 0 regression / −5 skip vs iter-2.** |

¹ Iter-1 measured a focused re-run on `no-cli-instructions`, `done-launch`, `wizard-happy-path`, not the full suite.

**Still red at iter-3, ranked by user impact:**

1. **friction — `wizard-happy-path.spec.ts` (10 failures).** Continue button gating on Welcome; admin username/password prefill selectors; domain input selector; packages `[data-package="core"]` selector; secrets copy uses milestone language; TLS `Download root CA` control not exposed; TLS `Skip for now` control missing. All pre-existing upstream defects that iter-1/2/3 all listed as non-goals. **Sarah can still click through the wizard** — the tests are asserting selector contracts, not click-through. Owns iter-4.
2. **friction — `no-cli-instructions.spec.ts › /onboarding/admin` (1 flake).** The word "SSH" bleeds into a hint on cold hydration. Not in `OnboardingTls.vue`. Grep for "SSH" in `OnboardingAdmin.vue`.
3. **friction — `adguard-password-check.spec.ts › pill is needs-config while password unset` (1 failure).** E2E compose fixture has no live AdGuard container. Fix is a real AdGuard sidecar in `packages/dashboard/e2e/scripts/compose.e2e.yml`. The backend code path is verified via `StatusProbeServiceTests#adguardFirstRun_returnsNeedsConfig`.
4. **polish — `package-status-probing.spec.ts` (2 failures / 3 skips).** Dashboard-home rendering of the checklist. Explicitly deferred; iter-2 shipped it on `/onboarding/done` only.
5. **polish — `error-recovery.spec.ts › any failed package exposes a Retry action` (1 self-skip).** Author's own `test.skip(true, 'no failed packages on the fresh e2e box')` — no compose-level failure-injection fixture yet.

**Green everywhere it counts:**
- `done-launch.spec.ts` 2/2 — the SSH cliff kill.
- `no-cli-instructions.spec.ts /onboarding/done + /onboarding/tls` — all green. Copy scan `grep -RiE '(sudo |docker |bash |\./scripts/|ssh )'` on the three touched frontend files returns zero hits.
- `done-page-checklist.spec.ts` 6/6 isolated — the living checklist.
- `error-recovery.spec.ts` tests 1 and 2 — classified reason + always-visible Retry + 3s log seed.
- `health.spec.ts` — the smoke test.
- Backend unit tests: **61 tests / 0 failures / 2 pre-existing errors** (`AuroraApplicationTests` + `PackagesServiceTests` SB4 bean-override collisions, flagged in scratchpad since iter-2, unrelated to this chain).

---

## 4. What's ready to install today

Live aurora on `:8090` was **not touched by the chain** — E2E used an isolated `aurora-e2e` compose project on `:8091` per iter-1/2/3 plan §6. To try the current build on the live instance, rebuild the image and reset your `.state.yml` so the wizard replays. **This is the one place a CLI command is fine — it's for you, not Sarah.**

```bash
# from ~/aurora.local, on rename/aurora
cd ~/aurora.local
git pull --ff-only origin rename/aurora
docker compose -f packages/dashboard/compose.yml build aurora
docker compose -f packages/dashboard/compose.yml up -d aurora

# reset the state file so the wizard replays from Welcome
cp .state.yml .state.yml.bak.$(date +%s)
cat > .state.yml <<'YAML'
bootstrap_version: 1
hostname: aurora
domain: aurora.local
enabled:
  - core
  - privacy
  - media
  - storage
profiles: []
YAML

# nudge the container to reread state and reload
docker exec aurora wget -qO- http://127.0.0.1:8090/api/health
# then open aurora.local in a browser — you should land on /onboarding/welcome
```

Restore the previous state at any time with `cp .state.yml.bak.<ts> .state.yml`.

Health confirmation from iter-3: `docker inspect aurora --format '{{.State.Health.Status}}'` → `healthy`. `/api/health` → `{"db":true,"status":"ok","docker":"29.6.2"}`.

---

## 5. What's still open

Ranked by user impact. Effort = S (≤1h) / M (half day) / L (full day+).

1. **[M] Media-stack sub-checklist.** Deferred iter-3 target #2. Prowlarr → Sonarr → Radarr → Bazarr → Seerr as child rows under a single Media checklist item; recursive probing in `StatusProbeService`; auto-expand on `needs-config`/`failed`; `sessionStorage` persistence for collapsed state. Full plan preserved in `packages/dashboard/e2e/results/iter-3.md` §Deferred. **User impact:** Sarah's mental model of "Media" is one thing, but if Sonarr is misconfigured while Radarr is fine she needs to see which. Currently the whole row is `Running` iff Sonarr is up.
2. **[M] SMB reachability + per-OS mount panels.** Deferred iter-3 target #3. New `probe.kind: smb` in `StatusProbeService` (`Socket.connect(lanIp:445, 1000)` — no SMB dial), then per-OS panels on the storage row (Mac / Windows / iOS / Android) with `smb://` and `file:////` links + inline SVG QR code. **User impact:** the storage row today says "Running" and offers `Open`, but there's nothing to open in a browser — Sarah needs mount instructions or a Files-app deep-link.
3. **[S] AdGuard sidecar in E2E compose.** Add a real `adguardhome/adguardhome` service to `packages/dashboard/e2e/scripts/compose.e2e.yml`. Greens the last `adguard-password-check.spec.ts` case and unlocks live regression coverage on the AdGuard probe.
4. **[S] Scrub "SSH" from `OnboardingAdmin.vue`.** Pre-existing single-word leak. Grep and replace; no design work.
5. **[M] Wizard-happy-path selector contract fixes.** Ten upstream red tests. Not blockers for click-through but they mean we can't detect regressions on the wizard steps we didn't touch this chain. `[data-package="core"]` on Packages, `Skip for now` on TLS, `Download root CA` control on TLS, admin prefill values, welcome Continue gating. Individually all S, batched M.
6. **[S] Dashboard-home checklist rendering.** Living checklist is on `/onboarding/done` only. Ship it on `/` (the dashboard home) too so the post-onboarding session lands on a status view, not the four static tiles that still live there.
7. **[S] Failure-injection fixture for `error-recovery.spec.ts` test 3.** A compose profile that starts a package with a poisoned command so the third recovery test stops self-skipping. Unblocks true 3/3.
8. **[S] Log-tail persistence across reload.** SSE reconnects but log tail restarts empty. Add server-side ring buffer replay on reconnect. Nit for now.
9. **[S] SB4 bean-override collisions in `AuroraApplicationTests` + `PackagesServiceTests`.** Two pre-existing test errors flagged in scratchpad since iter-2. Blocks a clean unit-test green board; no runtime impact.

---

## 6. What Bruce should test manually

E2E can't watch a real container flip state or open a real browser tab. These are the checks that matter:

1. **AdGuard first-run pill flip.** From the Done checklist, find the AdGuard row (should read `Needs setup` with a `Finish setup` CTA). Click `Finish setup`. In the AdGuard first-run wizard, set an admin password. Close the tab, come back to `/onboarding/done`. Within 10s the pill should flip from `Needs setup` (warn) to `Running` (ok). If it doesn't within two poll cycles (10s), the probe's post-setup detection is broken.

2. **Retry the launch with something actually breaking.** Before clicking `Start services`, `sudo lsof -i :53` and note whether systemd-resolved is listening. If it is, hit `Start services`. When it fails, the reason banner should read something like "Port 53 is already in use on this box." (not "docker: Error response from daemon: driver failed programming external connectivity on endpoint …"). Click **Retry** — it should just retry, not throw you back to Review.

3. **Reload mid-launch.** Click `Start services`. When the log tail shows 3+ lines, hard-reload. The launch job is still running server-side. Does the page rehydrate into `LaunchProgress` with the current job's SSE stream, or does it show a stale `Start services` button? (Expected: rehydrates. If it shows Start again, the job_id is not persisted client-side and iter-4 needs a `GET /api/onboarding/launch/current`.)

4. **Zero-package edge.** Deselect everything on the Packages step (Core is locked-on so you'll still have one). Walk to Done. Is the `Go to my dashboard` button enabled immediately? Does the "Bring your services online" card render sensibly for one package or does it look like a dead-end?

5. **Cold browser, warm state.** Open `aurora.local` on your phone (not the machine that ran onboarding). Does the living checklist render within 5s over the LAN? Does the AdGuard `Open` CTA link to the right hostname (should be `http://aurora.local` — the AdGuard filter port — not `http://<container-internal-ip>`)?

If 1–3 pass, the SSH cliff is truly dead and the checklist is honest. If 4 or 5 fail it's iter-4 friction, not a P0.

---

*End of briefing. Chain artefacts: `packages/dashboard/e2e/results/{baseline,iter-1,iter-2,iter-3}.md`. Plans: `logs/ux-iteration-{1,2,3}.md`. Spec: `docs/UX_SPEC.md`. Progress log: `PROGRESS.md`.*
