#!/usr/bin/env bash
# aurora.local / bootstrap.sh
#
# Interactive or non-interactive installer for an aurora.local server.
#
# Modes:
#   bash bootstrap.sh                          # interactive TUI
#   ENABLE_PACKAGES="core media" bootstrap.sh  # non-interactive
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
  bootstrap.sh [install [PKG...]]   Full install (interactive if no args + tty).
  bootstrap.sh add PKG [PKG...]     Enable and start additional packages.
  bootstrap.sh remove PKG [PKG...]  Stop and disable packages.
  bootstrap.sh list                 List all packages available in this repo.
  bootstrap.sh status               Show current state + container health.
  bootstrap.sh --help               This message.

Environment:
  ENABLE_PACKAGES="core privacy"    Skip package picker; use these.
  HOMELOCAL_NONINTERACTIVE=1        Never prompt (use defaults/env only).
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
  # whiptail is nice-to-have; only require if interactive
  if [[ $# -gt 0 && "$1" == "interactive" ]]; then
    command -v whiptail >/dev/null 2>&1 || missing+=("whiptail")
  fi
  if [[ ${#missing[@]} -gt 0 ]]; then
    log_step "installing: ${missing[*]}"
    sudo apt-get update -qq
    # translate synthetic names
    local pkgs=()
    for m in "${missing[@]}"; do
      case "$m" in
        python3-yaml) pkgs+=("python3-yaml") ;;
        whiptail)     pkgs+=("whiptail") ;;
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

_detect_lan_ip() {
  ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}'
}

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
EOF
    log_ok "wrote $gv"
  fi
}

# --------------------------------------------------------------------
# Interactive flow
# --------------------------------------------------------------------

_interactive_install() {
  log_step "interactive setup"

  local default_host default_domain default_tz default_user default_cidr default_ip
  default_host="${HOSTNAME:-$(hostname -s)}"
  default_domain="${DOMAIN:-aurora.local}"
  default_tz="${HOME_TIMEZONE:-$(cat /etc/timezone 2>/dev/null || echo Europe/London)}"
  default_user="${HOME_USER:-$USER}"
  default_cidr="${LAN_CIDR:-192.168.0.0/24}"
  default_ip="${LAN_IP:-$(_detect_lan_ip)}"
  default_ip="${default_ip:-192.168.0.110}"

  local hostname domain tz user lan_cidr lan_ip
  hostname=$(prompt_inputbox "Hostname"  "Server hostname (mdns *.local)" "$default_host")
  domain=$(prompt_inputbox   "Domain"    "Domain for Caddy vhosts (e.g. aurora.local)" "$default_domain")
  tz=$(prompt_inputbox       "Timezone"  "IANA timezone" "$default_tz")
  user=$(prompt_inputbox     "User"      "Unix user owning bind-mounts" "$default_user")
  lan_cidr=$(prompt_inputbox "LAN CIDR"  "LAN subnet (UFW allow-from)" "$default_cidr")
  lan_ip=$(prompt_inputbox   "LAN IP"    "This box's LAN IP (AdGuard DNS bind)" "$default_ip")

  _write_configs "$hostname" "$domain" "$tz" "$user" "$lan_cidr" "$lan_ip"

  # Package picker
  local -a checklist_args=()
  local pkg title desc default_on
  while IFS= read -r pkg; do
    title=$(manifest_get title "$pkg")
    desc="${title:-$pkg}"
    # Default 'on' for core+privacy+media+storage (existing behaviour)
    case "$pkg" in core|privacy|media|storage) default_on="on" ;; *) default_on="off" ;; esac
    checklist_args+=("$pkg" "$desc" "$default_on")
  done < <(_all_packages)

  local selection
  selection=$(prompt_checklist "Packages" "Space to toggle, Enter to confirm" "${checklist_args[@]}") \
    || die "package selection cancelled"

  # shellcheck disable=SC2206
  local -a requested=($selection)
  [[ ${#requested[@]} -eq 0 ]] && requested=(core)

  # Init state before running host
  if ! state_exists; then
    state_init "$hostname" "$domain"
  fi

  _run_host_bootstrap
  _run_up "${requested[@]}"
}

# --------------------------------------------------------------------
# Non-interactive install
# --------------------------------------------------------------------

_noninteractive_install() {
  log_step "non-interactive install"

  local hostname="${HOSTNAME:-$(hostname -s)}"
  local domain="${DOMAIN:-aurora.local}"
  local tz="${HOME_TIMEZONE:-$(cat /etc/timezone 2>/dev/null || echo Europe/London)}"
  local user="${HOME_USER:-$USER}"
  local lan_cidr="${LAN_CIDR:-192.168.0.0/24}"
  local lan_ip="${LAN_IP:-$(_detect_lan_ip)}"
  lan_ip="${lan_ip:-192.168.0.110}"

  _write_configs "$hostname" "$domain" "$tz" "$user" "$lan_cidr" "$lan_ip"

  local -a requested
  if [[ $# -gt 0 ]]; then
    requested=("$@")
  elif [[ -n "${ENABLE_PACKAGES:-}" ]]; then
    # shellcheck disable=SC2206
    requested=($ENABLE_PACKAGES)
  else
    requested=(core privacy media storage)
  fi

  if ! state_exists; then
    state_init "$hostname" "$domain"
  fi

  _run_host_bootstrap
  _run_up "${requested[@]}"
}

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
  # snippets and homepage groups don't linger. Sourced late to avoid
  # pulling render.sh in for commands that don't need it.
  # shellcheck source=scripts/lib/render.sh
  . "$REPO/scripts/lib/render.sh"
  local remaining=()
  mapfile -t remaining < <(state_list_enabled)
  if [[ ${#remaining[@]} -gt 0 ]]; then
    log_step "re-rendering fragments for: ${remaining[*]}"
    render_caddy_snippets "${remaining[@]}"
    render_homepage_services "${remaining[@]}"
    # Nudge caddy + homepage to reload the trimmed config.
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
    if [[ -n "${ENABLE_PACKAGES:-}" || -n "${HOMELOCAL_NONINTERACTIVE:-}" || ! -t 0 ]]; then
      _ensure_prereqs
      _noninteractive_install "$@"
    else
      _ensure_prereqs interactive
      _interactive_install
    fi
    ;;
esac
