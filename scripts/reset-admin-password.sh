#!/usr/bin/env bash
# aurora.local / scripts/reset-admin-password.sh
#
# Break-glass recovery for a lost admin username or password. There is
# no other way back in: the dashboard has no "forgot password" flow by
# design (v0.1 is a single-box homelab admin plane, not a multi-tenant
# service with an email provider behind it), so this is the supported
# path when the owner forgets what they set during onboarding.
#
# Usage:
#   ./scripts/reset-admin-password.sh list        # show every user: id, username, role
#   ./scripts/reset-admin-password.sh <username>  # interactively set a new password
#   ./scripts/reset-admin-password.sh -h|--help
#
# Forgotten username, not just password:
#   Run `list` first. It prints every row in admin_user (id, username,
#   role, created) and nothing else — no password hashes.
#
# More than one admin:
#   `list` shows all of them; pick whichever username you want to reset.
#   Resetting a password never changes role or count, so the "keep at
#   least one admin" invariant enforced by UsersService (role changes /
#   deletes only) never comes into play here.
#
# Zero users at all (admin_user table empty):
#   Not a recovery case for this script — the dashboard's own onboarding
#   wizard creates the first admin from scratch the next time anyone
#   visits it. `list` says this in plain words instead of printing
#   nothing.
#
# ---------------------------------------------------------------------
# Authorisation model — read this before objecting to the lack of an
# old-password prompt
# ---------------------------------------------------------------------
# This script does not ask for the password you're trying to recover.
# That would be theatre: anyone who can run this script already has a
# shell on the box (or SSH to it), which means `docker exec` into the
# aurora container, read/write access to its bind-mounted repo, and the
# ability to read every secret in packages/*/.env directly. None of
# that is gated on the dashboard's own login. Shell access to the box
# IS the authorisation — the same assumption scripts/rotate-secrets.sh
# and scripts/backup.sh already make.
#
# What keeps this from being reachable any other way: the logic lives
# entirely inside the aurora.jar, dispatched from a command-line check
# in AuroraApplication.main() BEFORE Spring Boot (and therefore every
# HTTP route, including the onboarding wizard) starts. There is no
# controller, no endpoint, nothing the dashboard frontend or API can
# invoke — only `java -jar aurora.jar reset-admin-password ...` from
# inside the container reaches it.
#
# ---------------------------------------------------------------------
# How the database is reached
# ---------------------------------------------------------------------
# /data/aurora.db lives on the named Docker volume `aurora_data`, not a
# bind mount, and the aurora image has no `sqlite3` binary (it's a bare
# eclipse-temurin JRE). Rather than copy the file out and hand-edit it
# with a throwaway container (which is what led to this script existing
# — see docs/OPERATIONS.md), this shells into the SAME jar that's
# already running the app: `java -jar aurora.jar reset-admin-password`
# opens the SQLite file with the exact JDBC driver, repo class, and
# BCryptPasswordEncoder cost the app itself uses for login, so the
# written hash is guaranteed to verify.
#
# Two ways to reach the jar, tried in order:
#   1. Container running:   `docker exec` into it directly.
#   2. Container stopped (but not removed): a short-lived helper
#      container from the same image, `--volumes-from` the stopped one
#      so it sees the same /data without needing to know the volume
#      name or image tag by hand.
# If the container has been `docker rm`'d entirely, neither works —
# see docs/OPERATIONS.md for the manual `docker run -v aurora_data:/data`
# fallback.
#
# ---------------------------------------------------------------------
# Does the container need restarting afterwards?
# ---------------------------------------------------------------------
# No. AuthController.login() re-reads the row via AdminUserRepo on every
# request — there is no cache of the password hash anywhere in the JVM.
# The very next login attempt sees the new hash. An already-logged-in
# session stays valid until it's logged out or expires, same as any
# other password change; that's normal, not a bug this script needs to
# work around.

set -euo pipefail

# shellcheck source=lib/ops.sh
. "$(dirname "$0")/lib/ops.sh"

require_cmd docker

CONTAINER="aurora"

usage() { sed -n '2,29p' "$0"; }

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  "")        err "usage: $0 <list|username> — run with --help for details"; exit 1 ;;
esac
MODE="$1"

# --------------------------------------------------------------------
# run_cli <args...>  — run `reset-admin-password <args...>` inside the
# aurora jar, whichever way is available. stdin is passed through
# untouched so a password piped in by the caller reaches the JVM
# without ever becoming a command-line argument.
# --------------------------------------------------------------------
run_cli() {
  if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" == "true" ]]; then
    docker exec -i "$CONTAINER" java -jar /app/aurora.jar reset-admin-password "$@"
    return $?
  fi

  if docker inspect "$CONTAINER" >/dev/null 2>&1; then
    warn "$CONTAINER exists but is not running; using a short-lived helper container against its volume"
    local image
    image="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
    docker run --rm -i --volumes-from "$CONTAINER" \
      -e AURORA_DB_PATH=/data/aurora.db \
      "$image" reset-admin-password "$@"
    return $?
  fi

  die "no '$CONTAINER' container found (running or stopped). Bring the dashboard package up first: ./scripts/up.sh dashboard — or see docs/OPERATIONS.md for the manual docker-run fallback"
}

if [[ "$MODE" == "list" ]]; then
  run_cli list
  exit $?
fi

USERNAME="$MODE"
log "resetting password for '$USERNAME'"
dim "  no old-password check needed — shell access to this box is the authorisation (see the header of this script for why)"

if [[ ! -t 0 ]]; then
  die "stdin is not a terminal — run this interactively so the password can be entered without echoing"
fi

read -rs -p "New password (min 12 chars, hidden): " new_password
echo
read -rs -p "Confirm new password: " confirm_password
echo

if [[ "$new_password" != "$confirm_password" ]]; then
  die "passwords did not match"
fi
if (( ${#new_password} < 12 )); then
  die "password must be at least 12 characters"
fi

# Piped via stdin only — never as an argument (would show in `ps`) and
# never via an env var (would show in `docker inspect`/`/proc/*/environ`
# to anyone who can already read those, which per the authorisation
# note above is not a new exposure, but stdin is simplest and cleanest).
if printf '%s\n' "$new_password" | run_cli reset "$USERNAME"; then
  ok "password reset for '$USERNAME'"
else
  status=$?
  err "reset failed (exit $status) — see output above"
  exit "$status"
fi
