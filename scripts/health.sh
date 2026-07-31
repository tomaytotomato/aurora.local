#!/usr/bin/env bash
# home.local / scripts/health.sh
#
# Report on the running state of the enabled packages: docker container
# health, uptime, listening ports, and HTTP status of each package's
# Caddy vhost.
#
# Exit 0 if everything's happy, non-zero otherwise.
#
# Usage:
#   ./scripts/health.sh           # all enabled packages
#   ./scripts/health.sh media     # just one
#   ./scripts/health.sh --no-http # skip HTTP probes

set -euo pipefail

# shellcheck source=lib/ops.sh
. "$(dirname "$0")/lib/ops.sh"

require_cmd docker

NO_HTTP=0
pkgs=()
for a in "$@"; do
  case "$a" in
    --no-http)  NO_HTTP=1 ;;
    -h|--help)  sed -n '2,15p' "$0"; exit 0 ;;
    -*)         die "unknown flag: $a" ;;
    *)          pkgs+=("$a") ;;
  esac
done
if (( ${#pkgs[@]} == 0 )); then
  mapfile -t pkgs < <(list_enabled_packages)
fi

load_group_vars
home_domain="${home_domain:-${HOME_DOMAIN:-home.local}}"

FAILS=0

# ---- containers ------------------------------------------------------
log "containers (project=home)"
# Format: name status health
fmt='table {{.Name}}\t{{.Status}}\t{{.State}}'
if ! docker compose -p home ps --format "$fmt" 2>/dev/null; then
  warn "docker compose -p home ps failed (project not up?)"
fi
echo

# Any unhealthy?
while IFS=$'\t' read -r name state health; do
  [[ -z "$name" ]] && continue
  case "$health" in
    healthy|"")  ;;
    starting)    dim "  $name: starting" ;;
    unhealthy)   err "$name unhealthy"; FAILS=$((FAILS+1)) ;;
    *)           warn "$name health=$health" ;;
  esac
  case "$state" in
    running|"")  ;;
    exited|dead) err "$name state=$state"; FAILS=$((FAILS+1)) ;;
  esac
done < <(docker ps -a --filter "label=com.docker.compose.project=home" \
           --format '{{.Names}}\t{{.State}}\t{{.Status}}' 2>/dev/null)

# ---- HTTP probes -----------------------------------------------------
if (( ! NO_HTTP )) && has_cmd curl; then
  log "HTTP probes ($home_domain)"
  for pkg in "${pkgs[@]}"; do
    # crude: try https://<pkg>.$home_domain/ ; if it 000s, fall back to http
    for scheme in https http; do
      url="$scheme://$pkg.$home_domain/"
      code=$(curl -sk -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || echo 000)
      if [[ "$code" != "000" ]]; then
        if [[ "$code" =~ ^(2|3) ]]; then
          ok "$url -> $code"
        else
          warn "$url -> $code"
        fi
        break
      fi
      if [[ "$scheme" == "http" ]]; then
        dim "  $pkg.$home_domain: no vhost (skipped)"
      fi
    done
  done
elif (( ! NO_HTTP )); then
  warn "curl not available; skipping HTTP probes"
fi

# ---- summary ---------------------------------------------------------
echo
if (( FAILS == 0 )); then
  ok "health: OK"
else
  err "health: $FAILS problem(s)"
  exit 1
fi
