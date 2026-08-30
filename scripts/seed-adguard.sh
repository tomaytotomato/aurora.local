#!/usr/bin/env bash
# aurora.local / scripts/seed-adguard.sh
#
# Idempotent seeder for AdGuard Home. Ensures the rewrites in
# packages/privacy/adguard/rewrites.yaml are present in the live
# AdGuardHome.yaml.
#
# Safe to run any time. Only ADDS missing rewrites; never removes.
#
# Prereqs: sudo (config is root-owned inside the container), python3 with
# yaml. htpasswd/apache2-utils is no longer needed — it was only there to
# bcrypt a password for the retired Homepage widget's AdGuard user.

set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
export REPO
CONF="$REPO/data/adguard/conf/AdGuardHome.yaml"
REWRITES_FIXTURE="$REPO/packages/privacy/adguard/rewrites.yaml"

# The fixture is a template, not a fact: it used to hardcode
# 192.168.0.110 and aurora.local, which is one particular box's address
# checked into the repo. Both are substituted from this box's own state.
# shellcheck source=lib/net.sh
. "$REPO/scripts/lib/net.sh"
# shellcheck source=lib/state.sh
. "$REPO/scripts/lib/state.sh"
# NOT ${LAN_IP} from packages/privacy/.env: there it means "the address
# AdGuard binds :53 to", whose sane default is 0.0.0.0 — a bind wildcard,
# and a nonsense DNS answer. Using it here wrote rewrites pointing every
# *.aurora.local name at 0.0.0.0 alongside the correct ones.
SEED_LAN_IP="$(net_detect_lan_ip)"
if [[ -z "$SEED_LAN_IP" || "$SEED_LAN_IP" == "0.0.0.0" ]]; then
  warn "no LAN address detected; skipping DNS rewrites (they would point nowhere)"
  exit 0
fi
SEED_DOMAIN="$(state_get domain 2>/dev/null || echo "${DOMAIN:-aurora.local}")"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERR\033[0m %s\n' "$*" >&2; exit 1; }

[[ -f "$CONF" ]] || {
  warn "AdGuard config not found at $CONF"
  warn "run the first-boot wizard at http://<lan-ip>:3000/ first, then re-run me"
  exit 0
}
[[ -f "$REWRITES_FIXTURE" ]] || die "missing fixture $REWRITES_FIXTURE"

# Load ADGUARD user/pass from core/.env if present, so we can create a
# dedicated Homepage user without asking.
if [[ -f "$REPO/packages/core/.env" ]]; then
  # shellcheck disable=SC1091
  set -a; source "$REPO/packages/core/.env"; set +a
fi

# ---- 1. Merge rewrites ------------------------------------------------
log "merging rewrites from $(basename "$REWRITES_FIXTURE")"

added_count=$(sudo python3 - "$CONF" "$REWRITES_FIXTURE" "$SEED_LAN_IP" "$SEED_DOMAIN" <<'PY'
import sys, yaml
conf_path, fixture_path, lan_ip, domain = sys.argv[1:5]

def subst(value):
    if not isinstance(value, str):
        return value
    return value.replace('${LAN_IP}', lan_ip).replace('${DOMAIN}', domain)

with open(conf_path) as f: conf = yaml.safe_load(f)
with open(fixture_path) as f: fixture = yaml.safe_load(f)

conf.setdefault('filtering', {}).setdefault('rewrites', [])
have = {(r.get('domain'), r.get('answer')) for r in conf['filtering']['rewrites']}
added = 0
for r in fixture.get('rewrites', []):
    r = {k: subst(v) for k, v in r.items()}
    key = (r.get('domain'), r.get('answer'))
    if key not in have:
        conf['filtering']['rewrites'].append(r)
        added += 1

if added:
    with open(conf_path, 'w') as f:
        yaml.safe_dump(conf, f, sort_keys=False, default_flow_style=False)
print(added)
PY
)
log "rewrites: ${added_count:-0} added"

# Step 2 used to create a dedicated AdGuard user so Homepage's AdGuard
# widget could query it. Homepage was retired months ago, so that user
# had no consumer — it was an extra credentialed account on the LAN's DNS
# server, with its password sitting in packages/core/.env, existing for
# nothing. Removed along with the rest of the Homepage machinery.

# ---- 2. Restart AdGuard so it re-reads the config ---------------------
# Only when the config actually changed. This script runs from up.sh's
# post-up hooks on EVERY launch, so an unconditional restart meant
# installing any unrelated app took the LAN's DNS server down for a few
# seconds — the household symptom of "I added a photo app and the wifi
# went funny".
if [[ "${added_count:-0}" -gt 0 ]] && docker ps --format '{{.Names}}' | grep -q '^adguard$'; then
  log "restarting adguard so it picks up the new rewrites"
  docker restart adguard >/dev/null
  sleep 3
fi

log "done."
