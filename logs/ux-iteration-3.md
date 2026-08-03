# Aurora UX — Iteration 3 plan

**Branch:** `rename/aurora`
**Baseline:** `packages/dashboard/e2e/results/iter-2.md` — 39 pass / 14 fail / 9 skip. Iter-2 shipped the living checklist and per-package probing on `/onboarding/done`. AdGuard first-run detection is unit-tested; `wizard-happy-path` and dashboard-home rendering are still deferred.
**Author:** product-manager (Sarah persona, no-CLI-ever principle)

---

## 1 · The three targets for iteration 3

Sarah's three biggest remaining friction points, in strict priority order:

| # | Target | Why (Sarah) | Owns which failing E2E |
|---|--------|-------------|------------------------|
| **1** | **Error-recovery is actually recoverable** — plain-English failure reasons, always-visible Retry, `<pre>sudo cp …</pre>` scrub on `/onboarding/tls` | Something *will* fail on Sarah's Optiplex (port conflict, pull rate limit, disk full, wrong compose version). Iter-1 shipped a Retry button; iter-2 shipped `failed` pills. But we never verified the error copy is human. If she sees `up.sh exited non-zero` or `java.net.ConnectException`, she deletes Aurora. UX principle 4 ("Errors are actionable") is currently aspirational, not tested green. | `error-recovery.spec.ts` (3 tests currently gated/skipped), `no-cli-instructions.spec.ts › /onboarding/tls` (2 fail on `sudo cp` copy) |
| **2** | **Media-stack sub-checklist** — expandable "Media" row on `/onboarding/done` with per-service state for Prowlarr → Sonarr → Radarr → Bazarr → Seerr | Sarah's headline use case is *the Netflix alternative*. The iter-2 checklist ships a single green "Media – Open" pill the moment Sonarr answers on `:8989`. But if Prowlarr has zero indexers, or Seerr has no Jellyfin URL, the *arr stack is a beautiful shell that downloads nothing and requests nothing. She thinks it's done. It isn't. | Not a red test today — this is *silent* friction the checklist grid actively hides. New `media-substack.spec.ts` codifies the expected shape. |
| **3** | **Storage row → per-OS mount instructions with an SMB reachability probe** | Sarah wants "family photos on the shared folder." Today the storage row goes green the second the `samba` container is `Up`. She has no idea how to actually connect from her MacBook, her Windows laptop, or her iPhone. Instructions must live *inside* Aurora, one-click-per-OS, zero shell copy. | Extends `done-page-checklist.spec.ts` (storage-row expand + per-OS panels); new `smb-reachability.spec.ts`. |

**Explicitly cut from this iteration (nits, not friction):**

- The "You're up." / "You're set." headline rewrite on `OnboardingDone.vue:75`. Current copy is fine; polish, not a blocker.
- The "onboarding is committed" phrasing sweep. Iter-4 or opportunistic.
- SSE swap for `/api/services/status` (still 5s polling). Iter-4 owns transport.
- Dashboard-home checklist rendering (the 3 skips in `package-status-probing.spec.ts`). Real work, but no reason to batch it with error-recovery — do it alone next iteration when it's the biggest fish.
- The 11 upstream `wizard-happy-path` defects (Welcome→Admin selector plumbing, secrets copy, TLS controls). Iter-4/5 owns the wizard hardening pass.

Do not silently expand into any of the above. If the worker sees a "while I'm here" opportunity, note it and hand back.

---

## 2 · Required implementation

### 2a · Target #1 — Error-recovery is actually recoverable

The launch-failure surface (`components/onboarding/LaunchProgress.vue`) has a
Retry button, but the failure text is whatever fell out of stderr. Fix the
copy layer, prove it with the three `error-recovery.spec.ts` tests, and
scrub the last two shell copies from `/onboarding/tls`.

#### 2a.i — Backend: classify failures, don't just quote them

**`services/LaunchService.java`** — extend the `finish(...)` path so the
`failureReason` on the job (already surfaced via `event: done` SSE + the
final `GET /{id}` payload) is a **classified, human-copy string**, not a
raw stderr echo.

Add a small classifier (private static, ~40 LoC — a switch on regex
patterns is fine; do not pull in a rules engine):

| Pattern in tailed stderr / exit code | Classified reason (user copy) | `failureCode` |
|--------------------------------------|-------------------------------|---------------|
| `bind: address already in use` on port `<N>` | `"Port <N> is already in use by another program on this box. Free it up or pick a different port."` | `port_conflict` |
| `pull access denied` / `toomanyrequests` / `429` from a registry | `"Docker Hub is rate-limiting Aurora right now. Wait a couple of minutes and try again."` | `pull_rate_limited` |
| `no space left on device` | `"The disk Aurora is installing to is full. Free up space or pick a different drive."` | `disk_full` |
| `Cannot connect to the Docker daemon` | `"Aurora can't reach Docker on this box. Check that Docker Desktop / the Docker service is running."` | `docker_down` |
| Container exits `!= 0` within 30s of `starting` | `"<container> started but crashed straight away. Aurora tailed its log to the panel below."` | `container_crashed` |
| exit != 0, no known pattern matched | `"Something went wrong bringing up <first failed package>. The log below has the details."` | `unknown` |

The **`failureCode`** is a new field on `event: done` and on
`GET /api/onboarding/launch/{id}`. Frontend uses it to pick the right
CTA (`Retry`, `Retry when ready`, `Open Docker`), but the default remains
`Retry`. Do **not** synthesise fake success on unknown patterns; the
`unknown` case still surfaces the log tail.

**Reason strings are for humans and MUST NOT contain shell substrings.**
Extend `StatusProbeServiceTests` guardrail (iter-2 §6 risk 7) to also
scan `LaunchService.classify()` output — new
`LaunchServiceClassifierTests.java`, one case per row above, plus one
"reason must not contain any of `sudo `, `docker `, `bash `, `./scripts/`,
`ssh `" sweep across all classifier outputs.

#### 2a.ii — Frontend: expose the classified reason + always-Retry

**`components/onboarding/LaunchProgress.vue`** — currently lines 88–103
capture the raw reason; extend to also capture `failureCode` and render:

- A `[data-tone="err"]` banner with the classified copy at the top of the
  failure panel. Existing red header stays.
- The Retry button (already `data-testid="launch-retry"`) is **always
  visible on `state === 'failed'`**, regardless of whether we have a
  reason. E2E: `error-recovery.spec.ts › install failure shows retry + plain-English reason` currently `route.fulfill()`s `POST /api/onboarding/install|apply|launch` with 500 — Retry must render even when the *response* itself is the failure (not an SSE `done` event). Handle both entry paths in one branch.
- Live log panel opens automatically on failure (already partly true —
  verify). The log region is the `role="log"` element the E2E test looks
  for; make sure it renders **within 3 s of the Install click**, even
  before the first backend line arrives. Seed with
  `"Aurora is starting your services…"` at t=0 so the region isn't
  empty. (`error-recovery.spec.ts › install log emits progress within 3s`
  requires `innerText.length > 0` in the log region at 3s.)

**`views/onboarding/OnboardingReview.vue`** — the "Install failed" path
today sets `installErr.value = e.message` (line 88). Route that through
the same classifier by hitting a new backend helper
`POST /api/onboarding/install/classify-error` **or** — simpler — surface
the structured error body from the 500 response (`error`, `message`
fields, matching iter-1's contract). The E2E test posts a 500 with
`{error:"port_conflict", message:"Port 53 is already in use…"}`; parse
that shape and render `message` verbatim inside a `[data-tone="err"]`
alert. Also render a Retry button (`getByRole('button', { name: /retry/i })`
must pass). Do not invent new endpoints; just parse the JSON body the
worker/test already produces.

#### 2a.iii — Frontend: `/onboarding/tls` sudo-copy scrub

**`views/onboarding/OnboardingTls.vue`** — line 48:

```html
<p><code>sudo cp caddy-root.crt /usr/local/share/ca-certificates/ &amp;&amp; sudo update-ca-certificates</code></p>
```

Delete. Replace with the same one-click UX as macOS/Windows above:

- Keep the "Linux (Debian/Ubuntu)" section.
- Body: `"Save the file to your Downloads folder. Aurora will show you a step-by-step in Settings → TLS after install."`
- No `<code>` element. No `<pre>`. No `sudo`. No `docker`. No `./scripts/`.

Rationale: the CLI walkthrough exists for a reason — installing a root
CA on Linux *does* need a privileged copy — but Aurora's rule (principle
1) is **the user is never the operator**. If we can't offer a one-click
Linux flow yet, we offer *nothing* rather than a shell paste. Iter-4 can
add a "One-line install" that Aurora writes to the file via a
sudo-helper flow, but that's out of scope here. The scrub is enough to
green `no-cli-instructions.spec.ts › /onboarding/tls does not show sudo copy`.

Same file, sweep for any other `<code>` / `<pre>` while you're in there.
Verify:

```
grep -RnE '<(pre|code)' packages/dashboard/frontend/src/views/onboarding/OnboardingTls.vue
# → zero hits
```

### 2b · Target #2 — Media-stack sub-checklist

The iter-2 `probe` block on `packages/media/manifest.yml` covers Sonarr
only. Extend to a **probe group** so the checklist row can expand into
per-service rows.

#### 2b.i — Manifest schema — `probe.services[]`

**`packages/media/manifest.yml`** — extend, do not replace:

```yaml
probe:
  kind: http_json                  # kept for the roll-up (any child down → row not-running)
  container: sonarr
  in_cluster_url: http://sonarr:8989/api/v3/system/status
  external_url: http://sonarr.{domain}/
  auth_treats_401_as_up: true
  services:                        # NEW — child rows, ordered
    - id: prowlarr
      title: Indexers (Prowlarr)
      role: blocker                # if this is not-configured, media doesn't work
      kind: http_json
      container: prowlarr
      in_cluster_url: http://prowlarr:9696/api/v1/indexer
      external_url: http://prowlarr.{domain}/
      auth_treats_401_as_up: true
      needs_config_when: '200_empty_array'   # zero indexers = needs-config
    - id: sonarr
      title: TV shows (Sonarr)
      role: primary
      kind: http_json
      container: sonarr
      in_cluster_url: http://sonarr:8989/api/v3/system/status
      external_url: http://sonarr.{domain}/
      auth_treats_401_as_up: true
    - id: radarr
      title: Movies (Radarr)
      role: primary
      kind: http_json
      container: radarr
      in_cluster_url: http://radarr:7878/api/v3/system/status
      external_url: http://radarr.{domain}/
      auth_treats_401_as_up: true
    - id: bazarr
      title: Subtitles (Bazarr)
      role: optional               # not a blocker for playback
      kind: http_json
      container: bazarr
      in_cluster_url: http://bazarr:6767/api/system/status
      external_url: http://bazarr.{domain}/
      auth_treats_401_as_up: true
    - id: seerr
      title: Family requests (Jellyseerr)
      role: blocker                # if Sarah's family can't request, the point is gone
      kind: http_json
      container: jellyseerr
      in_cluster_url: http://jellyseerr:5055/api/v1/status
      external_url: http://jellyseerr.{domain}/
      auth_treats_401_as_up: true
      needs_config_when: '200_setup_incomplete'   # Seerr's /api/v1/status returns commitTag etc when set-up; a shape check will do
```

Only the `services:` list is new. The parent-level `kind: http_json` etc
stays so the *row roll-up* still uses Sonarr as the fast-path indicator.

**Do not** add child probes to `photos`, `home-automation`, `ai` in this
iteration. Media is the one Sarah is buying Aurora for. The others earn
their sub-checklists later.

#### 2b.ii — Backend — probe each child in the same probe cycle

**`services/StatusProbeService.java`** — read `probe.services[]` if
present. For each child, run the same probe kernel already implemented
for the parent (2s timeout, 3s TTL cache, priority sort). Roll-up rule
for the parent row:

- If any `role: blocker` child is `needs-config` or `failed` or
  `not-started` → **parent state = `needs-config`**, parent reason =
  `"Prowlarr has no indexers"` (or whichever child's reason wins by
  priority).
- Else if any `role: primary` child is `failed` → parent `failed`.
- Else if all `primary` children `running` → parent `running`.
- `optional` children never demote the parent below `running`; they
  only render inside the expansion.

Extend the JSON payload (`ServiceStatus`) with an optional
`children: ServiceStatus[]` field. Iter-2's shape stays valid for
callers that ignore the new field.

**`StatusProbeServiceTests.java`** — add cases:

- Prowlarr `200 []` → child `needs-config` with reason
  `"Prowlarr has no indexers yet"`. Parent rolls up to `needs-config`.
- Prowlarr `200 [{...}]` + Sonarr `401` + Radarr `401` + Seerr `200`
  configured → parent `running`, all children collapsed.
- Bazarr `not-started` alone → parent stays `running` (bazarr is
  optional), Bazarr shown only when expanded.
- Seerr `200 setup_incomplete` → child `needs-config`, parent
  `needs-config`.

#### 2b.iii — Frontend — expandable ChecklistItem

**`components/onboarding/ChecklistItem.vue`** — extend:

- When `service.children?.length > 0`, render a chevron on the row header
  and a child list below on expand. Root element gains
  `data-expandable="true"`, expanded state exposed as
  `data-expanded="true|false"`.
- Child rows use the **same component recursively** (no new component
  file). Each child gets its own pill, its own primary CTA, its own
  `data-package="media/prowlarr"` compound key so the E2E can address
  them.
- Expand rule: **auto-expand** when the parent state is
  `needs-config` or `failed`. Otherwise collapsed by default. User can
  toggle either way; toggle persists in `sessionStorage` under
  `aurora.checklist.<pkg>.expanded`.
- Roll-up copy: when collapsed and parent is `needs-config`, the row
  subtitle reads e.g. `"Prowlarr needs indexers before anything downloads."`
  — pulled from the winning child's reason.

**`components/onboarding/DoneChecklist.vue`** — one small change: when
priority-sorting the top-level rows, treat the winning child's
priority for tie-break so the media row surfaces above other
`needs-config` rows when *its* blocker is Prowlarr (i.e. lift the
worst-child priority into the parent's effective sort key).

**New E2E — `packages/dashboard/e2e/tests/media-substack.spec.ts`:**

- Seed: `enabled: [core, privacy, media, storage]`, and mock
  `/api/services/status` to return media with `children:[prowlarr:needs-config,
  sonarr:running, radarr:running, bazarr:not-started, seerr:running]`.
- Assert: media row renders `data-expandable=true`, `data-expanded=true`
  (auto-expand on `needs-config`), five child rows visible with
  `data-package="media/prowlarr" …`, Prowlarr child has
  `data-status="needs-config"` and a `Finish setup` CTA whose `href`
  points at `/prowlarr` on the domain.
- Assert: with all children `running`, media row auto-collapses,
  chevron toggles it back open, `sessionStorage` persists across a
  reload.

### 2c · Target #3 — Storage row: SMB reachability + per-OS instructions

Storage row today = docker inspect `samba`. Two problems:

1. `Up` ≠ *reachable* — a common misconfig is that the Samba container
   is Up but the ports aren't bound because host network is disabled.
2. Even when it works, Sarah cannot mount the share.

#### 2c.i — Backend — SMB reachability probe

**`services/StatusProbeService.java`** — add `kind: smb` to the probe
registry. Implementation: attempt a **TCP connect to `{lan_ip}:445`
with a 1-second timeout**, via `Socket.connect(SocketAddress, 1000)`.
Do **not** speak SMB. Do **not** try to authenticate. Do **not** list
shares. TCP-open is enough — a share we can't list is still Sarah's
problem to fix in `packages/storage/.env`, and iter-3 is not adding
credential-aware probing.

Result mapping:

- Container `Up` **and** TCP `445` open → `running`, reason `null`.
- Container `Up` **and** TCP `445` closed → `failed`, reason
  `"Samba is running but port 445 is closed on the network. Check host firewall."`.
- Container `Exited` → `failed`, reason `"Samba stopped unexpectedly."`.
- Container missing → `not-started`.

**`packages/storage/manifest.yml`** — update:

```yaml
probe:
  kind: smb
  container: samba
  host_port: 445
  external_url: smb://{lan_ip}/
  guest_share: media
```

`guest_share` is what the frontend renders in the per-OS instructions
(§2c.ii).

**`StatusProbeServiceTests.java`** — add:

- TCP `445` open (bind a `ServerSocket` in the test on a randomised
  port, override `host_port` via a package-local test fixture, or use
  MockWebServer's socket) → `running`.
- TCP `445` closed → `failed` with the copy above.
- Container missing → `not-started`.

Wall-clock cap on the socket probe is **1s**, not the default 2s, so
the parent probe budget doesn't blow out.

#### 2c.ii — Frontend — per-OS mount panel inside the storage row

**`components/onboarding/ChecklistItem.vue`** — the storage row (or,
cleanly, any row whose service payload includes a new optional
`connect_from` block) renders an expand chevron with per-OS panels:

The backend adds `connect_from` to the storage `ServiceStatus`:

```json
{
  "package": "storage",
  "state": "running",
  "connect_from": {
    "smb_url": "smb://192.168.1.42/media",
    "unc_path": "\\\\192.168.1.42\\media",
    "guest": true
  },
  ...
}
```

Frontend renders four collapsed panels inside the expansion:

1. **On a Mac** — one-click: `<a href="smb://192.168.1.42/media">Open in Finder</a>` (Safari and Finder do the right thing). Sub-copy: `"Finder will open with the share connected. Guest access is on by default."`
2. **On Windows** — one-click: `<a href="file:////192.168.1.42/media">Open in Explorer</a>`. Sub-copy: the same. **No** `net use` command. **No** `\\` copy block that Sarah has to paste anywhere.
3. **On iPhone** — instructions: `"Files app → Browse → three-dot menu → Connect to Server → smb://192.168.1.42"`. Include a small QR-code component (`qrcode.vue` is already on the classpath — verify; if not, use a 200-byte inline SVG generator, do not add a heavyweight lib) that encodes the SMB URL so she can point her phone at the laptop screen.
4. **On Android** — instructions: `"Install *Files by Google* → menu → SMB → Server: 192.168.1.42, Guest."` Same QR.

Whatever we do, **no `<code>` or `<pre>` block that contains
`mount `, `//`, `sudo`, or `smbclient`.** The `no-cli-instructions.spec.ts`
scan will catch that immediately.

If TCP `445` is closed, the storage row is `failed`, the row shows the
classified reason (§2a rules apply — this is the same UX principle:
errors are actionable). The per-OS panel is hidden on `failed` — no
point pointing Sarah at an unreachable share.

**New E2E — `packages/dashboard/e2e/tests/smb-reachability.spec.ts`:**

- Mock `/api/services/status` to return storage with `state: "running"`,
  `connect_from: { smb_url: "smb://10.0.0.5/media", unc_path: "\\\\10.0.0.5\\media", guest: true }`.
- Assert: storage row expandable; expansion contains four `[data-os]`
  panels (mac, windows, ios, android); mac panel has an `<a>` with
  `href="smb://10.0.0.5/media"`; windows panel has an `<a>` with
  `href="file:////10.0.0.5/media"`; iOS panel contains an SVG.
- Second case: mock storage `failed` with the port-445-closed reason.
  Row shows reason, expansion is hidden, Retry is visible.
- `grep '<pre>\|<code>' packages/dashboard/frontend/src/components/onboarding/ChecklistItem.vue` still returns zero hits.

---

## 3 · Explicit non-goals for iteration 3

- **No headline copy rewrite** on `OnboardingDone.vue`. "You're set." stays.
- **No "onboarding is committed" phrasing sweep.** No global copy pass.
- **No SSE transport for `/api/services/status`.** Still 5s polling. Iter-4.
- **No dashboard-home checklist rendering.** The 3 skips in `package-status-probing.spec.ts` stay skipped. Iter-4.
- **No sub-checklists for `photos`, `home-automation`, `ai`, `identity`.** Media is the only package that gets an expansion in iter-3.
- **No AdGuard-in-E2E-compose fixture.** The 1 red `adguard-password-check.spec.ts` case stays red — unit test coverage is proof enough for now.
- **No touching `wizard-happy-path.spec.ts` upstream defects.** Welcome→Admin nav, admin selectors, packages `[data-package]` on the wizard step, secrets copy, TLS controls — all iter-4/5.
- **No Linux root-CA one-click flow** on the TLS step. Just delete the `sudo cp` copy; the CTA becomes "install from Settings → TLS later".
- **No new lib for SMB.** TCP-open only. No `jcifs`, no `smbj`.
- **No smart credential probing.** If the user set up authenticated SMB shares, the row still says `running` on TCP-open and the per-OS panel notes guest-by-default. Iter-4 owns credential-aware probing.

---

## 4 · Definition of done

1. **`error-recovery.spec.ts` — all 3 tests green**, not skipped:
   - `install failure shows retry + plain-English reason (no stack trace)` — passes.
   - `install log emits progress within 3s of clicking Install` — passes.
   - `done page: any failed package exposes a Retry action` — passes (already implicitly true via ChecklistItem; assert holds under the seeded failure fixture).
2. **`no-cli-instructions.spec.ts` — all `/onboarding/tls` cases green.** No visible `<code>`/`<pre>` on the TLS step. Full-suite `no-cli-instructions.spec.ts` is 28/28.
3. **`media-substack.spec.ts` (new) — all cases green.** Media row expandable, five child rows, per-service pills, auto-expand on `needs-config`, `sessionStorage` toggle persistence.
4. **`smb-reachability.spec.ts` (new) — all cases green.** Storage row per-OS panels with `smb://` and `file:////` links; failed-port-445 case surfaces classified reason + Retry.
5. **`done-page-checklist.spec.ts` and `package-status-probing.spec.ts` on `/onboarding/done` stay green.** No regression from the recursive `ChecklistItem` change.
6. **`LaunchServiceClassifierTests.java` (new) — 6/6 pass**, one per row in §2a.i table.
7. **`StatusProbeServiceTests.java` — new cases pass:** 4 media-substack roll-up cases (§2b.ii) + 3 SMB cases (§2c.i). Total new backend test count = 6 (classifier) + 4 (media) + 3 (smb) = **13**.
8. **Backend unit tests green:** `mvn -pl packages/dashboard/backend test`. Test count goes up by 13 exactly (pre-existing SB4 bean-override collisions in `AuroraApplicationTests` + `PackagesServiceTests` are still there, still flagged in scratchpad — not this iteration's job).
9. **Live aurora `:8090` healthy through the change.** `docker exec aurora wget -qO- http://127.0.0.1:8090/api/health` returns `{"status":"ok"}`. Live container is not redeployed by this iteration.
10. **Copy scan:**
    ```
    grep -RiE '(sudo |docker |bash |\./scripts/|ssh )' \
      packages/dashboard/frontend/src/views/onboarding/OnboardingTls.vue \
      packages/dashboard/frontend/src/components/onboarding/ChecklistItem.vue \
      packages/dashboard/frontend/src/components/onboarding/LaunchProgress.vue
    ```
    Must return zero hits.
11. **Full-suite E2E delta vs iter-2:** ≥ **+5 passing tests**, **0 new failures**. Skipped count drops by at least 3 (the three `error-recovery.spec.ts` tests). Target: 44+ pass / ≤ 11 fail / ≤ 6 skip.

---

## 5 · Files the worker will touch

**Backend (Java, Spring Boot 4):**

- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/LaunchService.java` — add `classify(String tailStderr, int exitCode, String failedPackage)` returning `(reason, failureCode)`. Wire into `finish(...)` so `failureReason` is the classified copy and a new `failureCode` field ships on both `event: done` and `GET /{id}`.
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/StatusProbeService.java` — (a) probe `services[]` recursively; (b) roll-up rules per §2b.ii; (c) new `kind: smb` probe via `Socket.connect(..., 1000)`; (d) `connect_from` payload for storage.
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/controllers/OnboardingController.java` — surface `failureCode` in the launch job JSON. If `/install` still exists (review path), ensure its 500 responses use the same `{error, message}` shape iter-1 defined so the frontend classifier surfaces the copy verbatim.
- `packages/dashboard/backend/src/main/java/com/tomaytotomato/aurora/services/PackagesService.java` — extend `readProbe(name)` to also parse `probe.services[]` and `probe.host_port`, `probe.guest_share`. Do not extend the manifest schema loader beyond these fields.
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/services/LaunchServiceClassifierTests.java` — **new**, one case per §2a.i row + one no-shell-copy sweep.
- `packages/dashboard/backend/src/test/java/com/tomaytotomato/aurora/services/StatusProbeServiceTests.java` — extend with 4 media roll-up cases + 3 SMB cases.

**Frontend (Vue 3.5 + TS):**

- `packages/dashboard/frontend/src/api/services.ts` — add `children?: ServiceStatus[]`, `connect_from?: { smb_url, unc_path, guest }`, and `failure_code?: string` to relevant types.
- `packages/dashboard/frontend/src/components/onboarding/ChecklistItem.vue` — recursive rendering for `children`, expand/collapse state, per-OS `connect_from` panels on the storage row. **This is the biggest single file change in iter-3.** Keep it under 300 LoC — if it wants to be more, split the per-OS panels into `components/onboarding/SmbConnectPanel.vue`.
- `packages/dashboard/frontend/src/components/onboarding/LaunchProgress.vue` — always-show Retry on `failed`; render classified reason as `[data-tone="err"]`; seed the log region with an initial line at t=0 so `role="log"` is non-empty within 3s.
- `packages/dashboard/frontend/src/views/onboarding/OnboardingReview.vue` — parse the 500 body's `{error, message}` shape into a `[data-tone="err"]` alert + Retry button.
- `packages/dashboard/frontend/src/views/onboarding/OnboardingTls.vue` — delete the `<code>sudo cp …</code>` line at line 48; replace with the "save file, install from Settings → TLS later" copy per §2a.iii.

**Manifests:**

- `packages/media/manifest.yml` — add `probe.services[]` block per §2b.i. Nothing else changes.
- `packages/storage/manifest.yml` — flip `probe.kind: docker` → `probe.kind: smb`; add `host_port: 445`, `guest_share: media`. Everything else stays.

**E2E (new):**

- `packages/dashboard/e2e/tests/media-substack.spec.ts` — new, per §2b.iii bullets.
- `packages/dashboard/e2e/tests/smb-reachability.spec.ts` — new, per §2c.ii bullets.

**Do not touch — worker guardrail:**

- Any `views/onboarding/*.vue` other than `OnboardingReview.vue` and `OnboardingTls.vue`. `OnboardingDone.vue` stays as iter-2 shipped it.
- `DoneChecklist.vue` — one-line sort tie-break tweak only (§2b.iii), no other change.
- `SecurityConfig.java` — no matcher changes.
- `SystemService.java` — reuse `lanIp()`, do not extend.
- `scripts/up.sh`, `scripts/down.sh`, `compose.yml`, `Dockerfile`.
- Any manifest other than `media` and `storage`.
- Dashboard-home views (iter-4 territory).

---

## 6 · Risks + mitigations

**Risk 1 — Recursive `ChecklistItem` explodes.** *(medium)*

Rendering `<ChecklistItem>` inside `<ChecklistItem>` invites a footgun:
child rows also render `I did this` / `Skip` overrides that write to
`localStorage['aurora.checklist.media/prowlarr.done']`, which is a
namespace decision iter-2 didn't consider. Mitigation: the override
key uses the flat `package` string, and child rows carry
`data-package="media/prowlarr"`. Do **not** let children write their
own `.done` overrides in iter-3 — the "I did this" / "Skip" buttons on
child rows are **hidden** in iter-3 (`v-if="!isChild"`). Parents own
the override; children are read-only rows. Iter-4 can revisit.

**Risk 2 — Prowlarr `/api/v1/indexer` requires an API key.** *(medium)*

Prowlarr's `/api/v1/indexer` returns `401` when unauthenticated, same
as Sonarr. `auth_treats_401_as_up: true` means we classify `401` as
`running` — but "running with zero indexers" is the exact needs-config
state Sarah's family cares about. Mitigation: Prowlarr's `/ping`
returns `200 "OK"` unauthenticated (verify against the current
Prowlarr version at `linuxserver/prowlarr`). Split the probe into (a)
`GET /ping` for up/down, (b) `GET /api/v1/indexer` with the API key
Aurora already knows from the compose env (Prowlarr writes it to a
predictable path — worker checks). If we can't read the API key
without a filesystem hop we haven't earned yet, downgrade Prowlarr to
`up-only` for iter-3 and add a sticky `"Open Prowlarr and add an
indexer"` blurb in the child row. Do not paper over with a fake
`needs-config`.

**Risk 3 — SMB probe on `127.0.0.1:445` false-positives when
tested from the aurora container** — the samba container may bind
`host` net and be reachable via LAN IP but not the loopback of the
aurora container. *(medium)*

Mitigation: the SMB probe always dials `SystemService.lanIp()`, never
`localhost`, never a compose service DNS name. If `lanIp()` is null
(the iter-0 fallback), the probe returns `starting`, not `failed` —
UX principle 3 forbids showing a false red. Add a test case that
`lanIp() == null` yields `starting` with reason `"Aurora is still
figuring out your LAN address."`

**Risk 4 — `Socket.connect(..., 1000)` blocks the parallel executor
under DNS-slow conditions.** *(low)*

`ForkJoinPool.commonPool()` capped at 8 wide (iter-2 contract) is
still fine — 8 × 1s ≤ 8s worst case, but the controller enforces the
4s wall-clock ceiling and returns partials with `probed_ms: -1`. That
path already exists. Verify it fires for SMB too (write a test that
throttles the socket).

**Risk 5 — QR code lib pulls a big dependency.** *(low)*

Do not add `qrcode` (the npm package) — 60KB gzipped is out of
proportion for four QR codes. Use a ~200 LoC inline TypeScript
generator (there are permissive MIT snippets, worker's call) or skip
QR entirely and render `smb://192.168.1.42/media` as large,
selectable text on the iOS/Android panels. If QR is dropped, note it
in the DoD and iter-4 can add it back.

**Risk 6 — Classifier regex over-matches.** *(medium)*

`bind: address already in use` is a stable message across Docker
versions, but non-English locales might trip us. Mitigation: match
against the **English** patterns only, and always fall through to
`unknown` on no match. `unknown` still shows the log tail — Sarah
sees the same live output, just prefixed with the generic copy.
Do not attempt localised matching in iter-3.

**Risk 7 — `OnboardingReview.vue` install path might already be dead
code post-iter-1.** *(check first)*

Iter-1 moved the "run compose up" side of install to
`/api/onboarding/launch` on the Done page. Verify whether the Review
step still triggers a `POST /install` (schema-only commit) vs
`POST /apply` (compose run). If Review's `install` is only writing
`.state.yml`, its 500-classified-error surface is much smaller and
the `error-recovery.spec.ts › install failure shows retry` test is
really about the *Done page* Retry surface. Either interpretation is
fine — worker picks the one that matches current code and greens the
test. **Do not** revive dead endpoints to satisfy a test literally.

---

## 7 · One-line handoff to the worker

> Read `logs/ux-iteration-3.md`. Three targets, ranked: error-recovery
> (classify launch failures + always-Retry + scrub `sudo cp` from
> `/onboarding/tls`), media-stack sub-checklist (Prowlarr → Sonarr →
> Radarr → Bazarr → Seerr as children in one manifest probe block,
> auto-expand on `needs-config`), SMB reachability + per-OS mount
> panels (TCP-open probe on port 445, four one-click OS panels, no
> shell copy anywhere). Do not touch anything outside §5. Run
> `error-recovery.spec.ts`, `no-cli-instructions.spec.ts`,
> `media-substack.spec.ts`, `smb-reachability.spec.ts`,
> `done-page-checklist.spec.ts` against `aurora-e2e`. Definition of
> done is §4. Commit as
> `aurora: UX iteration 3 implement (error-recovery + media substack + SMB panels)`.
