#!/usr/bin/env bash
# scripts/lib/compose.sh
#
# Assemble compose invocations that touch ONE package without disturbing
# the rest of the box.
#
# Why this exists separately from scripts/up.sh's own assembly: up.sh is a
# converge tool. Its last act is `state_set_enabled "${pkgs[@]}"` and it
# passes `--remove-orphans`, so `up.sh media` means "make this box be
# core+media", which rewrites .state.yml and reaps every other package's
# containers. That is the right behaviour for install and for launch, and
# completely wrong for "restart this one app".
#
# The functions here keep every enabled package's -f file in the
# invocation — dropping them would make compose treat the others as
# orphans, and would break the relative paths in compose.yml, which
# resolve against the FIRST -f file's directory — while restricting the
# services acted upon to the one package. Same technique as up.sh's
# self-launch guard.
#
# Deliberately does NOT touch .state.yml. Restarting or upgrading a
# package changes nothing about what is enrolled.

[[ -n "${_AURORA_COMPOSE_SH:-}" ]] && return 0
_AURORA_COMPOSE_SH=1

# shellcheck source=log.sh
[[ -n "${_HOMELOCAL_LOG_SH:-}" ]] || . "${BASH_SOURCE%/*}/log.sh"
# shellcheck source=manifest.sh
[[ -n "${_HOMELOCAL_MANIFEST_SH:-}" ]] || . "${BASH_SOURCE%/*}/manifest.sh"
# shellcheck source=state.sh
[[ -n "${_HOMELOCAL_STATE_SH:-}" ]] || . "${BASH_SOURCE%/*}/state.sh"

# compose_enabled_files -> "-f path -f path ..." for every enabled package
# that still exists in this repo, one token per line.
#
# Retired packages are skipped rather than fatal, for the same reason
# up.sh skips them: one dangling .state.yml entry should not make every
# other package unmanageable.
compose_enabled_files() {
  local -a pkgs=()
  mapfile -t pkgs < <(state_list_enabled)
  [[ ${#pkgs[@]} -eq 0 ]] && return 0
  mapfile -t pkgs < <(manifest_filter_known "${pkgs[@]}")

  local p f
  for p in "${pkgs[@]}"; do
    f="$REPO/packages/$p/compose.yml"
    [[ -f "$f" ]] || continue
    printf -- '-f\n%s\n' "$f"
  done
}

# compose_services_for PKG -> the service names that package declares,
# one per line. Asks compose rather than parsing YAML, so profiles and
# extends are resolved the same way the real invocation will resolve them.
compose_services_for() {
  local pkg="$1"
  local f="$REPO/packages/$pkg/compose.yml"
  [[ -f "$f" ]] || die "no compose.yml for package: $pkg"
  docker compose -f "$f" config --services
}

# compose_guard_not_self PKG VERB
#
# Refuse to act on the dashboard's own package from inside the dashboard's
# own container. Recreating or restarting the container that is running
# this script takes SIGTERM mid-invocation and leaves the job log
# truncated with no record of what happened — the same fault the
# self-launch guard in up.sh exists to prevent, except here there is
# nothing useful to fall back to: the whole point of the command IS the
# dashboard.
compose_guard_not_self() {
  local pkg="$1" verb="$2"
  [[ "$pkg" != "dashboard" ]] && return 0
  if [[ "${AURORA_LAUNCHED_BY:-}" == "aurora-dashboard" ]] \
      || [[ "${AURORA_INVOKED_BY:-}" == "aurora-dashboard" ]]; then
    die "refusing to $verb the dashboard from inside its own container — run this from the host: ./scripts/$verb.sh dashboard"
  fi
  return 0
}
