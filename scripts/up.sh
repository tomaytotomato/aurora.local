#!/usr/bin/env bash
# aurora.local / scripts/up.sh
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
# shellcheck source=lib/render.sh
. "$REPO/scripts/lib/render.sh"

# --------------------------------------------------------------------
# Parse args: profile flags + package names
# --------------------------------------------------------------------
profiles=()
pkgs=()
for arg in "$@"; do
  case "$arg" in
    --torrent) profiles+=(torrent) ;;
    --zigbee)  profiles+=(zigbee) ;;
    --gpu)     profiles+=(gpu) ;;
    --profile=*) profiles+=("${arg#--profile=}") ;;
    --*) die "unknown flag: $arg" ;;
    *) pkgs+=("$arg") ;;
  esac
done

# Default AI Ollama runs on CPU. When --gpu is not requested, add the
# 'cpu' profile so packages/ai's ollama-cpu service starts. --gpu opts
# into the gpu profile instead; the two are mutually exclusive because
# both containers bind :11434.
_has_gpu=0
for p in "${profiles[@]}"; do [[ "$p" == "gpu" ]] && _has_gpu=1; done
if [[ $_has_gpu -eq 0 ]]; then
  profiles+=(cpu)
fi

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
  # shellcheck disable=SC2155
  export COMPOSE_PROFILES="$(IFS=,; echo "${profiles[*]}")"
  log_step "enabling profiles: $COMPOSE_PROFILES"
fi

# --------------------------------------------------------------------
# Shared network
# --------------------------------------------------------------------
if ! docker network inspect aurora_net >/dev/null 2>&1; then
  log_step "creating docker network aurora_net"
  docker network create aurora_net >/dev/null
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

# --------------------------------------------------------------------
# Dashboard orphan guard
# --------------------------------------------------------------------
# .state.yml can omit "dashboard" entirely (none of the onboarding
# wizard's presets add it — a frontend bug tracked separately), so
# `pkgs` above may not include it even though the dashboard's own
# container is what is running on this box right now, quite possibly
# running this very script. If dashboard's compose.yml were left out of
# the -f list, `docker compose up -d --remove-orphans` below would see a
# running "aurora" container with no matching service in the merged
# config and delete it as an orphan — the mirror image of the
# self-recreation bug the guard further down already handles.
#
# Force the file in whenever it exists and the dashboard is genuinely
# installed. "Installed" is judged by packages/dashboard/.env existing,
# the same signal every other package's seeding above relies on: forcing
# in an uninstalled dashboard's compose file would swap this crash for
# another, since AURORA_SESSION_SECRET is a hard requirement
# (`:?set in packages/dashboard/.env`) and every docker compose
# invocation below — pull, config, up — would abort before doing
# anything. On a box where the dashboard never ran, there is no
# container for --remove-orphans to mistakenly reap, so leaving it out
# entirely is correct there.
dashboard_compose="$REPO/packages/dashboard/compose.yml"
dashboard_env="$REPO/packages/dashboard/.env"
dashboard_requested=0
[[ " ${pkgs[*]} " == *" dashboard "* ]] && dashboard_requested=1

dashboard_forced=0
if [[ $dashboard_requested -eq 0 ]] && [[ -f "$dashboard_compose" ]] && [[ -f "$dashboard_env" ]]; then
  files+=(-f "$dashboard_compose")
  env_files+=("$dashboard_env")
  dashboard_forced=1
  log_step "dashboard is installed but not in the enabled set; including its compose file so --remove-orphans can't reap its running container"
fi

# A freshly-seeded .env has every secret blank (that's what .env.example
# ships). Several services (Authelia, Paperless, Kopia, ...) treat an
# empty required secret as fatal and refuse to start, so a first "up"
# would otherwise crash on exactly the files this loop just created.
# rotate-secrets.sh already knows how to tell a secret-shaped key from
# a config value; run it in --apply mode so first boot gets a real
# secret instead of a startup crash.
if [[ -x "$REPO/scripts/rotate-secrets.sh" ]]; then
  log_step "generating secrets for any newly-seeded .env files"
  "$REPO/scripts/rotate-secrets.sh" --apply || true
fi

# Merge per-package .env into shell env so ${VAR} substitution works
# across multi-file compose invocations.
for ef in "${env_files[@]}"; do
  set -a
  # shellcheck source=/dev/null
  . "$ef"
  set +a
done

# Auto-detect the docker group's gid so core/homepage can read
# /var/run/docker.sock without hard-coding a number that differs
# per distro. Fall back to 998 (Debian) if the lookup fails.
if [[ -z "${DOCKER_GID:-}" ]]; then
  DOCKER_GID="$(getent group docker 2>/dev/null | cut -d: -f3 || true)"
  DOCKER_GID="${DOCKER_GID:-998}"
  export DOCKER_GID
fi

# --------------------------------------------------------------------
# Render per-package fragments into runtime layout (caddy snippets,
# homepage services.yaml, identity users_database seed, pinned images).
# --------------------------------------------------------------------
render_all "${pkgs[@]}"

# --------------------------------------------------------------------
# Self-recreation guard
# --------------------------------------------------------------------
# LaunchService runs this script from inside the dashboard's own
# container (the onboarding wizard's Launch step) and sets
# AURORA_LAUNCHED_BY=aurora-dashboard so we can tell. Without this guard,
# `up -d` over every enabled package — which includes "dashboard" itself
# — recreates the very container the command is running in the moment
# the install step has rewritten a .env file: the process takes SIGTERM
# mid-invocation and every other package is left "Created" but never
# started.
#
# Two different call sites in the dashboard backend independently
# invented a marker for "this process is running inside the dashboard's
# own container": LaunchService sets AURORA_LAUNCHED_BY for the
# onboarding wizard's Launch step; JobService.submitCommand sets
# AURORA_INVOKED_BY for every other in-container job it runs (SnapRAID
# parity sync/scrub today, package enable/disable/update once those
# land). Both mean exactly the same thing to this script, so the guard
# below reacts to whichever is set rather than picking a winner and
# leaving the other call site free to reintroduce this exact bug under
# a name nobody's guarding against.
self_launch_marker=0
[[ "${AURORA_LAUNCHED_BY:-}" == "aurora-dashboard" ]] && self_launch_marker=1
[[ "${AURORA_INVOKED_BY:-}" == "aurora-dashboard" ]] && self_launch_marker=1

# Fix: keep every -f file exactly as assembled above (dropping the
# dashboard's own would make --remove-orphans below treat its running
# container as an orphan and delete it, which is worse than the bug),
# but hand `up -d` an explicit list of every service EXCEPT the
# dashboard's own instead of no arguments. Compose then only creates,
# recreates or starts those services; the dashboard's own container is
# never touched, regardless of whether its config changed.
#
# Two independent reasons land us in that same "exclude the dashboard's
# own services" spot, so one flag covers both:
#   - dashboard_forced: the dashboard orphan guard above pulled its
#     compose file in purely so --remove-orphans doesn't reap it, even
#     though nobody asked for the dashboard as part of this launch.
#     Never start or recreate it in that case, self-launched or not.
#   - self_launch_marker + dashboard genuinely requested: the existing
#     self-recreation guard — don't recreate the container this process
#     is running in mid-launch.
# A host operator explicitly bringing the dashboard up, not running from
# inside its own container, hits neither condition and gets ordinary
# recreate semantics, same as before.
restrict_dashboard_services=0
if [[ $dashboard_forced -eq 1 ]]; then
  restrict_dashboard_services=1
elif [[ $self_launch_marker -eq 1 ]] && [[ $dashboard_requested -eq 1 ]]; then
  restrict_dashboard_services=1
fi

up_target_services=()
self_launch=0
if [[ $restrict_dashboard_services -eq 1 ]]; then
  self_launch=1
  self_compose="$dashboard_compose"
  mapfile -t self_services < <(docker compose -f "$self_compose" config --services)
  mapfile -t all_services  < <(docker compose -p aurora "${files[@]}" config --services)
  for svc in "${all_services[@]}"; do
    is_self=0
    for s in "${self_services[@]}"; do [[ "$svc" == "$s" ]] && is_self=1 && break; done
    [[ $is_self -eq 0 ]] && up_target_services+=("$svc")
  done
  if [[ $dashboard_forced -eq 1 ]]; then
    log_step "excluding dashboard's own service(s) [${self_services[*]}] from 'up -d' (installed but not part of this launch)"
  else
    log_step "self-launch guard: excluding dashboard's own service(s) [${self_services[*]}] from 'up -d' (invoked from inside its own container)"
  fi

  # A recreate interrupted this way leaves compose's rename-then-remove
  # dance half-done: the old container is renamed out of the way but
  # never cleaned up. That's debris, not a blocker — the name is free
  # again for the next recreate — and an install script auto-removing
  # containers on every run is a worse failure mode than leaving one
  # around, so we only surface it. Only relevant when a recreate could
  # actually have been interrupted, i.e. the self-launch marker fired.
  if [[ $self_launch_marker -eq 1 ]]; then
    mapfile -t stray_containers < <(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E '_aurora$' || true)
    if [[ ${#stray_containers[@]} -gt 0 ]]; then
      log_warn "found stray renamed container(s) from a previous interrupted recreate: ${stray_containers[*]} (safe to 'docker rm' once you've confirmed nothing needs them)"
    fi
  fi
fi

# --------------------------------------------------------------------
# Up
# --------------------------------------------------------------------
log_step "bringing up: ${pkgs[*]}"
docker compose -p aurora "${files[@]}" pull
if [[ $self_launch -eq 1 ]]; then
  if [[ ${#up_target_services[@]} -gt 0 ]]; then
    docker compose -p aurora "${files[@]}" up -d --remove-orphans "${up_target_services[@]}"
  elif [[ $dashboard_forced -eq 1 ]]; then
    log_info "dashboard is the only compose file in play and it isn't part of this launch; skipping 'up -d'"
  else
    log_info "self-launch guard: nothing besides the dashboard itself to bring up; skipping 'up -d'"
  fi
else
  docker compose -p aurora "${files[@]}" up -d --remove-orphans
fi
docker compose -p aurora "${files[@]}" ps

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
