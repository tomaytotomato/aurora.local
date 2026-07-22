#!/usr/bin/env bash
# home.local / scripts/down.sh
#
# Stop and remove one or more packages. Volumes are preserved.
#
#   ./scripts/down.sh                # all packages
#   ./scripts/down.sh media           # just media

set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"

pkgs=("$@")
[[ ${#pkgs[@]} -eq 0 ]] && pkgs=(core privacy media storage)

files=()
for p in "${pkgs[@]}"; do
  f="$REPO/packages/$p/compose.yml"
  [[ -f "$f" ]] || { echo "no such package: $p" >&2; exit 1; }
  files+=(-f "$f")
done

echo "==> stopping: ${pkgs[*]}"
docker compose -p home "${files[@]}" down
