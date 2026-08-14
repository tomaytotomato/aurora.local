#!/usr/bin/env bash
#
# aurora.local / dev/testbed/up.sh
#
# Brings up a Debian 12 VM and runs the full install chain inside it:
# host/site.yml via Ansible, then the core package via docker compose,
# then checks the dashboard actually answers.
#
# This exists because the development machine is macOS. bootstrap.sh,
# host/site.yml and scripts/*.sh cannot run there at all, so until now
# they were only ever linted, never executed.
#
#   ./dev/testbed/up.sh              # create if needed, sync, full install
#   ./dev/testbed/up.sh sync         # refresh the repo copy only
#   ./dev/testbed/up.sh install      # sync, then run the install chain
#   ./dev/testbed/up.sh shell        # interactive shell as bruce
#   ./dev/testbed/up.sh destroy      # delete the VM
#
set -euo pipefail

VM="${AURORA_TESTBED_VM:-aurora}"
# core is Caddy alone; the dashboard is a separate package that depends on
# it and builds the Spring Boot and Vue app from source, which is the part
# worth proving on Linux.
PACKAGES="${AURORA_TESTBED_PACKAGES:-core dashboard}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUEST_REPO="/home/bruce/aurora.local"

c_step=$'\033[1;36m'; c_ok=$'\033[1;32m'; c_bad=$'\033[1;31m'; c_off=$'\033[0m'
step() { printf '%s==>%s %s\n' "$c_step" "$c_off" "$*" >&2; }
ok()   { printf '%s  ok%s %s\n' "$c_ok" "$c_off" "$*" >&2; }
bad()  { printf '%s fail%s %s\n' "$c_bad" "$c_off" "$*" >&2; }

need_limactl() {
  command -v limactl >/dev/null 2>&1 || {
    bad "limactl not found. brew install lima"
    exit 1
  }
}

vm_exists() { limactl list --quiet 2>/dev/null | grep -qx "$VM"; }
vm_running() { [[ "$(limactl list --format '{{.Status}}' "$VM" 2>/dev/null)" == "Running" ]]; }

# Run a command in the VM as bruce, with a login shell so PATH picks up
# docker and ansible after they are installed mid-run.
as_bruce() { limactl shell "$VM" -- sudo -iu bruce bash -lc "$1"; }

cmd_create() {
  if vm_exists; then
    if vm_running; then
      ok "VM '$VM' already running"
    else
      step "starting existing VM '$VM'"
      limactl start "$VM"
      ok "started"
    fi
    return
  fi

  step "creating VM '$VM' (Debian 12, first boot downloads an image)"
  limactl start --name="$VM" --tty=false "$REPO/dev/testbed/lima.yaml"
  ok "VM created"
}

cmd_sync() {
  step "syncing repo into $GUEST_REPO"
  # The macOS home is mounted read-only at the same path inside the VM,
  # so the source path is identical on both sides. node_modules and build
  # output are excluded: 207MB of the repo is node_modules alone, and the
  # VM builds its own.
  limactl shell "$VM" -- sudo install -d -o bruce -g bruce "$GUEST_REPO"
  limactl shell "$VM" -- sudo rsync -a --delete \
    --exclude 'node_modules/' \
    --exclude 'target/' \
    --exclude 'dist/' \
    --exclude '.claude/worktrees/' \
    --chown=bruce:bruce \
    "$REPO/" "$GUEST_REPO/"
  ok "synced $(as_bruce "du -sh $GUEST_REPO | cut -f1")"
}

cmd_install() {
  step "running the full install chain (bootstrap.sh, non-interactive)"

  # HOSTNAME/DOMAIN/HOME_USER are pinned so the run is reproducible rather
  # than picking up whatever the VM happens to report. The empty line on
  # stdin answers ansible's -K become prompt; sudo is NOPASSWD, so the
  # value is irrelevant, but the prompt still has to be fed.
  local env_prefix
  env_prefix="HOMELOCAL_NONINTERACTIVE=1 ENABLE_PACKAGES='$PACKAGES' HOSTNAME=aurora"
  env_prefix="$env_prefix DOMAIN=aurora.local HOME_USER=bruce"
  env_prefix="$env_prefix HOME_TIMEZONE=Europe/London LAN_CIDR=10.0.0.0/24"

  if as_bruce "cd $GUEST_REPO && printf '\n' | $env_prefix bash ./bootstrap.sh install $PACKAGES"; then
    ok "bootstrap.sh exited 0"
  else
    bad "bootstrap.sh failed (exit $?)"
    return 1
  fi
}

cmd_verify() {
  step "verifying the box actually serves something"
  local failures=0

  local ansible_hosts
  ansible_hosts=$(as_bruce "cd $GUEST_REPO && ansible-inventory -i inventory.ini --list 2>/dev/null | grep -c ansible_host" || echo 0)
  printf '     inventory hosts: %s\n' "$ansible_hosts" >&2

  step "containers"
  as_bruce "docker ps --format '     {{.Names}}  {{.Status}}'" || true

  step "endpoints"
  # Poll rather than check once. The image finishes building well before
  # Spring Boot finishes starting, so an immediate curl reports a
  # connection failure that looks like a broken build and is not one.
  # Fed on stdin as a heredoc rather than interpolated into a command
  # string: this crosses limactl, sudo and bash, and anything with a $ in
  # it gets eaten by one of them on the way through.
  probe() {
    local name="$1" url="$2" code
    code=$(limactl shell "$VM" -- sudo -u bruce bash -s "$url" <<'SH'
url="$1"
for _ in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)
  if [ "$c" != 000 ]; then printf '%s' "$c"; exit 0; fi
  sleep 2
done
printf '000'
SH
)
    if [[ "$code" == 2* || "$code" == 3* ]]; then
      ok "$name -> HTTP $code"
    else
      bad "$name -> HTTP $code (gave up after 120s)"
      failures=$((failures + 1))
    fi
  }

  probe "dashboard 8090" "http://127.0.0.1:8090/"
  probe "caddy 80" "http://127.0.0.1/"

  if [[ $failures -eq 0 ]]; then
    ok "reachable from macOS at http://localhost:8090/ and http://localhost:8080/"
  fi
  return "$failures"
}

cmd_shell() { limactl shell "$VM" -- sudo -iu bruce; }

cmd_destroy() {
  step "deleting VM '$VM'"
  limactl stop --force "$VM" 2>/dev/null || true
  limactl delete --force "$VM"
  ok "gone"
}

need_limactl
case "${1:-all}" in
  all)      cmd_create; cmd_sync; cmd_install; cmd_verify ;;
  create)   cmd_create ;;
  sync)     cmd_create; cmd_sync ;;
  install)  cmd_create; cmd_sync; cmd_install; cmd_verify ;;
  verify)   cmd_verify ;;
  shell)    cmd_shell ;;
  destroy)  cmd_destroy ;;
  *) printf 'usage: %s [all|create|sync|install|verify|shell|destroy]\n' "$0" >&2; exit 2 ;;
esac
