#!/usr/bin/env bash
# aurora.local / scripts/down.sh
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

# --------------------------------------------------------------------
# Self-recreation guard
# --------------------------------------------------------------------
# Mirrors scripts/up.sh: nothing calls this script from inside the
# dashboard's own container today, but if a future "disable package"
# flow ever does, tearing down every enabled package including the
# dashboard's own would SIGTERM the very process running
# `docker compose down` before it finishes stopping the rest.
#
# Two different call sites in the dashboard backend independently
# invented a marker for "this process is running inside the dashboard's
# own container": LaunchService sets AURORA_LAUNCHED_BY for the
# onboarding wizard's Launch step; JobService.submitCommand sets
# AURORA_INVOKED_BY for every other in-container job it runs (SnapRAID
# parity sync/scrub today, package enable/disable/update once those
# land — the most likely future callers of THIS script). Both mean
# exactly the same thing here, so the guard reacts to whichever is set
# rather than picking a winner and leaving the other call site free to
# reintroduce this exact bug under a name nobody's guarding against.
self_launch_marker=0
[[ "${AURORA_LAUNCHED_BY:-}" == "aurora-dashboard" ]] && self_launch_marker=1
[[ "${AURORA_INVOKED_BY:-}" == "aurora-dashboard" ]] && self_launch_marker=1

# `down` accepts explicit SERVICE arguments in this compose version, so
# the fix is the same shape as up.sh: keep every -f file, target every
# service except the dashboard's own. Unlike up.sh this invocation does
# not pass --remove-orphans, so simply not naming the dashboard's
# service is enough — it is not at risk of being treated as an orphan.
down_target_services=()
self_launch=0
if [[ $self_launch_marker -eq 1 ]] && [[ " ${pkgs[*]} " == *" dashboard "* ]]; then
  self_launch=1
  self_compose="$REPO/packages/dashboard/compose.yml"
  mapfile -t self_services < <(docker compose -f "$self_compose" config --services)
  mapfile -t all_services  < <(docker compose -p aurora "${files[@]}" config --services)
  for svc in "${all_services[@]}"; do
    is_self=0
    for s in "${self_services[@]}"; do [[ "$svc" == "$s" ]] && is_self=1 && break; done
    [[ $is_self -eq 0 ]] && down_target_services+=("$svc")
  done
  log_step "self-launch guard: excluding dashboard's own service(s) [${self_services[*]}] from 'down' (invoked from inside its own container); stop it with 'down.sh dashboard' from the host instead"
fi

log_step "stopping: ${pkgs[*]}"
if [[ $self_launch -eq 1 ]]; then
  if [[ ${#down_target_services[@]} -gt 0 ]]; then
    docker compose -p aurora "${files[@]}" down "${down_target_services[@]}"
  else
    log_info "self-launch guard: nothing besides the dashboard itself to stop; skipping 'down'"
  fi
else
  docker compose -p aurora "${files[@]}" down
fi

log_ok "down complete"
