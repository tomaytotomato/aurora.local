#!/usr/bin/env bash
# aurora.local · scripts/mdns-audit.sh
#
# iter-3 P1c: diagnostic script for the "aurora.local won't resolve on
# my Mac / Firefox" class of problems Bruce hit on 2026-08-02.
#
# What it does:
#   1. Browses the local Avahi network for anything advertising itself
#      as `_http._tcp` and greps for `aurora`.
#   2. Sends a direct multicast DNS query for `aurora.local` at
#      224.0.0.251:5353 via `dig`.
#   3. Reports the Avahi daemon status.
#   4. Records the box's own advertised hostname + LAN IPs so future
#      audits can spot a hostname change or a new collision.
#
# Output goes to stdout AND to logs/mdns-audit-YYYY-MM-DD.txt so we
# have a paper trail if collisions surface later.
#
# Runs both on the host and inside the aurora container — see the
# `-v /var/run/dbus:/var/run/dbus` bind mount in packages/dashboard/
# compose.yml if you want the container to see the host's Avahi.
#
# Usage:
#   scripts/mdns-audit.sh                # human-readable + log written
#   scripts/mdns-audit.sh --quiet        # only writes the log
#   scripts/mdns-audit.sh --hostname foo # audit `foo.local` instead
#
# Exit codes:
#   0 — audit ran (a collision may still have been found; check output)
#   2 — required tool missing (avahi-browse or dig)

set -u
set -o pipefail

QUIET=0
HOSTNAME_ARG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --quiet|-q) QUIET=1; shift ;;
    --hostname) HOSTNAME_ARG="$2"; shift 2 ;;
    --hostname=*) HOSTNAME_ARG="${1#*=}"; shift ;;
    -h|--help)
      sed -n '2,32p' "$0"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 64 ;;
  esac
done

# Resolve target hostname. Prefer explicit --hostname, then .state.yml,
# then `hostname -s`.
TARGET="$HOSTNAME_ARG"
if [ -z "$TARGET" ]; then
  STATE=""
  for candidate in /repo/.state.yml /aurora.local/.state.yml "$HOME/aurora.local/.state.yml" "$(pwd)/.state.yml"; do
    if [ -r "$candidate" ]; then STATE="$candidate"; break; fi
  done
  if [ -n "$STATE" ]; then
    TARGET=$(grep -E '^hostname:' "$STATE" 2>/dev/null | head -n1 | awk '{print $2}' | tr -d '"'\''')
  fi
fi
[ -z "$TARGET" ] && TARGET=$(hostname -s 2>/dev/null || echo aurora)
FQDN="${TARGET}.local"

# Log destination.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$REPO_ROOT/logs"
mkdir -p "$LOG_DIR" 2>/dev/null || LOG_DIR="/tmp"
LOG_FILE="$LOG_DIR/mdns-audit-$(date +%F).txt"

emit() {
  if [ "$QUIET" -eq 0 ]; then printf '%s\n' "$*"; fi
  printf '%s\n' "$*" >>"$LOG_FILE"
}

section() {
  emit ""
  emit "== $* =="
}

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    emit "!! missing tool: $1 — install it (alpine: apk add $2; ubuntu: apt install $3)"
    return 1
  fi
  return 0
}

emit "# aurora mdns-audit · $(date -Iseconds) · target=$FQDN"

MISSING=0
need avahi-browse   avahi-tools   avahi-utils  || MISSING=1
need avahi-resolve  avahi-tools   avahi-utils  || true
need dig            bind-tools    dnsutils     || MISSING=1
if [ "$MISSING" -eq 1 ]; then
  emit "!! required diagnostics missing; aborting"
  exit 2
fi

section "avahi-daemon status"
if command -v pgrep >/dev/null 2>&1; then
  if pgrep -x avahi-daemon >/dev/null 2>&1; then
    emit "avahi-daemon: running (pid $(pgrep -x avahi-daemon | tr '\n' ' '))"
  else
    emit "avahi-daemon: NOT running — mDNS won't answer for this box"
  fi
else
  emit "pgrep unavailable — skipping daemon status probe"
fi

section "advertised _http._tcp records matching '$TARGET'"
BROWSE=$(avahi-browse -atr --terminate 2>/dev/null | grep -iE "\\b${TARGET}\\b|\\b${FQDN}\\b" || true)
if [ -z "$BROWSE" ]; then
  emit "no _http._tcp advertisements match '$TARGET' — is avahi seeing this network?"
else
  emit "$BROWSE"
fi

section "avahi-resolve $FQDN"
if command -v avahi-resolve >/dev/null 2>&1; then
  RESOLVED=$(avahi-resolve -n "$FQDN" 2>&1 || true)
  emit "${RESOLVED:-avahi-resolve returned no output}"
fi

section "multicast dig @224.0.0.251:5353 $FQDN"
DIG=$(dig +short +time=2 +tries=1 @224.0.0.251 -p 5353 "$FQDN" 2>&1 || true)
if [ -z "$DIG" ]; then
  emit "dig returned no answer — check that :5353 udp is reachable on the local segment"
else
  emit "$DIG"
fi

section "local interface facts (for collision hunting)"
if command -v ip >/dev/null 2>&1; then
  emit "$(ip -4 -o addr show scope global | awk '{print $2, $4}')"
elif command -v ifconfig >/dev/null 2>&1; then
  emit "$(ifconfig | grep -E 'inet (addr:)?[0-9]')"
fi

section "collision check"
# Count how many distinct hosts are advertising the target name. If >1
# there is a real .local collision on this LAN (a very common failure
# mode when a Mac also claims <hostname>.local).
COLLISIONS=$(printf '%s\n' "$BROWSE" | awk 'NF' | awk '{print $NF}' | sort -u | wc -l)
if [ "$COLLISIONS" -gt 1 ]; then
  emit "!! DETECTED collision — multiple hosts claim '$FQDN' on this LAN"
  emit "   hunt: check every device (Mac, printer, router, another Pi) for"
  emit "        a computer-name matching '$TARGET' and rename all but one."
else
  emit "no collision detected for '$FQDN'"
fi

emit ""
emit "# log written: $LOG_FILE"
exit 0
