#!/usr/bin/env bash
# aurora.local / scripts/restart.sh
#
# Pull one package's images and recreate its containers. Changes nothing
# about what is enrolled, and leaves every other package alone.
#
#   ./scripts/upgrade.sh media
#
# Scoped to one package on purpose: the Upgrade button lives on a single
# app's page, so updating all nineteen — including recreating the dashboard
# the click came from — would be a surprise rather than a feature.
# "Update everything" is a different job for a different page.
#
# Deliberately NOT `up.sh media`. up.sh is a converge tool: it ends with
# state_set_enabled and passes --remove-orphans, so asking it to upgrade
# one package would rewrite .state.yml to just that package and reap every
# other package's containers. See scripts/lib/compose.sh.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export REPO

# shellcheck source=lib/log.sh
. "$REPO/scripts/lib/log.sh"
# shellcheck source=lib/manifest.sh
. "$REPO/scripts/lib/manifest.sh"
# shellcheck source=lib/state.sh
. "$REPO/scripts/lib/state.sh"
# shellcheck source=lib/compose.sh
. "$REPO/scripts/lib/compose.sh"

pkg="${1:-}"
[[ -n "$pkg" ]] || die "usage: upgrade.sh PACKAGE"
manifest_exists "$pkg" || die "no such package '$pkg' (no packages/$pkg/manifest.yml)"
compose_guard_not_self "$pkg" upgrade

mapfile -t files < <(compose_enabled_files)
[[ ${#files[@]} -gt 0 ]] || die "no enabled packages with a compose.yml; nothing to upgrade"

mapfile -t services < <(compose_services_for "$pkg")
[[ ${#services[@]} -gt 0 ]] || die "package '$pkg' declares no compose services"

# Per-package .env files hold the ${VAR} values compose substitutes, and a
# multi-file invocation resolves them from the shell environment rather
# than each file's own directory.
for p in $(state_list_enabled); do
  env_real="$REPO/packages/$p/.env"
  if [[ -f "$env_real" ]]; then
    set -a
    # shellcheck source=/dev/null
    . "$env_real"
    set +a
  fi
done
if [[ -z "${DOCKER_GID:-}" ]]; then
  DOCKER_GID="$(getent group docker 2>/dev/null | cut -d: -f3 || true)"
  DOCKER_GID="${DOCKER_GID:-998}"
  export DOCKER_GID
fi

log_step "pulling images for $pkg (${services[*]})"
docker compose -p aurora "${files[@]}" pull "${services[@]}"

# No --remove-orphans: this invocation names a subset of the project's
# services, and asking compose to reap orphans while only being told about
# some of them is how you lose the containers you did not mention.
log_step "recreating $pkg"
docker compose -p aurora "${files[@]}" up -d "${services[@]}"
docker compose -p aurora "${files[@]}" ps "${services[@]}"
log_ok "upgrade complete"
