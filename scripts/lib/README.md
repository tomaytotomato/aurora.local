# scripts/lib

Reusable bash modules sourced by `bootstrap.sh`, `scripts/up.sh`,
`scripts/down.sh`, `scripts/status.sh`, etc.

Every file here is:

- POSIX-ish bash 4+, `set -euo pipefail` compatible
- side-effect free at source time (only defines functions and readonly vars)
- prefixed with a namespace: `log_*`, `prompt_*`, `manifest_*`, `state_*`

## Files

| File          | Namespace   | Purpose                                                                 |
|---------------|-------------|-------------------------------------------------------------------------|
| `log.sh`      | `log_*`     | Color-aware `log_info/warn/err/die` + `log_step`. TTY detection.        |
| `prompt.sh`   | `prompt_*`  | `whiptail`-backed `menu`, `checklist`, `inputbox`, `yesno`. Falls back to plain `read` in headless mode. |
| `manifest.sh` | `manifest_*`| Parse `packages/*/manifest.yml` (yq if present, else python3+PyYAML).   |
| `state.sh`    | `state_*`   | Read/write `.state.yml` at the repo root.                               |

## Conventions

- Source with: `. "$REPO/scripts/lib/log.sh"` — never execute directly.
- Each module can be sourced standalone; `manifest.sh` and `state.sh` require `log.sh`.
- Callers must set `REPO` (absolute path to repo root) before sourcing.
