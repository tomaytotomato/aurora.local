#!/usr/bin/env bash
# aurora.local / scripts/migrate-from-docker-apps.sh
#
# One-shot migration from the pre-refactor ~/docker-apps/ layout to
# ~/aurora.local/. Idempotent-ish: safe to re-run, will skip anything
# already migrated.
#
# What it does:
#   1. Stops the old ~/docker-apps stack
#   2. Moves the per-service config dirs under ../data/<svc>
#   3. Copies ~/docker-apps/.env into packages/core/.env
#   4. Brings up the new stack via scripts/up.sh
#
# Run from ~/aurora.local:
#   ./scripts/migrate-from-docker-apps.sh

set -euo pipefail

OLD="${OLD:-$HOME/docker-apps}"
NEW="${NEW:-$HOME/aurora.local}"

cd "$NEW"

if [[ ! -d "$OLD" ]]; then
  echo "no $OLD to migrate from; skipping"
  exit 0
fi

echo "==> stopping old stack in $OLD"
if [[ -f "$OLD/docker-compose.yml" ]]; then
  (cd "$OLD" && docker compose down) || true
fi

echo "==> preparing $NEW/data"
mkdir -p "$NEW/data"

# service -> where its config dir lives under docker-apps/
declare -A SERVICES=(
  [sonarr]=sonarr
  [radarr]=radarr
  [prowlarr]=prowlarr
  [bazarr]=bazarr
  [jellyseerr]=jellyseerr
  [sabnzbd]=sabnzbd
  [rdtclient]=rdtclient
  [qbittorrent]=qbittorrent
  [minidlna]=minidlna
  [homepage]=homepage/config
)

for svc in "${!SERVICES[@]}"; do
  src="$OLD/${SERVICES[$svc]}"
  dst="$NEW/data/$svc"
  if [[ -d "$src" && ! -e "$dst" ]]; then
    echo "==> moving $src -> $dst"
    mv "$src" "$dst"
  else
    echo "    skip $svc (src=$src dst=$dst)"
  fi
done

# Homepage config is special — data/homepage IS the mounted config dir,
# but we've also got packages/core/homepage/config tracked in git with
# our new opinionated defaults. Merge: prefer the new tracked configs,
# keep the runtime `logs/` from the old dir.
if [[ -d "$NEW/data/homepage" ]]; then
  if [[ -d "$NEW/data/homepage/logs" ]]; then
    echo "==> preserving homepage logs/"
    mkdir -p "$NEW/packages/core/homepage/config/logs"
    mv "$NEW/data/homepage/logs/"* "$NEW/packages/core/homepage/config/logs/" 2>/dev/null || true
  fi
  echo "==> archiving old homepage config -> data/homepage.pre-migration"
  mv "$NEW/data/homepage" "$NEW/data/homepage.pre-migration"
fi

if [[ -f "$OLD/.env" && ! -f "$NEW/packages/core/.env" ]]; then
  echo "==> copying $OLD/.env -> packages/core/.env"
  cp "$OLD/.env" "$NEW/packages/core/.env"
  # Ensure DOMAIN is set so Caddy vhosts resolve.
  grep -q '^DOMAIN=' "$NEW/packages/core/.env" || \
    echo 'DOMAIN=aurora.local' >> "$NEW/packages/core/.env"
fi

# Propagate SAMBA_PASS from old .env into packages/storage/.env if present.
if [[ -f "$OLD/.env" && ! -f "$NEW/packages/storage/.env" ]]; then
  echo "==> seeding packages/storage/.env from .env.example + SAMBA_PASS"
  cp "$NEW/packages/storage/.env.example" "$NEW/packages/storage/.env"
  if samba_pass=$(grep -E '^SAMBA_PASS=' "$OLD/.env" | cut -d= -f2-); then
    sed -i "s|^SAMBA_PASS=.*|SAMBA_PASS=${samba_pass}|" "$NEW/packages/storage/.env"
  fi
fi

echo
echo "==> migration complete. next steps:"
echo "    1) review packages/core/.env and packages/privacy/.env"
echo "    2) point your router's DHCP DNS at this box (AdGuard)"
echo "    3) ./scripts/up.sh core privacy media storage"
