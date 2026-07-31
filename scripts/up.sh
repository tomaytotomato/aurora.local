#!/usr/bin/env bash
# home.local / scripts/up.sh
#
# Bring up one or more packages. Handles the cross-package coupling
# (media's qbittorrent needs privacy's gluetun) by merging compose
# files under one project.
#
#   ./scripts/up.sh                          # everything in .state.yml (or all 4 defaults)
#   ./scripts/up.sh core privacy media       # explicit list
#   ./scripts/up.sh --torrent core privacy media
#
# Dependencies are auto-resolved from manifest.yml.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export REPO

# shellcheck source=lib/log.sh
. "$REPO/scripts/lib/log.sh"
# shellcheck source=lib/manifest.sh
. "$REPO/scripts/lib/manifest.sh"
# shellcheck source=lib/state.sh
. "$REPO/scripts/lib/state.sh"

# --------------------------------------------------------------------
# Parse args: profile flags + package names
# --------------------------------------------------------------------
profiles=()
pkgs=()
for arg in "$@"; do
  case "$arg" in
    --torrent) profiles+=(torrent) ;;
    --profile=*) profiles+=("${arg#--profile=}") ;;
    --*) die "unknown flag: $arg" ;;
    *) pkgs+=("$arg") ;;
  esac
done

# No packages given → use state, or fall back to legacy defaults.
if [[ ${#pkgs[@]} -eq 0 ]]; then
  mapfile -t pkgs < <(state_list_enabled)
  if [[ ${#pkgs[@]} -eq 0 ]]; then
    pkgs=(core privacy media storage)
    log_info "no state file; using legacy defaults: ${pkgs[*]}"
  fi
fi

# Merge profiles from state too
if state_exists; then
  while IFS= read -r pf; do
    [[ -z "$pf" ]] && continue
    local_have=0
    for p in "${profiles[@]}"; do [[ "$p" == "$pf" ]] && local_have=1; done
    [[ $local_have -eq 0 ]] && profiles+=("$pf")
  done < <(state_list_profiles)
fi

# --------------------------------------------------------------------
# Resolve deps
# --------------------------------------------------------------------
mapfile -t resolved < <(manifest_resolve_deps "${pkgs[@]}")
if [[ "${resolved[*]}" != "${pkgs[*]}" ]]; then
  log_step "resolved package set: ${resolved[*]}"
fi
pkgs=("${resolved[@]}")

# Warn on unmet recommends
for p in "${pkgs[@]}"; do
  while IFS= read -r rec; do
    [[ -z "$rec" ]] && continue
    found=0
    for x in "${pkgs[@]}"; do [[ "$x" == "$rec" ]] && found=1; done
    [[ $found -eq 0 ]] && log_warn "$p recommends '$rec' (not selected)"
  done < <(manifest_recommends "$p")
done

# --------------------------------------------------------------------
# Compose profiles
# --------------------------------------------------------------------
if [[ ${#profiles[@]} -gt 0 ]]; then
  export COMPOSE_PROFILES="$(IFS=,; echo "${profiles[*]}")"
  log_step "enabling profiles: $COMPOSE_PROFILES"
fi

# --------------------------------------------------------------------
# Shared network
# --------------------------------------------------------------------
if ! docker network inspect home_net >/dev/null 2>&1; then
  log_step "creating docker network home_net"
  docker network create home_net >/dev/null
fi

# --------------------------------------------------------------------
# Assemble compose -f flags + seed .env files
# --------------------------------------------------------------------
files=()
env_files=()
for p in "${pkgs[@]}"; do
  f="$REPO/packages/$p/compose.yml"
  [[ -f "$f" ]] || die "no compose.yml for package: $p"
  files+=(-f "$f")

  env_ex="$REPO/packages/$p/.env.example"
  env_real="$REPO/packages/$p/.env"
  if [[ -f "$env_ex" && ! -f "$env_real" ]]; then
    log_info "seeding $p/.env from .env.example (edit before restart)"
    cp "$env_ex" "$env_real"
  fi
  [[ -f "$env_real" ]] && env_files+=("$env_real")
done

# Merge per-package .env into shell env so ${VAR} substitution works
# across multi-file compose invocations.
for ef in "${env_files[@]}"; do
  # shellcheck disable=SC1090
  set -a; . "$ef"; set +a
done

# --------------------------------------------------------------------
# Up
# --------------------------------------------------------------------
log_step "bringing up: ${pkgs[*]}"
docker compose -p home "${files[@]}" pull
docker compose -p home "${files[@]}" up -d --remove-orphans
docker compose -p home "${files[@]}" ps

# --------------------------------------------------------------------
# Record in state (only if state exists — bootstrap creates it).
# --------------------------------------------------------------------
if state_exists; then
  state_set_enabled "${pkgs[@]}"
  [[ ${#profiles[@]} -gt 0 ]] && state_set_profiles "${profiles[@]}"
fi

# --------------------------------------------------------------------
# Post-up hooks
# --------------------------------------------------------------------
# Package-level seed.sh (any package can ship one)
for p in "${pkgs[@]}"; do
  seed="$REPO/packages/$p/seed.sh"
  if [[ -x "$seed" ]]; then
    log_step "running $p/seed.sh"
    "$seed" || log_warn "$p/seed.sh exited non-zero"
  fi
done

# Legacy privacy hook (until packages/privacy/seed.sh replaces it)
if [[ " ${pkgs[*]} " == *" privacy "* ]] \
    && [[ ! -x "$REPO/packages/privacy/seed.sh" ]] \
    && [[ -x "$REPO/scripts/seed-adguard.sh" ]]; then
  echo
  "$REPO/scripts/seed-adguard.sh" || true
fi

log_ok "up complete"
