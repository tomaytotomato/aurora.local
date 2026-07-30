#!/usr/bin/env bash
# home.local / scripts/seed-adguard.sh
#
# Idempotent seeder for AdGuard Home. Ensures:
#   1. The rewrites in packages/privacy/adguard/rewrites.yaml are
#      present in the live AdGuardHome.yaml
#   2. A dedicated 'homepage' user exists (bcrypted from
#      HOMEPAGE_VAR_ADGUARD_PASS in packages/core/.env)
#
# Safe to run any time. Only ADDS missing rewrites; never removes.
# Only creates the homepage user if it's not already there.
#
# Prereqs: sudo (config is root-owned inside the container), apache2-utils
# (for htpasswd), python3 with yaml.

set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
CONF="$REPO/data/adguard/conf/AdGuardHome.yaml"
REWRITES_FIXTURE="$REPO/packages/privacy/adguard/rewrites.yaml"

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

sudo python3 - "$CONF" "$REWRITES_FIXTURE" <<'PY'
import sys, yaml
conf_path, fixture_path = sys.argv[1], sys.argv[2]
with open(conf_path) as f: conf = yaml.safe_load(f)
with open(fixture_path) as f: fixture = yaml.safe_load(f)

conf.setdefault('filtering', {}).setdefault('rewrites', [])
have = {(r.get('domain'), r.get('answer')) for r in conf['filtering']['rewrites']}
added = 0
for r in fixture.get('rewrites', []):
    key = (r.get('domain'), r.get('answer'))
    if key not in have:
        conf['filtering']['rewrites'].append(r)
        added += 1

with open(conf_path, 'w') as f:
    yaml.safe_dump(conf, f, sort_keys=False, default_flow_style=False)
print(f"  added {added} rewrite(s); {len(conf['filtering']['rewrites'])} total")
PY

# ---- 2. Ensure homepage user exists -----------------------------------
if [[ -n "${HOMEPAGE_VAR_ADGUARD_USER:-}" && -n "${HOMEPAGE_VAR_ADGUARD_PASS:-}" ]]; then
  log "ensuring AdGuard user '$HOMEPAGE_VAR_ADGUARD_USER' exists"

  # We DON'T want to check the plaintext-hash match — that's not how
  # bcrypt works. Just check the username exists; if not, inject.
  if sudo grep -qE "^\s*- name:\s*$HOMEPAGE_VAR_ADGUARD_USER\s*$" "$CONF"; then
    echo "  user '$HOMEPAGE_VAR_ADGUARD_USER' already present"
  else
    command -v htpasswd >/dev/null || die "htpasswd missing — apt install apache2-utils"
    HASH=$(htpasswd -bnBC 10 "" "$HOMEPAGE_VAR_ADGUARD_PASS" | tr -d ':\n' | sed 's/^\$2y/\$2a/')

    sudo python3 - "$CONF" "$HOMEPAGE_VAR_ADGUARD_USER" "$HASH" <<'PY'
import sys, yaml
conf_path, name, phash = sys.argv[1], sys.argv[2], sys.argv[3]
with open(conf_path) as f: conf = yaml.safe_load(f)
conf.setdefault('users', [])
if not any(u.get('name') == name for u in conf['users']):
    conf['users'].append({'name': name, 'password': phash})
with open(conf_path, 'w') as f:
    yaml.safe_dump(conf, f, sort_keys=False, default_flow_style=False)
print(f"  injected user '{name}'")
PY
  fi
else
  warn "HOMEPAGE_VAR_ADGUARD_{USER,PASS} unset in packages/core/.env; skipping user seed"
fi

# ---- 3. Restart AdGuard so it re-reads the config ---------------------
if docker ps --format '{{.Names}}' | grep -q '^adguard$'; then
  log "restarting adguard container"
  docker restart adguard >/dev/null
  sleep 3
fi

log "done."
