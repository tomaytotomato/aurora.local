# Iter-1 E2E results

**Branch:** `rename/aurora`
**Focus suites:** `no-cli-instructions.spec.ts`, `done-launch.spec.ts`, `wizard-happy-path.spec.ts`
**Aurora image:** `aurora-dashboard:0.1.0` rebuilt post-Dockerfile change (bash + docker-cli + docker-cli-compose installed).
**E2E project:** `aurora-e2e` on :8091 (isolated; live aurora on :8090 untouched).

## Headline numbers

Focused re-run (post fix `No shell required` → `No typing required`):

- `no-cli-instructions.spec.ts` — **26/28 pass** (2 remaining fail on `/onboarding/tls` sudo copy, deliberately out-of-scope per plan §1)
- `done-launch.spec.ts` — **2/2 pass** ✓
  - `Start services POSTs /launch and streams events` — SSE stream connects, POST returned 202 with `job_id`, `LaunchProgress` panel rendered with package rows.
  - `Done page carries no SSH / shell-script copy` — no `<pre>`, no `./scripts/up.sh`, no `scripts/down.sh`, no "SSH into the box".

Full run (no-cli + done-launch + wizard-happy-path, pre-copy-tweak baseline):

- 45 tests total on the three suites
- 30 pass
- 15 fail — breakdown:
  - **1** `/onboarding/done: no SSH/terminal/CLI in visible copy` — fixed by copy tweak; passes post-rebuild.
  - **2** `/onboarding/tls: no <pre> / shell-text` + `no shell-command patterns` — pre-existing `sudo cp …` copy on the TLS step; **out of iter-1 scope** per plan §1.
  - **1** `/onboarding/admin: no SSH/terminal/CLI` — the admin step contains banned CLI word; **out of iter-1 scope** (plan §3 non-goals: no changes to wizard steps other than Done).
  - **11** `wizard-happy-path.spec.ts` upstream red steps — all upstream wizard defects (welcome→admin nav, admin username/password selectors, domain input selector, packages `[data-package="core"]` selector, secrets copy, TLS controls, review Install label). All out of iter-1 scope; plan §4.2 explicitly waives these.

## Definition of done — plan §4 audit

| # | Criterion | Result |
|---|-----------|--------|
| 1a | `/onboarding/done: does not mention ./scripts/up.sh` | ✓ pass |
| 1b | `/onboarding/done: no <pre> or shell-text <code>` | ✓ pass |
| 1c | `/onboarding/done: no SSH/terminal/CLI in visible copy` | ✓ pass (after copy tweak) |
| 1d | `/onboarding/done: no shell-command patterns in visible copy` | ✓ pass |
| 2  | Done-page click drives POST /api/onboarding/launch → 202 + SSE + at least one package row | ✓ pass (`done-launch.spec.ts`) |
| 3  | Live aurora on `:8090` healthy through the change | ✓ pass (`docker exec aurora wget -qO- http://127.0.0.1:8090/api/health` → `{"status":"ok"}`; container reports "healthy") |
| 4  | Backend unit tests green | ✓ pass (37 tests, incl. 5 new `LaunchServiceTests`) |
| 5  | No new `<pre>` or `<code>` containing shell text | ✓ pass (removed the SSH banner and down.sh alert; `LaunchProgress.vue` uses `<div>` not `<pre>` for the log panel) |
| 6  | `docker exec aurora which bash docker` — both present | ✓ pass (`bash --version` → GNU bash 5.3.3; `docker compose version` → v2.40.3) |

## Explicitly deferred (matches plan §3)

- TLS `sudo` copy scrub (2 failing tests)
- Admin CLI-word copy scrub (1 failing test)
- Wizard-happy-path upstream red steps (11 failing tests)
- Auto-stop for deselected packages (down.sh in-app)
- Checklist grid redesign, AdGuard/media/SMB probing

## Test artefacts

- `iter-1.json` — machine-readable summary of the focused re-run
- `packages/dashboard/e2e/playwright-report/` — HTML report from the focused re-run
