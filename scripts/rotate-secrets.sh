#!/usr/bin/env bash
# home.local / scripts/rotate-secrets.sh
#
# Walk every packages/*/.env and flag values that look weak. Rules:
#   - Empty value
#   - Well-known bad values: "password", "changeme", "admin", "letmein"
#   - Key names matching *SECRET*, *KEY*, *PASSWORD*, *TOKEN*, *PASS*
#     whose value is < 12 chars
#
# Modes:
#   (default) report only
#   --apply    replace weak values with `openssl rand -hex 24` output,
#              write a .env.bak, and print a diff. Skips keys whose
#              purpose is *not* a secret (e.g. HOME_DOMAIN, TZ, USER
#              fields) via NON_SECRET_KEYS pattern.
#
# Never rotates keys that already look strong (>= 24 chars random-ish).

set -euo pipefail

# shellcheck source=lib/ops.sh
. "$(dirname "$0")/lib/ops.sh"

APPLY=0
for a in "$@"; do
  case "$a" in
    --apply)   APPLY=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *)         die "unknown arg: $a" ;;
  esac
done

# Names that look like secrets but are NOT — never rotate.
NON_SECRET_PATTERN='^(TZ|HOME_DOMAIN|LAN_IP|VPN_SERVICE_PROVIDER|VPN_TYPE|SERVER_COUNTRIES|SERVER_CITIES|OPENVPN_USER|HOMEPAGE_VAR_[A-Z]+_USER|.*_USER|FIREWALL.*|VPN_PORT_FORWARDING(_PROVIDER)?|PORT_FORWARD_ONLY|WIREGUARD_ADDRESSES)$'
SECRET_HINT_PATTERN='(SECRET|KEY|PASSWORD|TOKEN|PASS|PSK)'
WEAK_VALUES=('' password Password PASSWORD changeme change_me admin letmein 123456 secret)

weak_value() {
  local k="$1" v="$2"
  # Skip explicit non-secret keys.
  if [[ "$k" =~ $NON_SECRET_PATTERN ]]; then
    return 1
  fi
  # Explicit bad values.
  for bad in "${WEAK_VALUES[@]}"; do
    if [[ "$v" == "$bad" ]]; then
      return 0
    fi
  done
  # Secret-hinted keys with short values.
  if [[ "$k" =~ $SECRET_HINT_PATTERN ]] && (( ${#v} < 12 )); then
    return 0
  fi
  return 1
}

require_cmd openssl

FOUND=0
for envf in "$REPO"/packages/*/.env; do
  [[ -f "$envf" ]] || continue
  pkg=$(basename "$(dirname "$envf")")
  weak_here=()
  suggestions=()
  while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    k="${BASH_REMATCH[1]}"
    v="${BASH_REMATCH[2]}"
    # strip surrounding quotes
    v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
    if weak_value "$k" "$v"; then
      weak_here+=("$k=<${v:-empty}>")
      suggestions+=("$k=$(openssl rand -hex 24)")
    fi
  done < "$envf"

  if (( ${#weak_here[@]} > 0 )); then
    FOUND=$((FOUND+1))
    log "$pkg/.env — ${#weak_here[@]} weak value(s)"
    for w in "${weak_here[@]}"; do warn "  $w"; done
    echo
    dim "  suggested replacements:"
    for s in "${suggestions[@]}"; do dim "    $s"; done
    echo

    if (( APPLY )); then
      cp -a "$envf" "$envf.bak"
      for s in "${suggestions[@]}"; do
        k="${s%%=*}"; newv="${s#*=}"
        # sed with | as delim (hex has no |). Only first match per key.
        sed -i "0,/^${k}=/{s|^${k}=.*|${k}=${newv}|}" "$envf"
      done
      ok "applied; diff vs backup:"
      diff -u "$envf.bak" "$envf" || true
      echo
    fi
  fi
done

if (( FOUND == 0 )); then
  ok "no weak secrets found across packages/*/.env"
elif (( ! APPLY )); then
  warn "$FOUND file(s) have weak values; re-run with --apply to rotate"
  exit 2
fi
