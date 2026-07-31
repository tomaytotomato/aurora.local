#!/usr/bin/env bash
# home.local / scripts/doctor.sh
#
# Pre-flight sanity check for a home.local box. Run this on any host
# you plan to install home.local on, or after an install to verify the
# environment is still sane.
#
# Exits non-zero if any CRITICAL check fails. WARN-level checks do not
# affect exit code but are surfaced clearly.
#
# Usage:
#   ./scripts/doctor.sh          # full report
#   ./scripts/doctor.sh --quiet  # only failures

set -euo pipefail

# shellcheck source=lib/ops.sh
. "$(dirname "$0")/lib/ops.sh"

QUIET=0
for a in "$@"; do
  case "$a" in
    --quiet|-q) QUIET=1 ;;
    -h|--help)  sed -n '2,15p' "$0"; exit 0 ;;
    *)          die "unknown arg: $a" ;;
  esac
done

load_group_vars

FAILS=0
WARNS=0
_pass() { (( QUIET )) || ok "$*"; }
_fail() { err "$*"; FAILS=$((FAILS+1)); }
_warn() { warn "$*"; WARNS=$((WARNS+1)); }

log "home.local doctor — $(date -Iseconds)"
echo

# ---- identity --------------------------------------------------------
log "identity"
if [[ $EUID -eq 0 ]]; then
  _fail "running as root; home.local expects a normal user (sudo is used explicitly)"
else
  _pass "user=$(id -un) uid=$(id -u) gid=$(id -g)"
fi

# ---- docker ----------------------------------------------------------
log "docker"
if ! has_cmd docker; then
  _fail "docker not installed"
elif ! docker info >/dev/null 2>&1; then
  _fail "docker daemon not reachable (are you in the 'docker' group? logged out/in?)"
else
  _pass "docker $(docker version -f '{{.Server.Version}}' 2>/dev/null || echo '?')"
fi

if has_cmd docker && docker compose version >/dev/null 2>&1; then
  _pass "docker compose plugin $(docker compose version --short 2>/dev/null || echo '?')"
else
  _fail "'docker compose' plugin missing (install docker-compose-plugin)"
fi

if has_cmd docker && docker info >/dev/null 2>&1; then
  if docker network inspect home_net >/dev/null 2>&1; then
    _pass "docker network home_net exists"
  else
    _warn "docker network home_net missing (scripts/up.sh will create it)"
  fi
fi

# ---- resources -------------------------------------------------------
log "resources"
mem_mb=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
if (( mem_mb < 1500 )); then
  _fail "low RAM: ${mem_mb} MiB (need >=2 GiB for the media stack)"
else
  _pass "RAM ${mem_mb} MiB"
fi

swap_mb=$(awk '/SwapTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
if (( swap_mb < 1024 )); then
  _warn "swap ${swap_mb} MiB (recommend >=4 GiB on low-RAM boxes)"
else
  _pass "swap ${swap_mb} MiB"
fi

root_free=$(df -Pm / | awk 'NR==2 {print $4}')
if (( root_free < 5120 )); then
  _fail "/ free ${root_free} MiB (<5 GiB)"
else
  _pass "/ free ${root_free} MiB"
fi

media_root="${media_root:-${MEDIA_ROOT:-}}"
if [[ -n "$media_root" && -d "$media_root" ]]; then
  media_free=$(df -Pm "$media_root" | awk 'NR==2 {print $4}')
  _pass "\$MEDIA_ROOT ($media_root) free ${media_free} MiB"
elif [[ -n "$media_root" ]]; then
  _warn "media_root=$media_root does not exist yet"
else
  _warn "media_root not set in group_vars/all.yml"
fi

# ---- mDNS / hostname -------------------------------------------------
log "networking"
if has_cmd hostnamectl; then
  _pass "hostname: $(hostname -f 2>/dev/null || hostname)"
fi
if systemctl is-active --quiet avahi-daemon 2>/dev/null; then
  _pass "avahi-daemon active (.local mDNS)"
else
  _warn "avahi-daemon not active — <hostname>.local will not resolve on the LAN"
fi

# ---- listening ports vs manifests -----------------------------------
log "ports"
declare -a want_ports=()
while IFS= read -r pkg; do
  m="$REPO/packages/$pkg/manifest.yml"
  [[ -f "$m" ]] || continue
  # crude parse: lines like "  - {port: 80, ...}"
  while IFS= read -r p; do
    want_ports+=("$p:$pkg")
  done < <(awk '/^ports:/{f=1;next} f && /^[[:space:]]*-/{
      match($0, /port:[[:space:]]*[0-9]+/); if(RSTART){print substr($0,RSTART+5,RLENGTH-5)+0}
    } f && /^[^[:space:]-]/{f=0}' "$m")
done < <(list_enabled_packages)

if has_cmd ss; then
  listening=$(ss -H -tuln 2>/dev/null | awk '{print $5}' | awk -F: '{print $NF}' | sort -u)
  for entry in "${want_ports[@]}"; do
    p="${entry%%:*}"; pkg="${entry##*:}"
    if grep -qxF "$p" <<<"$listening"; then
      _pass "port $p ($pkg) listening"
    else
      # not fatal — package may not be up yet
      (( QUIET )) || dim "  port $p ($pkg) not listening (package may be stopped)"
    fi
  done
else
  _warn "ss not available; cannot audit listening ports"
fi

# ---- DNS / adguard ---------------------------------------------------
if list_enabled_packages | grep -qx privacy; then
  log "dns"
  home_domain="${home_domain:-${HOME_DOMAIN:-home.local}}"
  if has_cmd getent && getent hosts "home.$home_domain" >/dev/null 2>&1; then
    _pass "DNS home.$home_domain resolves"
  else
    _warn "DNS home.$home_domain does not resolve (adguard rewrites configured?)"
  fi
fi

# ---- caddy root cert -------------------------------------------------
if list_enabled_packages | grep -qx core; then
  log "caddy trust"
  found=0
  for trust in /usr/local/share/ca-certificates /etc/ca-certificates/trust-source/anchors; do
    if [[ -d "$trust" ]] && compgen -G "$trust/*caddy*" >/dev/null 2>&1; then
      _pass "caddy root cert installed at $trust"
      found=1; break
    fi
  done
  (( found )) || _warn "caddy root cert not installed on this host (run scripts/get-caddy-root-cert.sh)"
fi

# ---- summary ---------------------------------------------------------
echo
if (( FAILS == 0 && WARNS == 0 )); then
  ok "doctor: all checks passed"
elif (( FAILS == 0 )); then
  warn "doctor: $WARNS warning(s), 0 failures"
else
  err "doctor: $FAILS failure(s), $WARNS warning(s)"
  exit 1
fi
