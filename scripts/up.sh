#!/usr/bin/env bash
# home.local / scripts/up.sh
#
# Bring up one or more packages. Handles the cross-package coupling
# (media's qbittorrent needs privacy's gluetun) by merging compose
# files under one project.
#
#   ./scripts/up.sh core privacy media storage
#   ./scripts/up.sh core                       # dashboard only

set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"

pkgs=("$@")
[[ ${#pkgs[@]} -eq 0 ]] && pkgs=(core privacy media storage)

# Compose profiles that opt in optional services. Right now the only
# profile is 'torrent' (gluetun + qbittorrent).
profiles=()
filtered=()
for arg in "${pkgs[@]}"; do
  case "$arg" in
    --torrent) profiles+=(torrent) ;;
    --*)       echo "unknown flag: $arg" >&2; exit 1 ;;
    *)         filtered+=("$arg") ;;
  esac
done
pkgs=("${filtered[@]}")
if [[ ${#profiles[@]} -gt 0 ]]; then
  export COMPOSE_PROFILES="$(IFS=,; echo "${profiles[*]}")"
  echo "==> enabling profiles: $COMPOSE_PROFILES"
fi

# Shared docker network. Idempotent.
if ! docker network inspect home_net >/dev/null 2>&1; then
  echo "==> creating docker network home_net"
  docker network create home_net >/dev/null
fi

# Assemble -f flags across every requested package.
files=()
env_files=()
for p in "${pkgs[@]}"; do
  f="$REPO/packages/$p/compose.yml"
  [[ -f "$f" ]] || { echo "no such package: $p" >&2; exit 1; }
  files+=(-f "$f")

  # Copy .env.example -> .env on first run so var substitution doesn't error.
  env_ex="$REPO/packages/$p/.env.example"
  env_real="$REPO/packages/$p/.env"
  if [[ -f "$env_ex" && ! -f "$env_real" ]]; then
    echo "==> seeding $p/.env from .env.example (edit before restart)"
    cp "$env_ex" "$env_real"
  fi
  [[ -f "$env_real" ]] && env_files+=("$env_real")
done

# Merge all per-package .env files into shell env so ${VAR} substitution
# in every compose file sees them (compose only auto-loads .env from the
# project dir; multi-file setups fall through the cracks).
for ef in "${env_files[@]}"; do
  set -a; source "$ef"; set +a
done

echo "==> bringing up: ${pkgs[*]}"
docker compose -p home "${files[@]}" pull
docker compose -p home "${files[@]}" up -d --remove-orphans
docker compose -p home "${files[@]}" ps

# Post-up seed hooks (idempotent — safe to run every time).
if [[ " ${pkgs[*]} " == *" privacy "* ]] && [[ -x "$REPO/scripts/seed-adguard.sh" ]]; then
  echo
  "$REPO/scripts/seed-adguard.sh" || true
fi
