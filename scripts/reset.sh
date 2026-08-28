#!/usr/bin/env bash
# aurora.local / scripts/reset.sh
#
# Take this box back to "just cloned": stop everything Aurora started,
# delete the state it created, and leave the repo and the host alone.
#
#   ./scripts/reset.sh              # ask first, then reset
#   ./scripts/reset.sh --yes        # no prompt (scripts, re-install loops)
#   ./scripts/reset.sh --keep-data  # keep data/, only clear enrolment + env
#
# Why this exists: there was no way back. Nothing in bootstrap.sh, the
# scripts, or the dashboard could return a box to a clean state, so
# "start over" meant a hand-rolled sequence of docker rm -f, docker volume
# prune, sudo rm -rf data/ and picking .env files out by hand — every one
# of which is exactly the kind of terminal surgery this product exists to
# remove. A consumer appliance has to have a factory reset.
#
# What it removes:
#   * every container, network and volume in the `aurora` compose project
#   * data/ (every service's state: mail, database, DNS config, backups
#     that live on this box)
#   * .state.yml, packages/*/.env, packages/*/pins.env
#
# What it keeps:
#   * the repository itself, including anything you have edited
#   * group_vars/all.yml + inventory.ini (host facts, cheap to keep and
#     annoying to re-answer). --all removes those too.
#   * everything the host role did: docker, ufw, ssh hardening, avahi.
#     Those are OS-level and undoing them belongs to the OS, not here.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export REPO

# shellcheck source=lib/log.sh
. "$REPO/scripts/lib/log.sh"

ASSUME_YES=0
KEEP_DATA=0
ALSO_HOST_CONFIG=0
for a in "$@"; do
  case "$a" in
    --yes|-y)     ASSUME_YES=1 ;;
    --keep-data)  KEEP_DATA=1 ;;
    --all)        ALSO_HOST_CONFIG=1 ;;
    -h|--help)    sed -n '2,30p' "$0"; exit 0 ;;
    *)            die "unknown arg: $a" ;;
  esac
done

# --------------------------------------------------------------------
# Say what is about to happen, in the same words the dashboard would.
# --------------------------------------------------------------------
log_step "this will reset aurora on this box"
log_info "stop and delete every container, network and volume Aurora started"
if [[ $KEEP_DATA -eq 0 ]]; then
  log_warn "delete data/ — mail, accounts, DNS settings, and anything stored on this box"
else
  log_info "keep data/ (--keep-data)"
fi
log_info "delete .state.yml and every packages/*/.env"
[[ $ALSO_HOST_CONFIG -eq 1 ]] && log_info "delete group_vars/all.yml and inventory.ini (--all)"
log_info "leave the repo, docker itself, and the firewall exactly as they are"

if [[ $ASSUME_YES -eq 0 ]]; then
  # `[[ -r /dev/tty ]]` is not enough: the device node exists inside
  # containers and under some job-control setups but cannot be opened, and
  # a failed open here would abort mid-message with a raw bash error.
  if ! { exec 3</dev/tty; } 2>/dev/null; then
    die "refusing to reset without a confirmation, and there is no terminal to ask on. Re-run with --yes if you mean it."
  fi
  printf '\nType RESET to continue: ' >&2
  read -r answer <&3 || answer=""
  exec 3<&-
  [[ "$answer" == "RESET" ]] || { log_info "nothing was changed"; exit 0; }
fi

# --------------------------------------------------------------------
# 1. Containers, networks, volumes belonging to this box's compose project.
# --------------------------------------------------------------------
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  log_step "stopping aurora's containers"
  # By project label rather than by compose file: a package that was
  # removed from the repo (or whose compose file no longer parses) still
  # has containers running, and they have to go too.
  mapfile -t ids < <(docker ps -aq --filter "label=com.docker.compose.project=aurora" 2>/dev/null || true)
  if [[ ${#ids[@]} -gt 0 ]]; then
    docker rm -f "${ids[@]}" >/dev/null
    log_ok "removed ${#ids[@]} container(s)"
  else
    log_info "no aurora containers were running"
  fi

  mapfile -t vols < <(docker volume ls -q --filter "label=com.docker.compose.project=aurora" 2>/dev/null || true)
  if [[ $KEEP_DATA -eq 0 && ${#vols[@]} -gt 0 ]]; then
    docker volume rm "${vols[@]}" >/dev/null 2>&1 || true
    log_ok "removed ${#vols[@]} volume(s)"
  fi

  docker network rm aurora_net >/dev/null 2>&1 && log_ok "removed network aurora_net" || true
else
  log_warn "docker is not available; skipping container teardown"
fi

# --------------------------------------------------------------------
# 2. On-disk state.
# --------------------------------------------------------------------
if [[ $KEEP_DATA -eq 0 && -d "$REPO/data" ]]; then
  log_step "deleting data/"
  # Containers run as assorted uids and leave root-owned trees behind, so
  # this usually needs sudo — the one place in this script that does. Try
  # without first (a box where every service ran as the operator does not
  # need it), and only escalate when the unprivileged attempt fails.
  # Testing ownership up front looked tidier and was wrong: `find` cannot
  # even read a root-owned 0700 directory, so the probe reported "no
  # root-owned files" and the plain rm failed halfway through.
  if ! rm -rf "${REPO:?}/data" 2>/dev/null; then
    sudo rm -rf "${REPO:?}/data"
  fi
  log_ok "data/ removed"
fi

log_step "clearing installation state"
rm -f "$REPO/.state.yml"
rm -f "$REPO"/packages/*/.env "$REPO"/packages/*/pins.env
rm -rf "$REPO/packages/dashboard/state"
if [[ $ALSO_HOST_CONFIG -eq 1 ]]; then
  rm -f "$REPO/group_vars/all.yml" "$REPO/inventory.ini"
fi
log_ok "state cleared"

echo >&2
log_ok "this box is back to a fresh clone."
log_info "start again with:  bash bootstrap.sh"
