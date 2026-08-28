#!/usr/bin/env bash
# aurora.local / bootstrap.sh
#
# Non-interactive installer for an aurora.local server (Phase 1 base setup).
#
# Modes:
#   bash bootstrap.sh                          # base setup: core + dashboard
#   ENABLE_PACKAGES="core media" bootstrap.sh  # override the bring-up set
#   ./bootstrap.sh list                        # list all available packages
#   ./bootstrap.sh status                      # show enabled + health
#   ./bootstrap.sh add   <pkg> [<pkg>...]      # enable + up
#   ./bootstrap.sh remove <pkg> [<pkg>...]     # down + disable
#   ./bootstrap.sh install [<pkg>...]          # full install (default)
#   ./bootstrap.sh --help
#
# Curl-pipeable one-liner:
#   curl -fsSL https://raw.githubusercontent.com/tomaytotomato/aurora.local/main/bootstrap.sh | bash

set -euo pipefail

# --------------------------------------------------------------------
# Self-clone when piped through curl (no repo on disk yet).
# --------------------------------------------------------------------
REPO_URL="${REPO_URL:-https://github.com/tomaytotomato/aurora.local.git}"
REPO_DIR="${REPO_DIR:-$HOME/aurora.local}"

# Detect "running from a pipe" — BASH_SOURCE[0] is empty or absent.
if [[ -z "${BASH_SOURCE[0]:-}" || "${BASH_SOURCE[0]}" == "bash" || "${BASH_SOURCE[0]}" == "-" ]]; then
  echo "==> cloning $REPO_URL -> $REPO_DIR"
  command -v git >/dev/null 2>&1 || {
    sudo apt-get update -qq && sudo apt-get install -y -qq git
  }
  if [[ ! -d "$REPO_DIR/.git" ]]; then
    git clone "$REPO_URL" "$REPO_DIR"
  fi
  cd "$REPO_DIR"
  exec bash ./bootstrap.sh "$@"
fi

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REPO

# shellcheck source=scripts/lib/log.sh
. "$REPO/scripts/lib/log.sh"
# shellcheck source=scripts/lib/prompt.sh
. "$REPO/scripts/lib/prompt.sh"

BOOTSTRAP_VERSION=1
export BOOTSTRAP_VERSION

# --------------------------------------------------------------------
# CLI parsing
# --------------------------------------------------------------------
usage() {
  cat <<EOF
aurora.local bootstrap v$BOOTSTRAP_VERSION

Usage:
  bootstrap.sh [install [PKG...]]   Base setup (non-interactive). Brings up
                                    core + dashboard, then open the web wizard.
  bootstrap.sh add PKG [PKG...]     Enable and start additional packages.
  bootstrap.sh remove PKG [PKG...]  Stop and disable packages.
  bootstrap.sh list                 List all packages available in this repo.
  bootstrap.sh status               Show current state + container health.
  bootstrap.sh --help               This message.

Environment:
  ENABLE_PACKAGES="core privacy"    Override the first-run bring-up set.
  HOMELOCAL_NONINTERACTIVE=1        (No-op; setup is always non-interactive.)
  DOMAIN=aurora.local            Domain used for Caddy vhosts.
  HOME_TIMEZONE=Europe/London
  HOME_USER=$USER                   User owning bind-mounts.
  LAN_CIDR=192.168.0.0/24           Used for UFW rules.
  LAN_IP=auto                       Address AdGuard binds DNS to.
  REPO_URL / REPO_DIR               Override clone source/target.
  FORCE=1                           Overwrite existing inventory.ini / group_vars/all.yml.
EOF
}

CMD="install"
if [[ $# -gt 0 ]]; then
  case "$1" in
    install|add|remove|list|status) CMD="$1"; shift ;;
    -h|--help|help) usage; exit 0 ;;
    -*) log_err "unknown flag: $1"; usage; exit 2 ;;
    *) ;;  # bare package names -> install
  esac
fi

# Lazy-load libs that need manifest/state
. "$REPO/scripts/lib/manifest.sh"
. "$REPO/scripts/lib/state.sh"
# shellcheck source=scripts/lib/net.sh
. "$REPO/scripts/lib/net.sh"

# --------------------------------------------------------------------
# Prereqs
# --------------------------------------------------------------------

_ensure_yq() {
  if command -v yq >/dev/null 2>&1 && yq --version 2>/dev/null | grep -qE 'version v?4'; then
    return 0
  fi
  log_step "installing yq (mikefarah v4) to /usr/local/bin"
  local arch
  case "$(uname -m)" in
    x86_64|amd64) arch="amd64" ;;
    aarch64|arm64) arch="arm64" ;;
    armv7l) arch="arm" ;;
    *) log_warn "unsupported arch $(uname -m) for yq; falling back to python3+PyYAML"; return 0 ;;
  esac
  local url="https://github.com/mikefarah/yq/releases/latest/download/yq_linux_${arch}"
  local tmp; tmp=$(mktemp)
  if curl -fsSL -o "$tmp" "$url"; then
    sudo install -m 0755 "$tmp" /usr/local/bin/yq
    rm -f "$tmp"
    log_ok "yq $(yq --version)"
  else
    log_warn "yq download failed; will use python3+PyYAML"
    rm -f "$tmp"
  fi
}

_ensure_prereqs() {
  log_step "checking prerequisites"
  local missing=()
  for c in curl git python3; do
    command -v "$c" >/dev/null 2>&1 || missing+=("$c")
  done
  # python3-yaml (fallback if yq download fails)
  python3 -c 'import yaml' 2>/dev/null || missing+=("python3-yaml")
  if [[ ${#missing[@]} -gt 0 ]]; then
    log_step "installing: ${missing[*]}"
    sudo apt-get update -qq
    # translate synthetic names
    local pkgs=()
    for m in "${missing[@]}"; do
      case "$m" in
        python3-yaml) pkgs+=("python3-yaml") ;;
        *)            pkgs+=("$m") ;;
      esac
    done
    sudo apt-get install -y -qq "${pkgs[@]}"
  fi
  _ensure_yq
}

# --------------------------------------------------------------------
# Package discovery / dep resolution
# --------------------------------------------------------------------

_all_packages() { manifest_list_packages; }

# Resolve a request set into an ordered list of packages, adding hard
# deps transitively. Warns on unmet recommends.
_resolve_selection() {
  local -a requested=("$@")
  local -A req_set=()
  for p in "${requested[@]}"; do req_set["$p"]=1; done

  # core is always implicit
  req_set["core"]=1

  local -a full
  # shellcheck disable=SC2207
  full=($(manifest_resolve_deps "${!req_set[@]}"))

  # warn about unmet recommends
  local p rec
  for p in "${full[@]}"; do
    while IFS= read -r rec; do
      [[ -z "$rec" ]] && continue
      if [[ -z "${req_set[$rec]:-}" ]]; then
        # Is it in full (i.e. resolved via someone else)?
        local found=0 f
        for f in "${full[@]}"; do [[ "$f" == "$rec" ]] && found=1; done
        [[ $found -eq 0 ]] && log_warn "$p recommends '$rec' which is not selected"
      fi
    done < <(manifest_recommends "$p")
  done

  printf '%s\n' "${full[@]}"
}

# --------------------------------------------------------------------
# Config writing
# --------------------------------------------------------------------

# Both live in scripts/lib/net.sh, which picks a LAN *interface* instead of
# following the default route — a VPN on the host owns that route and used to
# make aurora firewall off the real LAN. See the header of that file.
_detect_lan_ip() { net_detect_lan_ip; }

_write_configs() {
  local hostname="$1" domain="$2" tz="$3" user="$4" lan_cidr="$5" lan_ip="$6"
  local inv="$REPO/inventory.ini"
  local gv="$REPO/group_vars/all.yml"

  if [[ -f "$inv" && -z "${FORCE:-}" ]]; then
    log_info "keeping existing $inv (FORCE=1 to overwrite)"
  else
    cat > "$inv" <<EOF
# Generated by bootstrap.sh — safe to edit.
[home_servers]
$hostname ansible_host=$lan_ip ansible_user=$user

[home_servers:vars]
ansible_python_interpreter=/usr/bin/python3
EOF
    log_ok "wrote $inv"
  fi

  if [[ -f "$gv" && -z "${FORCE:-}" ]]; then
    log_info "keeping existing $gv (FORCE=1 to overwrite)"
  else
    local uid gid
    uid=$(id -u "$user" 2>/dev/null || echo 1000)
    gid=$(id -g "$user" 2>/dev/null || echo 1000)
    cat > "$gv" <<EOF
---
# Generated by bootstrap.sh — safe to edit.
domain: $domain
home_timezone: $tz
home_user: $user
home_uid: $uid
home_gid: $gid

home_local_root: $REPO
media_root: /home/$user/media

# ---- security ----
ssh_allow_password_auth: false
ssh_permit_root_login: "no"
firewall_allow_from_lan_only: true
firewall_lan_cidr: $lan_cidr
fail2ban_ban_time: 1h
fail2ban_find_time: 10m
fail2ban_max_retry: 5

# ---- network ----
lan_ip: $lan_ip

# Frees port 53 for the privacy package's AdGuard by turning off
# systemd-resolved's loopback stub listener. Set false to keep the stub.
dns_stub_listener_disabled: true
EOF
    log_ok "wrote $gv"
  fi
}

# --------------------------------------------------------------------
# Install (fully non-interactive — D1)
#
# Phase 1 of the two-phase install: base
# setup. There are NO prompts. Every host fact is auto-detected (with env
# overrides for CI/headless), and first-run brings up ONLY core + the
# dashboard — just enough that the browser can reach the Aurora wizard,
# which owns Phase 2 (domain, secrets, DNS, TLS) and then hands off to the
# dashboard where the user installs everything else at their own pace.
#
# The old interactive TUI (hostname/domain/tz/user/CIDR/IP prompts + a
# package picker) is gone: the web wizard is the single place a human
# answers questions. Package selection in particular is no longer a
# first-run concern (D3) — the dashboard catalogue owns it.
# --------------------------------------------------------------------

# Packages Phase 1 always brings up: the reverse proxy + SSO plane (core)
# and the admin dashboard itself. Everything else is day-2 via the wizard
# hand-off / dashboard catalogue.
_BASE_PACKAGES=(core dashboard)

_install() {
  log_step "base setup (non-interactive)"

  # Auto-detect every host fact. Env vars override for CI/headless, but
  # nothing ever prompts. Domain defaults to aurora.local permanently
  # (D2): the web wizard owns the live runtime domain via core/.env, so
  # group_vars only needs a sane ansible-side default.
  local hostname="${HOSTNAME:-$(hostname -s)}"
  local domain="${DOMAIN:-aurora.local}"
  local tz="${HOME_TIMEZONE:-$(cat /etc/timezone 2>/dev/null || echo Europe/London)}"
  local user="${HOME_USER:-${SUDO_USER:-$USER}}"
  local lan_cidr="${LAN_CIDR:-$(_detect_lan_cidr)}"
  local lan_ip="${LAN_IP:-$(_detect_lan_ip)}"
  # Detection returns empty rather than guessing. Say so out loud instead of
  # silently baking a stranger's address into the firewall rules.
  if [[ -z "$lan_ip" || -z "$lan_cidr" ]]; then
    log_warn "couldn't work out which network this box is on."
    log_warn "falling back to 192.168.0.110 on 192.168.0.0/24 — if that is wrong,"
    log_warn "re-run with LAN_IP=<this box's address> LAN_CIDR=<your network>/24"
    lan_ip="${lan_ip:-192.168.0.110}"
    lan_cidr="${lan_cidr:-192.168.0.0/24}"
  fi

  log_info "host=$hostname user=$user domain=$domain tz=$tz"
  log_info "lan_ip=$lan_ip lan_cidr=$lan_cidr"

  _write_configs "$hostname" "$domain" "$tz" "$user" "$lan_cidr" "$lan_ip"

  # Seed .state.yml with just core (SSO/Authelia rides inside it). The
  # wizard is the authority for the enabled set from here on; dashboard is
  # forced into the compose bring-up below regardless.
  if ! state_exists; then
    state_init "$hostname" "$domain"
  fi

  _run_host_bootstrap
  # First run brings up ONLY core + dashboard. Any positional args or
  # ENABLE_PACKAGES are honoured as an escape hatch (mainly for tests /
  # power users), but the default is deliberately minimal.
  local -a requested
  if [[ $# -gt 0 ]]; then
    requested=("$@")
  elif [[ -n "${ENABLE_PACKAGES:-}" ]]; then
    # shellcheck disable=SC2206
    requested=($ENABLE_PACKAGES)
  else
    requested=("${_BASE_PACKAGES[@]}")
  fi
  _run_up "${requested[@]}"
}

_detect_lan_cidr() { net_detect_lan_cidr; }

# --------------------------------------------------------------------
# Runners
# --------------------------------------------------------------------

_run_host_bootstrap() {
  log_step "running host bootstrap (ansible)"
  if ! command -v ansible-playbook >/dev/null 2>&1; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq ansible python3-apt
  fi
  local inv="$REPO/inventory.ini"
  [[ -f "$inv" ]] || inv="$REPO/inventory.example.ini"
  # Limit to the host _write_configs actually named in the inventory.
  # site.yml targets the home_servers group, so a --limit of "localhost"
  # intersected to nothing and ansible refused to run any role at all.
  # Same expression as _write_configs so the two cannot drift apart.
  local target="${HOSTNAME:-$(hostname -s)}"
  ( cd "$REPO" && ansible-playbook -i "$inv" host/site.yml \
      --connection=local --limit "$target" -K )
}

# The docker role has just added the user to the docker group, but this
# shell's group set was fixed when it started, so docker.sock is still
# refused for the rest of the run. sg re-runs up.sh with the group
# applied, rather than failing and telling the operator to log out and
# back in halfway through their first install.
_up_with_docker_group() {
  local me="${USER:-$(id -un)}"

  if docker info >/dev/null 2>&1; then
    "$REPO/scripts/up.sh" "$@"
    return
  fi

  if command -v sg >/dev/null 2>&1 && getent group docker | grep -qw "$me"; then
    log_info "applying new docker group membership for this run"
    sg docker -c "$(printf '%q ' "$REPO/scripts/up.sh" "$@")"
    return
  fi

  # Not a group problem. Let up.sh fail with its own diagnostics.
  "$REPO/scripts/up.sh" "$@"
}

_run_up() {
  local -a full
  # shellcheck disable=SC2207
  full=($(_resolve_selection "$@"))

  # Record in state
  state_set_enabled "${full[@]}"

  log_step "plan"
  local p
  for p in "${full[@]}"; do
    printf '   - %s (%s)\n' "$p" "$(manifest_get title "$p")" >&2
  done

  _up_with_docker_group "${full[@]}"

  log_step "post-install notes"
  for p in "${full[@]}"; do
    local notes; notes=$(manifest_get post_install_notes "$p")
    [[ -z "$notes" ]] && continue
    printf '\n%s---- %s ----%s\n%s\n' "$_c_blue" "$p" "$_c_reset" "$notes" >&2
  done

  log_ok "done."
}

# --------------------------------------------------------------------
# Subcommands
# --------------------------------------------------------------------

cmd_list() {
  local pkg
  printf '%-14s %-10s %s\n' "PACKAGE" "CATEGORY" "TITLE"
  while IFS= read -r pkg; do
    printf '%-14s %-10s %s\n' \
      "$pkg" \
      "$(manifest_get category "$pkg")" \
      "$(manifest_get title "$pkg")"
  done < <(_all_packages)
}

cmd_status() {
  if ! state_exists; then
    log_warn "not installed yet (no .state.yml). Run: bootstrap.sh install"
    return 0
  fi
  printf 'host:      %s\n' "$(state_get hostname)"
  printf 'domain:    %s\n' "$(state_get domain)"
  printf 'installed: %s\n' "$(state_get installed_at)"
  printf 'enabled:   %s\n' "$(state_list_enabled | tr '\n' ' ')"
  printf 'profiles:  %s\n' "$(state_list_profiles | tr '\n' ' ')"
  echo
  exec "$REPO/scripts/status.sh"
}

cmd_add() {
  [[ $# -gt 0 ]] || die "usage: bootstrap.sh add PKG [PKG...]"
  state_exists || die "no .state.yml yet; run bootstrap.sh install first"

  local -a cur=() new=()
  local x
  while IFS= read -r x; do [[ -n "$x" ]] && cur+=("$x"); done < <(state_list_enabled)
  new=("${cur[@]}" "$@")
  # dedupe
  local -A seen=()
  local -a merged=()
  for x in "${new[@]}"; do
    [[ -n "${seen[$x]:-}" ]] && continue
    seen[$x]=1; merged+=("$x")
  done
  _run_up "${merged[@]}"
}

cmd_remove() {
  [[ $# -gt 0 ]] || die "usage: bootstrap.sh remove PKG [PKG...]"
  state_exists || die "no .state.yml yet"

  local pkg
  for pkg in "$@"; do
    [[ "$pkg" == "core" ]] && die "refusing to remove core (dashboard + proxy)"
    log_step "stopping $pkg"
    "$REPO/scripts/down.sh" "$pkg"
    state_disable "$pkg"
    log_ok "removed $pkg"
  done

  # Re-render fragments for the remaining enabled set so stale caddy
  # snippets don't linger. Sourced late to avoid pulling render.sh in for
  # commands that don't need it.
  # shellcheck source=scripts/lib/render.sh
  . "$REPO/scripts/lib/render.sh"
  local remaining=()
  mapfile -t remaining < <(state_list_enabled)
  if [[ ${#remaining[@]} -gt 0 ]]; then
    log_step "re-rendering fragments for: ${remaining[*]}"
    render_caddy_snippets "${remaining[@]}"
    # Nudge caddy to reload the trimmed config.
    docker kill --signal SIGUSR1 caddy 2>/dev/null || true
  fi
}

# --------------------------------------------------------------------
# Dispatch
# --------------------------------------------------------------------

# Root guard
[[ $EUID -eq 0 ]] && die "run as your normal user; sudo is invoked where needed"

case "$CMD" in
  list)
    _ensure_prereqs
    cmd_list
    ;;
  status)
    _ensure_prereqs
    cmd_status
    ;;
  add)
    _ensure_prereqs
    cmd_add "$@"
    ;;
  remove)
    _ensure_prereqs
    cmd_remove "$@"
    ;;
  install)
    _ensure_prereqs
    _install "$@"
    ;;
esac
