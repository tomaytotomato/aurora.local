# scripts/lib

Reusable bash modules sourced by `bootstrap.sh`, `scripts/up.sh`,
`scripts/down.sh`, `scripts/status.sh`, etc.

Every file here is:

- POSIX-ish bash 4+, `set -euo pipefail` compatible
- side-effect free at source time (only defines functions and readonly
  vars — `ops.sh` is the one exception, see below)

## Files

| File          | Namespace    | Purpose                                                                 |
|---------------|--------------|-------------------------------------------------------------------------|
| `log.sh`      | `log_*`      | Colour-aware `log_info/ok/warn/err` + `log_step`, plus `die`. TTY detection. |
| `prompt.sh`   | `prompt_*`   | `whiptail`-backed `menu`, `checklist`, `inputbox`, `yesno`. Falls back to plain `read` in headless mode. |
| `manifest.sh` | `manifest_*` | Parse `packages/*/manifest.yml` (yq if present, else python3+PyYAML). Also `manifest_exists` / `manifest_filter_known`, which answer "is this package still in the repo" without dying. |
| `state.sh`    | `state_*`    | Read/write `.state.yml` at the repo root.                               |
| `render.sh`   | `render_*`   | Stitch per-package fragments into the runtime layout: caddy snippets, the identity seed, pinned images. `render_all` is the one call `up.sh` makes. |
| `ops.sh`      | *unprefixed* | Bare `log ok warn err die dim` for the operator-facing scripts (`doctor.sh`, `health.sh`, …), plus `has_cmd`, `load_group_vars` and friends. |

## Conventions

Two sourcing styles exist, and both are correct for their callers:

- **`. "$REPO/scripts/lib/log.sh"`** — the caller sets `REPO` first. Used
  by `up.sh`, `down.sh`, `bootstrap.sh` and anything that already knows
  the repo root.
- **`. "$(dirname "$0")/lib/ops.sh"`** — `ops.sh` infers `REPO` itself
  (`ops.sh:22`, honouring an existing value if set), so operator scripts
  can source it with no preamble.

Never execute a module directly.

### One trap worth knowing

`ops.sh` defines **bare** `log ok warn err die dim`; `log.sh` defines
**prefixed** `log_*` — but both define `die`, with different behaviour.
`ops.sh`'s header says the two "may be sourced together", and if that ever
happens the second one sourced silently wins. No script does it today.
Prefer one or the other per script, and if you genuinely need both, be
deliberate about the order.
