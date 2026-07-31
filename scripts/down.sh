#!/usr/bin/env bash
# home.local / scripts/down.sh
#
# Stop and remove one or more packages. Volumes are preserved.
#
#   ./scripts/down.sh                # everything currently enabled (or the 4 defaults)
#   ./scripts/down.sh media          # just media

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export REPO

# shellcheck source=lib/log.sh
. "$REPO/scripts/lib/log.sh"
# shellcheck source=lib/manifest.sh
. "$REPO/scripts/lib/manifest.sh"
# shellcheck source=lib/state.sh
. "$REPO/scripts/lib/state.sh"

pkgs=("$@")
if [[ ${#pkgs[@]} -eq 0 ]]; then
  mapfile -t pkgs < <(state_list_enabled)
  [[ ${#pkgs[@]} -eq 0 ]] && pkgs=(core privacy media storage)
fi

files=()
for p in "${pkgs[@]}"; do
  f="$REPO/packages/$p/compose.yml"
  [[ -f "$f" ]] || die "no compose.yml for package: $p"
  files+=(-f "$f")
done

log_step "stopping: ${pkgs[*]}"
docker compose -p home "${files[@]}" down

log_ok "down complete"
