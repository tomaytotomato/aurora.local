# Pi — the household assistant on aurora.local

Pi is the coding agent Bruce uses on his laptop (`@earendil-works/pi-coding-agent`), now containerised on this box and dressed up as a household secretary. LibreChat is the door; Pi's SDK does the reasoning; a small shim (`pi-server`) wires them together.

- **Web UI:** `https://pi.$DOMAIN` (behind Authelia SSO)
- **Reasoning core:** Pi (v0.84+), talking to whichever model provider you configure
- **Default provider:** GitHub Copilot (bring your own auth store)
- **State:** conversation history in `data/pi/mongo/`, provider auth in `data/pi/auth/`

## What's in the stack

Three containers, all on `aurora_net`, none published to the host:

| Container | Image | Purpose |
|---|---|---|
| `librechat` | `ghcr.io/danny-avila/librechat:v0.7.9` | The chat UI. |
| `librechat-mongo` | `mongo:7.0` | LibreChat's conversation + auth store. |
| `pi-server` | built here (`pi-server/`) | OpenAI-compatible endpoint fronting Pi's SDK. |

## Model provider

Pi's `ModelRuntime` reads `~/.pi/agent/auth.json` at boot to pick the provider. Inside the container that's `/pi-data/.pi/agent/auth.json`, which the compose file bind-mounts from `data/pi/auth/`.

### Option A — Copilot (default, recommended)

1. On your laptop where `pi` already works, copy `~/.pi/agent/auth.json` onto the box:

   ```bash
   scp ~/.pi/agent/auth.json aurora.local:aurora.local/data/pi/auth/auth.json
   chmod 600 ~/aurora.local/data/pi/auth/auth.json
   ```

2. Recreate the `pi-server` container so it picks up the new auth:

   ```bash
   docker compose -p aurora restart pi-server
   ```

3. Check `/health` inside aurora_net (`docker exec aurora sh -c 'wget -qO- http://pi-server:8080/health'`) — `modelReady` should be `true`.

### Option B — OpenAI or Anthropic

Set `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` in `packages/pi/.env` and re-run `up.sh`. Pi picks whichever provider has valid auth first; when both are set, `PI_MODEL_PROVIDER` (also in `.env`) breaks the tie.

## Skills

Skill folders under `packages/pi/skills/` are bind-mounted into `pi-server` at `/pi-data/.pi/agent/skills/`. Pi's `DefaultResourceLoader` walks that directory at startup, so adding a new skill is: create a folder, drop a `SKILL.md`, restart the container.

Ships with:

- **aurora-know-the-box** — inventory of installed packages, health check idioms, audit log shape. Refreshed on `pi-server` restart.
- **household** — shared household facts. Editable through the UI. Pi appends to this when a household member tells it something worth remembering across users.

More skill families (notes, mail, reminders, calendar) land in Phase E5 alongside the MCP servers.

## What Pi cannot do

Deliberately, at v0.1:

- No access to the host filesystem (no repo mount, no `/etc/`).
- No `docker.sock` mount. Pi cannot start, stop, or destroy containers.
- No admin scope on Aurora's own API. The forthcoming `ASSISTANT` role in E4 is between USER and ADMIN; destructive Aurora operations return 403.
- No email auto-reply. Pi can search and send mail on explicit request; it doesn't answer for you.

## Isolation from the household coding agent

The Pi in this container is **not** the Pi on Bruce's laptop. They read different memory files (`~/.pi/agent/MEMORY.md` on the laptop vs. `data/pi/pi-memory/MEMORY.md` on the box). This is on purpose: the household secretary and the coding agent are two different jobs.

If you want them to share memory (so the coding agent knows what the household agent knows), that's a follow-up conversation about a shared MEMORY store — not a v0.1 default.

## Nothing published to the host

Every port in this stack is on `aurora_net`. Reach Pi through Caddy (`pi.aurora.local`), never directly. That is the same rule every other package on this box follows.
