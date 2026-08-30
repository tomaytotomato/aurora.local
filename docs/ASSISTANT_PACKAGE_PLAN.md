# Aurora's assistant package — plan (LibreChat + Pi + skills + configs)

**Status:** approved 2026-08-30 by Bruce; execution in progress.

Answers locked in:

1. **Name: Pi.** The agent is Pi. Vhost is `pi.aurora.local`. Package
   directory is `packages/pi`. LibreChat's dropdown reads "Pi". Ntfy
   `From:` is "Pi". Nothing new to name; the tool Bruce already trusts
   just moved from his laptop to the box.
2. **Single-tenant, household model.** One shared secretary, one
   shared memory. Pi sees which Authelia user is talking and can
   surface cross-user context ("your wife wanted you to do X"). No
   per-user skill folders. A `household/SKILL.md` replaces the
   originally proposed `bruce/SKILL.md`.
3. **Notifications: all channels available, kept simple to start.**
   Wire through Aurora's existing `NotificationsService` (ntfy,
   discord, webhook). Ntfy is the default channel for reminders.
   Adding a second channel is a Settings toggle, not a package
   rewrite. No new plumbing; we already own the delivery layer.
4. **pi-server shim: approved.** Node/TS service using the Pi SDK,
   exposes `POST /v1/chat/completions` in OpenAI shape so LibreChat
   is a dumb client. This is the piece Aurora owns end-to-end.

## What this closes

Bruce is terrible at todo lists, remembering dates, and household
paperwork by his own admission. He runs a homelab and Aurora is
already the fuse box for it — Stalwart owns his mail, SilverBullet
owns his notes, Home Assistant will eventually own his automations.
The gap is a **secretary that lives on the box, is aware of every
app Aurora runs, can read and write on his behalf, and prompts him
before he forgets**.

Two shapes were considered and rejected:

1. **Ollama + a local model on Bruce's Dell OptiPlex** — Bruce doesn't
   like Ollama; the box is a CPU-only i5-6500T with ~15 GB RAM; a
   local model good enough to be useful for reasoning is out of reach.
2. **Pi on baremetal via GitHub Copilot** — this is what Bruce runs
   now for coding. It works, but it lives on his dev laptop and knows
   nothing about aurora.local. It cannot be a "household" secretary
   because it is not present when Bruce is not at his desk.

The chosen shape is a **package on Aurora that runs headless in a
container, is aware of the box's apps, and is reachable from any
device on the LAN**. Pi is the reasoning core; LibreChat is the
door.

## Doctrine check (ESSENCE.md)

Bruce's own ESSENCE says: "99% done in a web UI, 1% on the terminal",
"Docker is our friend", "Just one solid choice for each area",
"Opinionated installs", "AI is encouraged to improve and maintain
this tool", "Core apps should never be swapped". This package is:

- One clear choice for the web chat (LibreChat) and one for the
  agent runtime (Pi).
- Docker end to end.
- Opinionated by default — LibreChat comes preconfigured to talk to
  Pi; Pi comes preloaded with Aurora-aware skills and tools.
- Not core. This ships as an optional `assistant` package so a box
  that just wants mail and notes stays that way.

## The stack

```
┌───────────────────────────────────────────────────────────────┐
│  Bruce's phone / laptop / any LAN device                      │
│  https://pi.aurora.local   OR   ntfy push notifications │
└───────────────────────────────────┬───────────────────────────┘
                                    │
                    (Caddy vhost, forward-auth via Authelia)
                                    │
┌───────────────────────────────────▼───────────────────────────┐
│  LibreChat (Node/React, official image)                       │
│  - the web UI, auth surface, conversation history,            │
│    multi-model dropdown, mobile-friendly PWA                  │
│  - loads librechat.yaml declaring exactly one endpoint:       │
│    "Aurora Assistant" → http://pi:8080/v1 (OpenAI-shape)      │
│  - loads Aurora's MCP servers as tools directly (belt-and-    │
│    braces path; see "why two mechanisms" below)               │
└───────────────────────────────────┬───────────────────────────┘
                                    │
                          (OpenAI-compatible HTTP)
                                    │
┌───────────────────────────────────▼───────────────────────────┐
│  pi-server: a small Node adapter that runs Pi's SDK           │
│  - accepts POST /v1/chat/completions from LibreChat           │
│  - forwards to a persistent AgentSession via                  │
│    createAgentSession() from @earendil-works/pi-coding-agent  │
│  - streams responses back as SSE in OpenAI shape              │
│  - one conversation per LibreChat thread, mapped 1:1 onto     │
│    Pi's own session persistence                               │
│  - honours GitHub Copilot / OpenAI / Anthropic auth by env    │
└───────────┬───────────────────────────────────┬───────────────┘
            │                                   │
            │ (Pi's tools)                      │ (Pi's MCP clients)
            │                                   │
┌───────────▼────────────┐        ┌─────────────▼─────────────┐
│  Aurora skill files    │        │  Aurora MCP servers        │
│  under data/pi/skills/ │        │  small stdio processes:    │
│  - aurora-know-the-box │        │  - aurora-status           │
│  - aurora-notes        │        │  - notes-read-write        │
│  - aurora-mail         │        │  - mail-send               │
│  - aurora-reminders    │        │  - reminders-set           │
│  - bruce (personal)    │        │  - calendar (later)        │
└────────────────────────┘        └─────────────┬─────────────┘
                                                │
                                (aurora_net HTTP + Aurora API token)
                                                │
                                    ┌───────────▼───────────┐
                                    │ stalwart (mail)       │
                                    │ silverbullet (notes)  │
                                    │ aurora (API + audit)  │
                                    │ radicale (calendar,   │
                                    │   packages/calendar,  │
                                    │   not yet shipped)    │
                                    │ ntfy (async out)      │
                                    └───────────────────────┘
```

Three long-lived containers on `aurora_net`: `librechat`, `pi-server`,
and the small `assistant-scheduler` (a cron that fires reminders
through `ntfy`). Their state lives under `data/assistant/` next to
every other package's data.

## Architecture — three options, one recommended

The interesting decision is **how LibreChat and Pi meet**. Three
patterns work; they trade UX polish against how much of Pi's
intelligence survives the round trip.

### Option A — Pi behind an OpenAI-compatible endpoint (recommended)

`pi-server` exposes `POST /v1/chat/completions`. LibreChat is
configured with **one custom endpoint** named "Aurora Assistant"
pointing at it. The user never picks a model from a dropdown; they
just talk to "Aurora Assistant" and it uses whatever provider Pi is
configured with (Copilot by default).

Pros:
- LibreChat is a simple client. All the "smart" stuff — tool use,
  memory, skills, MCP — happens inside Pi where it belongs.
- Model choice is Pi's, not LibreChat's; the user experience stays
  consistent regardless of whether Pi is running against Copilot,
  Claude, or Codex.
- Pi's MEMORY.md / SCRATCHPAD.md / daily logs persist across
  conversations. Same secretary's notebook every time.
- Adding Aurora awareness is one commit against Pi's skills folder,
  not two commits against LibreChat + Pi.

Cons:
- Requires a small shim (`pi-server`) — one file, maybe 200 lines
  of TS using the Pi SDK. Non-trivial but not scary.
- LibreChat's multi-model UI is wasted. That is a feature, not a bug:
  Bruce said he wants a secretary, not a model playground.

### Option B — LibreChat as the agent, Pi as an MCP tool provider

LibreChat calls Copilot directly. Pi is registered in
`librechat.yaml` as one or more MCP servers, each exposing tools that
wrap Pi's tools (bash, filesystem, memory search) as MCP tools that
LibreChat's agent can call.

Pros:
- Uses LibreChat's Agent Builder as-is.
- No Pi SDK shim.

Cons:
- LibreChat becomes the reasoning core. Pi's system prompt, its
  skill-loading rules, its memory search, its conversation model —
  all bypassed. Pi is reduced to a tool box.
- Two agent runtimes doing overlapping things: LibreChat's agent
  layer AND Pi's agent layer. Confusing to reason about.
- Bruce's request was "Pi.dev" as the agent, not "LibreChat as the
  agent with Pi bits bolted on".

### Option C — LibreChat AND Pi both talk to the same MCP servers directly

Skip the shim. Aurora's MCP servers (aurora-status, notes-read-write,
mail-send, reminders-set) run as their own containers or as stdio
subprocesses of LibreChat. LibreChat calls Copilot directly. Pi is
not involved in the web-UI path at all.

Pros:
- Simplest wiring. No shim.
- Bruce still has Pi on his laptop for coding, and the household
  agent is just LibreChat + tools.

Cons:
- Kills Bruce's stated goal ("Pi plugins that will help being a
  useful assistant"). Pi is not in the loop.
- No shared memory between "Pi as coding agent" and "Pi as household
  agent". The secretary would forget everything you told the coder.
- LibreChat's tool-calling is capable but not identical to Pi's, and
  the skill catalogue would have to be rebuilt.

### Recommendation: A

Option A gets Bruce what he asked for. The shim is small and it is
the only piece we own end-to-end. LibreChat stays a client. Pi stays
Pi. The Aurora awareness lives in Pi's skills folder, where Pi is
already good at loading and reasoning over it.

The MCP servers we build for option A are **also** loaded directly by
LibreChat as agent tools (see the diagram). That is the "belt and
braces" path: if the shim ever goes wrong, LibreChat can still talk
to the same underlying tools without Pi in the middle. This is cheap
because MCP is the transport we would have written anyway.

## Package layout

```
packages/pi/
├── manifest.yml
├── compose.yml
├── caddy.snippet
├── .env.example
├── README.md
├── pi-server/                       # the OpenAI-compatible shim
│   ├── Dockerfile                   # node:22-alpine + @earendil-works/pi-coding-agent
│   ├── package.json
│   └── src/
│       ├── index.ts                 # HTTP server, /v1/chat/completions
│       ├── pi-bridge.ts             # createAgentSession() + SSE stream
│       └── health.ts
├── librechat/
│   ├── librechat.yaml               # endpoint + MCP registration
│   └── .env.example.subset          # LIBRECHAT-specific env
├── skills/                          # bind-mounted into pi-server
│   ├── aurora-know-the-box/
│   │   ├── SKILL.md                 # inventory of packages, health idioms
│   │   └── manifest-cheatsheet.md
│   ├── aurora-notes/
│   │   └── SKILL.md                 # SilverBullet HTTP quick reference
│   ├── aurora-mail/
│   │   └── SKILL.md                 # Stalwart JMAP quick reference
│   ├── aurora-reminders/
│   │   └── SKILL.md                 # scheduler API + ntfy delivery
│   ├── aurora-calendar/
│   │   └── SKILL.md                 # CalDAV cheat sheet (once shipped)
│   └── bruce/
│       └── SKILL.md                 # personal facts, preferences,
│                                    # times he is usually free, etc.
├── mcp/                             # small stdio MCP servers
│   ├── aurora-status/
│   ├── notes-read-write/
│   ├── mail-send/
│   └── reminders-set/
└── scheduler/                       # fires reminders through ntfy
    ├── Dockerfile
    └── src/index.ts
```

State lives under `data/assistant/`:

```
data/assistant/
├── pi-sessions/            # Pi's own session persistence
├── pi-memory/              # MEMORY.md + SCRATCHPAD.md + daily/
├── librechat-mongo/        # LibreChat's Mongo store (conversations, auth)
├── librechat-meili/        # LibreChat's search index (optional)
└── scheduler.sqlite        # pending reminders
```

## The manifest

```yaml
name: assistant
title: Assistant (LibreChat + Pi)
description: |
  A household secretary that lives on this box. Read and write your
  notes, your mail, your calendar, and your reminders. Reachable in
  a browser from any device on the LAN, and pushes to your phone
  when something you asked to be reminded of arrives.
category: productivity

depends_on:
  - core                              # Caddy + Authelia + Stalwart
recommends:
  - notes                             # SilverBullet is the notes backend
  - calendar                          # once we ship it (see below)
  - privacy                           # AdGuard for *.aurora.local resolution

profiles:
  gpu: {}                             # reserved; not used at v0.1

ports: []                             # nothing published; Caddy fronts it

requires:
  min_ram_mb: 1024                    # LibreChat + Mongo + pi-server + shim
  min_disk_gb: 3                      # image + conversation history

required_env:
  # Pick ONE provider block. The wizard walks the operator through it.
  # Default: GitHub Copilot (Bruce already has a sub, no new key needed).
  - PI_MODEL_PROVIDER                 # copilot | openai | anthropic | codex
  - GITHUB_COPILOT_TOKEN              # only when provider=copilot
  # OPENAI_API_KEY, ANTHROPIC_API_KEY optional alternatives

sso:
  protect: true
  min_role: user                      # everyone signed in can talk to it
  disable_env:                        # LibreChat's own auth is neutralised
    - LIBRECHAT_ADMIN_EMAIL
    - LIBRECHAT_ADMIN_PASSWORD

vhosts:
  - {label: assistant, upstream: librechat:3080, description: The chat UI}
```

## Custom-tool + skill catalogue (v1)

Everything is scoped to what Bruce actually asked for: a secretary
for notes, dates, and household paperwork.

### Skills (Pi loads these as system-prompt context)

- **aurora-know-the-box**: what packages are installed, which
  endpoints they answer on, which are behind SSO, which are in a
  degraded state, and what the audit log looks like. Rendered from
  the manifests + `/api/state` + `/api/health` at pi-server startup
  and refreshed on a slow schedule.
- **aurora-notes**: SilverBullet's HTTP API in one page. When to use
  a tag, when to use a folder, how to link. Bruce's note style
  captured as examples.
- **aurora-mail**: Stalwart JMAP in one page. How to send from
  `assistant@$DOMAIN` (a new mailbox we provision), how to search
  Bruce's inbox on request.
- **aurora-reminders**: how to schedule a reminder (the `remind_at`
  tool), what "tomorrow morning" means to Bruce, what channels are
  available (ntfy → phone).
- **aurora-calendar**: CalDAV cheat sheet. **Blocked** until the
  `calendar` package ships; skill stub says so honestly.
- **household**: shared facts everyone benefits from. Time zones,
  household names, who does what ("Sarah handles the bins"),
  recurring events Pi should surface ("check backups on Sunday",
  "your wife's birthday is X"). Pi appends to this file when a user
  tells it something worth remembering across users. Editable from
  the UI so household members can add to it directly.

### MCP servers (Pi and LibreChat both use these)

Each is one stdio Node script + a Dockerfile. Registered in Pi via
its `mcp:` extension and in LibreChat via `mcpServers:` in
`librechat.yaml`. Both surfaces stay in sync.

- **aurora-status** — `list_packages`, `package_health`,
  `recent_findings`, `recent_audit` (readonly to `/api/*`).
- **notes-read-write** — `list_notes`, `read_note`, `append_note`,
  `create_note`, `search_notes`. Wraps SilverBullet's HTTP API.
- **mail-send** — `send_mail(to, subject, body)`, `search_mail`.
  Wraps Stalwart JMAP with an `assistant@$DOMAIN` sender.
- **reminders-set** — `remind_at(when, what, channel=ntfy)`,
  `list_reminders`, `cancel_reminder`. Writes into
  `data/assistant/scheduler.sqlite`, which the scheduler container
  reads.
- **calendar** — `list_events`, `create_event`, `next_free_slot`.
  Wraps Radicale CalDAV. **Blocked** on the `calendar` package.

### Custom Pi tools (in-process, for anything MCP is overkill for)

- `whoami_on_aurora` — reads the assistant's own service-account row
  + the current caller's role. Used to enforce "USER-role callers
  cannot delete anything".
- `explain_the_box` — a system tool that runs at conversation start
  to seed the assistant's turn with the current inventory. Called
  once per session, cached.

## Aurora integrations we need to build alongside

None of these are the assistant package itself; they are the seams
the assistant needs.

1. **A service account role in Aurora.** `ASSISTANT` — between
   `USER` and `ADMIN`. Can read `/api/*`, can call notes + mail on
   behalf of the caller, cannot mutate `/api/reset`, cannot rotate
   secrets, cannot enable/disable packages. Attributed in the audit
   log as `role=assistant, actor=<username>`.
2. **A dedicated mailbox: `assistant@$DOMAIN`**, provisioned by an
   `AssistantMailProvisionService` sibling of
   `AutheliaMailProvisionService` (C26's shape). Same idempotency
   contract. Password stored in
   `packages/pi/.env` and rotated only on operator ask.
3. **A `packages/calendar` package** — Radicale in a container, one
   CalDAV vhost `caldav.aurora.local`, DAVx5-friendly for phones.
   Not blocking on v0.1 of the assistant but the calendar skill is
   a stub until this lands.
4. **Reminders backing store** — a tiny SQLite in the assistant's
   own data dir + the scheduler container. Nothing Aurora-wide.
5. **Chat panel in the Aurora dashboard** (optional v0.2) — a Vue
   view that iframes LibreChat or, better, hits `pi-server`
   directly for a stripped-down "quick ask" flow. Not required for
   v0.1; the LibreChat vhost is enough.

## Provider choice: Copilot by default, everything else opt-in

Bruce's Copilot sub is already paid for and gives strong models
(Sonnet-4, GPT-5, o3, Gemini-2.5) at "unlimited" household use.
Default. Pi's own docs (`providers.md`) already document how to
wire it.

The `.env.example` also carries commented-out slots for `OPENAI_API_KEY`
and `ANTHROPIC_API_KEY` for people who want to pay per token or run
against a different provider. **No Ollama support in this package.**
The `packages/ai` package still exists for anyone who wants Ollama +
Open-WebUI locally; that is a different product.

## Isolation posture

The assistant container:

- Sits on `aurora_net` with the other services. Can reach `stalwart`,
  `silverbullet`, `aurora`, etc. by service name.
- Has **no docker.sock mount**. It cannot start, stop, or destroy
  containers.
- Has **no repo-root mount**. It cannot edit source, cannot rewrite
  `packages/*/compose.yml`, cannot commit.
- Reads and writes only `data/assistant/`.
- Its API token into `/api/*` is scoped to the `ASSISTANT` role.
  Destructive Aurora operations (reset, package uninstall, secret
  rotation) return 403; the UI's own confirmations are the only path.

This is the same posture every other package has, plus the
role-scoping. If Bruce ever wants an "admin assistant" that CAN
touch the docker socket, that is a second package (`assistant-admin`)
with a very different threat model — deliberately not in v0.1.

## What we build in order

Roughly one commit per bullet.

**Phase 0 — plan approved.** This doc lands on the worksheet as a
new phase (E).

**Phase 1 — pi-server shim.**

- `pi-server/` Node service, SDK-driven.
- `POST /v1/chat/completions` streaming SSE in OpenAI shape.
- One long-lived AgentSession per LibreChat thread, keyed off the
  incoming `conversation_id`.
- Provider config from env: `PI_MODEL_PROVIDER=copilot` +
  `GITHUB_COPILOT_TOKEN`.
- Unit tests: request-shape parity with what LibreChat sends, SSE
  chunk shape.

**Phase 2 — assistant compose stack.**

- `packages/pi/compose.yml` — LibreChat + Mongo (upstream
  official image) + Meilisearch (optional) + `pi-server` + scheduler.
- `librechat.yaml` declaring one custom endpoint "Aurora Assistant"
  → `http://pi-server:8080/v1`.
- `caddy.snippet` for `pi.aurora.local` with `import authelia`.
- `.env.example` + a rotate-secrets story.

**Phase 3 — the `ASSISTANT` role + service mailbox.**

- New role in `Role.java`, propagates through `AuroraProperties`,
  audit, Authelia group cascade.
- `AssistantMailProvisionService` (copy of `AutheliaMailProvisionService`,
  same idempotency).

**Phase 4 — MCP servers.**

- `aurora-status`, `notes-read-write`, `mail-send`, `reminders-set`.
- Each: stdio Node script, tiny Dockerfile, contract-tested against
  the live service (with a docker-compose test harness for CI).

**Phase 5 — skill catalogue.**

- Six skill files under `packages/pi/skills/`. Each edited
  by hand from what the live box actually knows.
- `explain_the_box` custom tool + startup context refresh.

**Phase 6 — reminders scheduler.**

- One-shot cron container reads `scheduler.sqlite`, fires ntfy at
  the due time, marks the row delivered. Uses Aurora's existing
  `NotificationsService` targets so Bruce's phone gets the push
  through the ntfy channel he already configured.

**Phase 7 — dashboard chat panel (optional).**

- A "Quick ask" view on Aurora that hits `pi-server` directly.
- Useful when the phone is elsewhere and the LibreChat UI is
  overkill for one question.

**Phase 8 — calendar package.**

- `packages/calendar` (Radicale). Not blocking; the calendar skill
  is a stub until this lands.

## Test story

- **Unit** (mvn / vitest / node --test):
  - Pi-server request/response shape parity with LibreChat's OpenAI
    client, verified against a snapshot payload.
  - Each MCP server's tool schema round-trips.
  - `AssistantMailProvisionService` idempotency (same shape test as
    `AutheliaMailProvisionServiceTests`).
- **Integration**:
  - Bring up the compose stack in the existing e2e harness.
  - Real conversation flow: ask the assistant to add a note, verify
    the SilverBullet API sees it. Ask it to schedule a reminder,
    fast-forward the scheduler clock, verify ntfy fires. Ask it
    what packages are healthy, verify the answer matches
    `/api/state`.
- **Live gate before shipping**:
  - Bruce runs the compose stack on aurora.local. Talks to it from
    his phone through `https://pi.aurora.local`. Confirms
    that reminders arrive on his phone in real time. Confirms the
    `bruce/SKILL.md` slot is editable from the UI (or from
    SilverBullet, whichever way we render it).

## Scope guards (deliberately not v1)

- **No voice.** Voice belongs in a `packages/voice` pass that wires
  Home Assistant's Assist / Piper / Whisper into `pi-server`'s HTTP
  interface. Same door, different transport. Deferred.
- **No email triage / auto-reply.** Reading mail on request is in;
  auto-replying is not. That is a much bigger trust decision.
- **No mutable Aurora config.** The assistant cannot install
  packages, cannot rotate secrets, cannot toggle SSO.
- **No cross-user memory.** Bruce's `bruce/SKILL.md` is scoped to
  Bruce; if a second household member ever signs in through
  Authelia, they get their own skill file, not Bruce's.

## Open questions for Bruce

Before code lands, three product-judgement forks:

1. **The assistant's identity.** Does it call itself "Aurora Assistant",
   "Pi", "Hermes", or something Bruce-specific? This is what shows up
   in the LibreChat model dropdown and in ntfy notifications ("From:
   Aurora"). Bruce raised "hermes" as a possible name for the
   agent — is that a preference or a "just checking"?
2. **Access control for the assistant.** Everyone signed in gets the
   same secretary and the same memory (Bruce's household model), OR
   each Authelia user gets a separate conversation history + skill
   folder (multi-tenant). v0.1 is single-tenant unless Bruce says
   otherwise; multi-tenant is a fork we should take deliberately.
3. **Calendar first or reminders first?** Reminders (ntfy scheduler)
   land in Phase 6 without needing a calendar package. Full calendar
   (Radicale + skill) is Phase 8. If Bruce wants the calendar sooner,
   we swap the order — Radicale is a small package.

## Decisions taken (2026-08-30)

The three questions in the earlier draft were answered:

- **Identity:** Pi. (Not Hermes; Bruce said "just use Pi as that's the
  agent".)
- **Access model:** Single-tenant, shared household memory. Pi
  distinguishes callers by their Authelia session but has one
  notebook.
- **Order of build:** reminders first (nfy scheduler in Phase 6),
  calendar (Radicale in Phase 8) later.
- **Testing posture:** Bruce is away for a few hours and has explicitly
  authorised an e2e nuke-and-rebuild if that's the cleanest way to
  land it. This session commits code first, verifies gates green,
  brings the assistant stack up alongside the existing box before any
  nuke, and only rebuilds from scratch if there is confidence that
  everything will come back green.

## Decision requested

(historical, now resolved.)
