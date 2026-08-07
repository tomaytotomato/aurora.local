# Aurora UX Spec — Dashboard Home (`/dashboard/home`)

**Audience:** worker agents implementing iter-1 of the post-onboarding dashboard,
and QA writing Playwright coverage against `/dashboard/home`.
**Scope:** the authenticated landing page a user hits after finishing the
wizard — header, four bento cards (System, Containers, Packages, Security),
and the Metrics card below the grid.
**Source of truth for behaviour:** `docs/UX_SPEC.md` (persona + principles +
anti-patterns), `docs/ONBOARDING_V0.2.md` (backend API shapes),
`logs/dashboard-bugs-2026-08-01.md` (live evidence Bruce captured tonight
against `:8090` at commit `9d5fd8e`).
**Companion:** this file is to `/dashboard/home` what `UX_SPEC.md` is to the
wizard. If a clause here contradicts UX_SPEC.md, UX_SPEC.md wins.

Every criterion below is Playwright-implementable against `/dashboard/home`.

---

## 1. Persona: Sarah, the morning after

Same Sarah as `UX_SPEC.md` §1. Paediatric nurse, mini PC on the shelf, has
never SSH'd anywhere. Last night she completed the wizard, watched the live
launch log stream, saw green pills on `/onboarding/done`, closed the laptop.
This morning is her first time on `/dashboard/home`.

She opens Safari on her laptop. She types `aurora.local` in the address bar.
She expects the dashboard. She has already decided Aurora is real — the
wizard felt like Sonos. Now she wants three things, in order:

1. **Confirmation the box is still healthy.** One glance. Green pill = good.
2. **Her next action, if any.** Photos not backing up yet? Tell her.
3. **A way in to each service she installed.** Big buttons that open the
   thing.

She does not want CPU graphs. She does not want a container event stream.
She does not want to know what a docker short-id is. If the header reads
`be1523c08f0f.undefined`, she assumes Aurora broke overnight and reboots the
mini PC. If the System card shows `NaN KB / NaN KB (NaN%)`, she assumes the
box is dying and Googles the error. If she clicks the Start button on a
package and it silently fails with `HTTP 409`, she thinks Aurora is bricked
and posts one line on the family group chat: "the server thing is broken
again, sorry."

This is the screen where Aurora either graduates from "cute wizard" to
"trusted appliance" or gets uninstalled. The morning after is the entire
game.

---

## 2. Confirmed defects — ranked by user impact

All four are **blockers**. Bruce captured them tonight at `9d5fd8e` from an
authenticated session. Ranked by how quickly Sarah bails.

### 2.1 BLOCKER — Package Start button returns HTTP 409

**Symptom.** On `/dashboard/home`, the Packages card lists 5 enabled packages
(core, media, notes, privacy, storage). Each has a **Start** button. Clicking
Start on any not-started package flashes an error and does nothing. Devtools
shows `POST /api/onboarding/launch → 409 {"detail":"onboarding already
complete; use authenticated endpoints"}`.

**Root cause hypothesis.** The dashboard-home Packages card is calling the
onboarding-scoped launch endpoint. `guardMidOnboarding` correctly refuses
because `complete=true`. There is no authenticated post-onboarding sibling
endpoint for per-package start. The wizard endpoint is a bulk "start
everything in `enabled_packages`" action — different lifecycle, different
verb than "start this one package now".

**Acceptance criterion.** Clicking Start on a not-started package tile
issues exactly one `POST /api/services/{package}/start` (or equivalent
authenticated route), returns `202` with a `job_id`, and the pill flips to
`starting` within 500 ms. On success the pill flips to `running` within 5 s
(within one poll cycle). On failure the pill flips to `failed` with an
inline classified reason and a Retry button — same classifier used by the
wizard's `LaunchService.classify()` (see UX_SPEC §4 P0-1 fix outline and
iter-3 morning briefing §2). No request from `/dashboard/home` ever hits
`/api/onboarding/launch`.

**Spec section.** §4 (Cards) — Packages card, primary action.

**Why Sarah bails.** She clicks the primary button on the primary card,
sees a red error, and the pill does not change. Aurora is broken. She has
no other affordance for starting a package. She reboots the box, sees the
same thing, gives up.

---

### 2.2 BLOCKER — Header identity reads `be1523c08f0f.undefined`

**Symptom.** The header on every authenticated page reads (verbatim):

> `be1523c08f0f.undefined idle Back to Homepage · bruce`

Overview card reads: `be1523c08f0f. · vCPU · Docker`.

**Root cause hypothesis (two bugs braided).**

1. `be1523c08f0f` is the aurora container's **docker-assigned hostname**
   (12-hex short id). The backend is reading `os.hostname()` from inside the
   container instead of the value in `.state.yml`. Source of truth is
   `.state.yml → hostname: aurora`.
2. `.undefined` is the frontend rendering `${info.hostname}.${info.domain}`
   where `info.domain` is not on the response payload. Field-name drift
   between `SystemService.info()` and the composable that consumes it
   (`domain_name` vs `domain`, or the field is on a different endpoint).

The correct rendered string is **`aurora.local`**. Both values come from
`.state.yml` (`hostname: aurora`, `domain: aurora.local`), and
`renderIdentity()` (`lib/identity.ts`) collapses the duplicate first label
so the header never reads the doubled `aurora.aurora.local`. See
`Essence.md`. `idle` and `Back to Homepage · bruce` also get crammed on
the same line with no visual separation; header anatomy is absent (see §3).

**Acceptance criterion.**

- Header renders `aurora.local` (hostname + domain joined, with the
  duplicate leading label collapsed by `renderIdentity`). It must never
  read the doubled `aurora.aurora.local`. No 12-hex short id appears
  anywhere in the header. No literal string `undefined` appears in the DOM.
- If either value is missing from the backend response, the header renders
  a single em-dash `—`, never the string `undefined`, never the token
  `null`, never a bare trailing dot (`aurora.`).
- Header anatomy separates identity, health, user, and actions into three
  regions with real spacing (see §3.1). `Back to Homepage` link is
  **removed** — homepage was retired in v0.1 per `packages/core/compose.yml`
  comment; the link is dead.

**Spec section.** §3 (Header anatomy).

**Why Sarah bails.** Top-of-page identity is the single most visible piece
of chrome on the dashboard. If it reads garbage, Sarah reads the whole page
as garbage before scrolling.

---

### 2.3 BLOCKER — System card shows `NaN` for uptime, memory, disk

**Symptom.** The System card renders:

```
uptime NaNh
NaN KB / NaN KB (NaN%)   ← memory row
NaN KB / NaN KB (NaN%)   ← disk row
```

**Root cause hypothesis.** JS arithmetic on `undefined`. The unit strings
(`h`, `KB`, `%`) render fine, which means the frontend's formatter is
running — the values themselves are missing from the payload. Almost
certainly field-name drift between `SystemService.status()` (or
`MetricSampler.snapshot()`) and the Vue composable. Backend likely emits
`mem_total_kb` / `disk_total_kb` / `uptime_sec`; frontend reads
`memoryTotal` / `diskTotal` / `uptimeHours`. The sampler is running
(startup log confirms `metric sampler online`), so the data exists — it is
not reaching the view.

**Acceptance criterion.**

- No visible text on `/dashboard/home` contains the literal token `NaN`,
  `null`, `undefined`, or `[object Object]`. Playwright:
  `expect(page.locator('body')).not.toContainText(/\bNaN\b|\bundefined\b/)`.
- Memory row renders as `<used human> / <total human> (<pct>%)` where each
  value is a real number formatted with `Intl.NumberFormat` and a
  right-sized unit (KB → MB → GB — never a 12-digit KB count).
- Disk row same shape.
- Uptime renders in human-friendly units — minutes for <1h, hours for
  <48h, days for ≥48h. `uptime NaNh` is banned; `uptime —` is acceptable
  during first paint before the sampler responds; `uptime 6h 42m` is the
  hydrated form.
- If the backend field is missing or 401, the card renders `—` per row,
  never `NaN`. Empty-state contract §4.1 applies.

**Spec section.** §4.1 (System card).

**Why Sarah bails.** `NaN` is the shape of "the software is broken and
the developers left in the middle." Three `NaN`s in a stack is worse than
one — it reads as cascading failure. She Googles "aurora NaN memory".

---

### 2.4 BLOCKER — Metrics card exposes raw axios error string

**Symptom.** The Metrics card body reads (verbatim):

> `no data — Request failed with status code 404`

**Root cause hypothesis.** The dashboard-home Metrics card is calling an
endpoint that does not exist on the backend router (Bruce's anonymous probe
returned 401 on five plausible variants, meaning they exist in the security
filter chain but not as routes; the frontend gets 404 with a session
cookie). Frontend swallows the axios `error.message` and renders it
verbatim into the card body — no error boundary, no plain-English mapping.

**Acceptance criterion.**

- No visible text on `/dashboard/home` matches the regex
  `/Request failed with status code \d+/`, `/Network Error/`, `/AxiosError/`,
  or any HTTP status code as a bare number outside a debug panel.
- On a `4xx`/`5xx` from any card's data source, the card renders the
  error-state contract in §5: one calm heading, one plain-English body
  sentence, one `Try again` button. No exception message, no URL, no
  container id, no stack trace.
- Card must **not** render `no data — Request failed with status code 404`.
  Either the endpoint exists and returns a legitimate empty-metrics state
  (§4.5 empty state), or the card renders the §5 error state — never both,
  never the axios string.
- If the Metrics endpoint truly does not exist yet in iter-1, the card
  renders the empty-state contract §4.5 with the copy "Metrics land next
  release." No 404 fetch is issued in the first place; the frontend gates
  the request on a capability flag from `/api/system/info`.

**Spec section.** §4.5 (Metrics card), §5 (Error-state contract).

**Why Sarah bails.** This is UX_SPEC §6 anti-pattern #5 in a bento card:
"Return 'Failed' without a reason and a Retry button." It is also the
exact phrasing Sarah has been trained to fear by every broken WordPress
site she has ever seen. It tells her Aurora is a website that fell over,
not an appliance.

---

## 3. Screen anatomy — `/dashboard/home`

Three regions, top to bottom: **header → four cards (bento) → metrics
strip**. Grid principles borrowed from `UX_SPEC.md` §5 (dashboard first-load).

### 3.1 Header

Single row, full-width, sticky. Three regions with real gap between them:

```
[  identity  ]           [  health  ]                    [  user + actions  ]
aurora.local      ● Running · 5 packages          bruce · Sign out
```

- **Identity (left).** `hostname.domain` in one line, monospace-adjacent
  weight (semibold sans, tabular numerals). Rendered from `.state.yml` via
  `GET /api/system/info` — never from `os.hostname()`. If either value is
  missing, render `—` for the missing half, never the string `undefined`.
  Optional muted-tone subtitle beneath: `Aurora v0.2.0` (from
  `package.json`).
- **Health (centre).** A single status pill with `data-status` in the
  UX_SPEC §5.2 enum (`running | needs-config | failed | not-started`).
  Aggregated: if any package is `failed`, pill is `failed`. Else if any is
  `needs-config`, pill is `needs-config`. Else if all core services are
  `running`, pill is `running`. Muted secondary line: `5 packages` (see
  §4.3 for count semantics — must not read `running 0` when core is up).
- **User + actions (right).** Signed-in user (`bruce`), a divider dot, a
  `Sign out` link. Nothing else. **No `Back to Homepage` link** — homepage
  is retired; the link is dead. Do not replace it with anything unless
  there is a real destination.

Regions separated by CSS grid gaps, not centred dots or pipes. `idle`,
`Back to Homepage`, and `· bruce` must not collide on one line without
visual boundaries — that is the shape of Bruce's current bug.

### 3.2 Bento grid — four cards

Four cards, two-column grid on ≥ 720 px, single column stacked below. Card
order left-to-right, top-to-bottom, by importance to Sarah's morning:

| Position    | Card       | Why here                                             |
|-------------|------------|------------------------------------------------------|
| Top-left    | Packages   | Sarah's list of things she installed. Primary card.  |
| Top-right   | System     | "Is the box healthy?" — the second glance.           |
| Bottom-left | Containers | Living status feed, secondary but informative.       |
| Bottom-right| Security   | Placeholder until the security module ships.         |

Each card has:

- A title in the same weight as the header identity.
- A subtitle explaining what the card is (≤ 60 chars) — no jargon.
- A body region with either data, an empty state (§4), or an error state
  (§5). Exactly one of the three renders at any time.
- Zero or one primary action per card. If the card has an action, it is
  bottom-right of the card in a consistent slot.

### 3.3 Metrics strip

Full-width, below the bento grid. Not a card in the four-card grid — a
single thin strip so it doesn't compete with the Packages primary action.
Content = a compact CPU / memory / disk sparkline row when data is
available, or the empty state §4.5 when it isn't. **No axios error strings,
ever.**

### 3.4 Spacing + typography (from `UX_SPEC.md` §5)

- 16 px base grid; 24 px between cards; 32 px between header and grid.
- One monospace weight (identity, values). One sans stack (labels, prose).
- Status pills use the five UX_SPEC §5.2 colours; never invent a sixth.
- Empty-state and error-state bodies inherit the same typography as
  hydrated bodies — the card doesn't visually degrade because it has no
  data. Empty ≠ broken (see §4).

---

## 4. Empty-state contract per card

An empty card is not a broken card. Every card that can render without
data has a designed empty state with three fixed slots: **glyph, one-line
headline, one-line body**. No error tone. No spinner past 1 s. No axios
error strings. No emoji clown act.

### 4.1 System card — empty state

Trigger: `GET /api/system/status` responds but sampler has not populated
yet (cold boot; first paint before sampler tick).

- Glyph: muted server icon.
- Headline: `Warming up`.
- Body: `Aurora is taking your box's first measurement.`
- Auto-transitions to the hydrated state within one sampler tick (≤ 5 s).
- Falls through to the §5 error state on 4xx/5xx.

Banned in this state: `NaN`, `undefined`, `0 KB / 0 KB`, `—h / —h / —%`.
If the sampler hasn't spoken yet, render the empty state above — don't
render a skeleton of zeroes.

### 4.2 Containers card — empty state

Trigger: docker event stream is connected but the ring buffer is empty
(no events since Aurora came up).

- Glyph: muted container-stack icon (not a shipping-container emoji).
- Headline: `Nothing has changed recently.`
- Body: `Container starts and stops will show up here.`
- **Banned copy:** the current `No events yet — waiting on Docker stream.`
  string. "Waiting on Docker stream" is a developer's internal state; Sarah
  reads it as "Docker is broken." Do not name Docker in the empty state.

If the event stream is *not* connected, that is the §5 error state, not
this empty state. The two are distinct.

### 4.3 Packages card — count semantics + empty state

Trigger for empty state: `enabled_packages` is empty (impossible in
practice — `core` is always enabled — but the empty state must exist for
safety). Copy: `You haven't enabled any packages yet.` Body:
`Add a package from Settings → Packages.` Link goes to the settings route.

**Count semantics fix (Bruce's evidence: `5 enabled — running 0`).** The
"running" count must match reality. `core` returns `running` from the
status probe, so a fresh box reads `5 enabled — 1 running`, not
`5 enabled — 0 running`. Acceptance:

- Numerator = count of packages whose status probe returned `running` at
  the last poll (default 5 s cadence, same as `/onboarding/done` in
  iter-2).
- Denominator = `enabled_packages.length`.
- If `numerator > denominator`, the display collapses to a single count
  (`5 packages`) and logs a client-side warning — never renders `6 / 5`.
- Format: `X enabled · Y running` (interpunct, not em-dash), or a single
  green `All running` when `X === Y` and `Y > 0`.

### 4.4 Security card — empty state (this is the entire card in iter-1)

Trigger: security module has not shipped. The card is a permanent
placeholder in iter-1. The card must **not** read as broken.

- Glyph: muted shield icon.
- Headline: `Security posture`.
- Body: `Aurora will start scanning your box for common misconfigurations
  once the security module ships.` (One sentence. Present-tense. No dates,
  no milestone names — see UX_SPEC §6 anti-pattern #9.)
- No CTA. No `Coming soon` badge. No `v0.3` or `M2` or milestone token in
  the DOM.
- The card has the same visual weight as the other three — same padding,
  same corner radius, same border. It renders as a designed empty card,
  not as a stub.

### 4.5 Metrics strip — empty state

Two triggers: (a) the metrics endpoint doesn't exist in iter-1 yet, or
(b) it exists and returns an empty timeseries.

- Glyph: muted graph icon.
- Headline: `Metrics land next release.` (iter-1 case) OR
  `Not enough data yet.` (empty timeseries case).
- Body: iter-1 case — `Aurora will chart your box's last 24 hours here.`
  Empty case — `Come back in an hour once Aurora has recorded a few
  samples.`
- No spinner. No 404 fetch. In iter-1, the frontend must not issue a
  request at all if the endpoint isn't wired — gate it on a capability
  flag in `/api/system/info` (e.g. `capabilities.metrics: false`).

---

## 5. Error-state contract

Any `4xx` or `5xx` from any card's data source renders the same three-slot
error state. Never an axios `error.message` string. Never a status code
number in the DOM. Never a container short-id.

Three slots, in order:

1. **Headline.** Plain English. One sentence. Names *what* the card can't
   show, not *why the HTTP call failed*. Examples:
   - System: `Aurora couldn't read the box's stats.`
   - Containers: `Aurora lost the container feed for a moment.`
   - Packages: `Aurora couldn't reach the package service.`
   - Metrics: `Aurora couldn't fetch metrics.`
2. **Body.** One sentence. What Aurora will do next, or what Sarah can
   try. Examples:
   - `We'll try again automatically in a few seconds.`
   - `Refresh the page or try again below.`
3. **Action.** Exactly one button labelled `Try again`. Clicking it re-fires
   the underlying request. If the retry also fails, the same state
   re-renders — do not degrade into the axios string on retry failure.

Banned in this state (Playwright asserts):

- `/Request failed with status code \d+/`
- `/Network Error/`
- `/AxiosError/`
- `/ECONNREFUSED|ETIMEDOUT|ENOTFOUND/`
- Bare HTTP codes in visible copy (`401`, `404`, `500` outside dev tools).
- Any hostname, IP, port, URL path, container id, or docker verb.
- The word `undefined` or `null` in any tone.

Retry action must debounce (≤ 1 request per 500 ms) and enter
`aria-busy="true"` on click within 100 ms per UX_SPEC §3.1 G7.

---

## 6. Iter-1 scope

**In scope (this overnight run):** the four defects in §2, plus copy-only
polish on cards to remove the Bruce-shouldn't-have-to-name items from
`logs/dashboard-bugs-2026-08-01.md`.

Concrete iter-1 targets:

1. **Bug 2.1 fix — new `POST /api/services/{package}/start`.**
   - Backend: new authenticated endpoint. Reuses `LaunchService` internals
     but scoped to a single package. Returns `202 + { job_id }`. Same SSE
     stream shape as the onboarding launcher so the frontend `LaunchProgress`
     component can be reused.
   - Frontend: dashboard-home Packages card's Start button switches to the
     new endpoint. Kill the direct `POST /api/onboarding/launch` call from
     any authenticated route.
   - Files: `packages/dashboard/backend/src/main/java/.../ServicesController.java`,
     `LaunchService.java` (new `startOne(name)` method or a
     `startPackages(Set<String>)` overload), and the frontend Packages
     card component under `packages/dashboard/frontend/src/views/dashboard/`
     (the tile Start button + its composable).
2. **Bug 2.2 fix — header identity from `.state.yml`.**
   - Backend: `SystemService.info()` (or equivalent) returns
     `{ hostname, domain }` sourced from `.state.yml` (via `StateService`),
     not from `os.hostname()`. If reading from `.state.yml` isn't cheap,
     inject `StateService` and read the cached values.
   - Frontend: header component reads `info.hostname` + `info.domain`,
     renders `${hostname}.${domain}`, falls back to `—` for any missing
     half. Field-name contract between backend and frontend is documented
     in `docs/ONBOARDING_V0.2.md` API appendix.
   - Files: `SystemService.java`, `SystemController.java`,
     `packages/dashboard/frontend/src/components/DashboardHeader.vue` (or
     current name), and its composable/store.
   - Same fix removes the `Back to Homepage` dead link.
3. **Bug 2.3 fix — System card values.**
   - Backend: `SystemService.status()` field names audit. Emit
     `{ uptime_sec, mem_used_kb, mem_total_kb, disk_used_kb, disk_total_kb }`
     (or align to whatever the frontend expects — the point is agreement,
     documented in `ONBOARDING_V0.2.md`).
   - Frontend: composable maps backend fields to the view model. Values
     format via `Intl.NumberFormat` and a `humanBytes()` / `humanUptime()`
     helper. Fallback to `—` on missing fields — never `NaN`, never `0`.
   - Files: `SystemService.java`, `MetricSampler.java` (verify shape), the
     dashboard-home System card component + composable.
4. **Bug 2.4 fix — Metrics card empty state.**
   - Frontend: gate the metrics fetch on `info.capabilities.metrics`.
     Backend returns `capabilities.metrics: false` in iter-1 because the
     endpoint doesn't ship yet. The card renders the §4.5 iter-1 empty
     state and issues no request.
   - Files: `SystemController.java` (add `capabilities` block),
     `SystemInfo` DTO/type on both sides, dashboard-home Metrics component.
   - This is deliberately not "add a metrics backend" — that is out of
     scope (see below). It's "stop rendering axios errors."
5. **Copy-only polish.**
   - Containers card: replace `No events yet — waiting on Docker stream.`
     with the §4.2 empty state.
   - Packages card: fix `5 enabled — running 0` count semantics per §4.3.
   - Header: kill `Back to Homepage`. Kill `idle` if it's a bare label
     with no value (replace with the aggregated health pill from §3.1).
   - Security card: apply §4.4 empty state. Replace whatever current
     "Full posture scan lands with the security module" placeholder reads
     as (fine phrasing, but visually needs the §4.4 treatment).

**Explicit non-goals for iter-1:**

- **No new metrics timeseries backend.** No new `MetricsController`, no
  storage schema, no historical query API. The iter-1 fix is the empty
  state and the capability flag. Real metrics are iter-2 or later.
- **No security module content.** Placeholder card only. No scan, no
  posture score, no findings list. §4.4 empty state and that's it.
- **No theme overhaul.** No new colour tokens, no dark-mode refactor, no
  new icon set. Reuse existing tokens and existing icon exports.
- **No new SSE channels beyond what's needed to reuse `LaunchProgress`.**
  The status probe polling loop from iter-2 stays as-is on 5 s cadence.
- **No dashboard-home living checklist port.** That's the iter-4 backlog
  item (`SCRATCHPAD` `mount DoneChecklist on dashboard-home`). Keep the
  four-card + metrics-strip layout in iter-1.
- **No auth/session changes.** The 401/404 disambiguation Bruce ran into
  on anonymous curl is a diagnostic aid, not a fix target.

---

## 7. Anti-patterns to enforce

Mirrors `UX_SPEC.md` §6. Numbered so worker agents can cite them in PR
review. These are absolute — a PR that ships any of them is not
reviewable.

**D1. No raw error strings in the DOM.** The regexes in §5 are canonical.
`error.message` from axios never reaches a rendered node. Every catch site
maps to §5's three-slot error state.

**D2. No `NaN` in visible copy.** If a formatter would emit `NaN`, the
input is `undefined` and the card renders the §4 empty state or the §5
error state — never a partial number. Playwright:
`expect(page.locator('body')).not.toContainText(/\bNaN\b/)`.

**D3. No `undefined` or `null` in visible copy.** Same rule. Missing
values render as `—`. Playwright:
`expect(page.locator('body')).not.toContainText(/\bundefined\b|\bnull\b/)`
(scoped outside dev consoles).

**D4. No docker container short-ids in user-facing copy.** The 12-hex
`be1523c08f0f` (or any 12-hex string that matches
`/^[0-9a-f]{12}$/`) is banned in the header, the System card, the
Containers card, and anywhere else Sarah looks. Container ids belong in
`/health` or dev tools, not the dashboard. Playwright:
`expect(page.locator('body')).not.toContainText(/^[0-9a-f]{12}$/m)` on
visible text.

**D5. No dead `Back to Homepage` link.** Homepage was retired in v0.1.
The link is stale copy-paste. It must be removed, not repointed. Do not
replace it with `Back to onboarding` or any other retrofit — the
dashboard is the home.

**D6. No jargon in card titles or bodies.** Same banned list as UX_SPEC
§6 anti-pattern #8: `vhost`, `compose`, `docker`, `container`, `bind
mount`, `TLS SNI`, `reverse proxy`. Note that the Containers card is *a
card that shows what changed*, not a card that says the word "container"
in every sentence. Title: `Recent changes`. Not `Docker events`.

**D7. No milestone names in card copy.** `v0.3`, `M2`, `next slice`,
`coming soon` are banned in visible copy per UX_SPEC §6 anti-pattern #9.
The Security card's §4.4 body ("once the security module ships") is the
one permitted forward-looking clause; nothing else may reference a
future release by name or number.

**D8. No card without either data, an empty state, or an error state.**
Every card is in exactly one of those three states at all times.
Skeletons are allowed for ≤ 1 s of first paint; past 1 s, the empty
state renders. Past 3 s of dead air on any card, the empty state must
already be visible (UX_SPEC §6 anti-pattern #6).

**D9. No two primary CTAs on `/dashboard/home`.** UX_SPEC §6 anti-pattern
#13 applies here. The Packages card's Start button (or the aggregated
"Bring services online" if we ever land it here) is the only accent
button on the page. Sign-out is a link. Try-again in an error state is
secondary tone.

**D10. No hostname string that isn't sourced from `.state.yml`.** The
backend `os.hostname()` value is banned in any rendered header or
identity slot. If `.state.yml` is unreadable, the header renders `—`,
not the container hostname as a fallback. Fallback to the wrong answer
is worse than fallback to no answer.

---

## 8. Definition of done for iter-1

- All four §2 blockers are gone from the live `:8090` build. Playwright
  scan on `/dashboard/home` (authenticated fixture): zero matches for
  `NaN`, `undefined`, `Request failed with status code`, any 12-hex
  container id, or the string `Back to Homepage`.
- Clicking Start on a not-started package flips the pill to `starting`
  within 500 ms and to `running` within 5 s on a healthy pull.
- Header reads `aurora.local` on Bruce's live box, `bruce` on the
  right, aggregated health pill in the centre.
- System card renders real values for uptime, memory, disk with human
  units.
- Metrics card renders §4.5 empty state without issuing a 404.
- Containers card empty state matches §4.2 copy exactly.
- Packages count reads `5 enabled · 1 running` (or whatever the probe
  actually returns) — never `running 0` with core up.
- Every card's error path renders §5's three-slot state on injected 500.

If any of the above fail, `/dashboard/home` still reads as a broken
website in Sarah's morning. That is the only failure mode iter-1 exists
to close.

---

*Companion files: `docs/UX_SPEC.md` (wizard + principles),
`docs/ONBOARDING_V0.2.md` (API shapes), `logs/dashboard-bugs-2026-08-01.md`
(evidence). Iter-1 plan drops next at `logs/dashboard-iteration-1.md`.*
