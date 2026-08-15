#!/usr/bin/env bash
# aurora.local / scripts/rotate-secrets.sh
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
#              purpose is *not* a secret (e.g. DOMAIN, TZ, USER
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
NON_SECRET_PATTERN='^(TZ|DOMAIN|LAN_IP|VPN_SERVICE_PROVIDER|VPN_TYPE|SERVER_COUNTRIES|SERVER_CITIES|OPENVPN_USER|HOMEPAGE_VAR_[A-Z]+_USER|.*_USER|FIREWALL.*|VPN_PORT_FORWARDING(_PROVIDER)?|PORT_FORWARD_ONLY|WIREGUARD_ADDRESSES)$'
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

# --------------------------------------------------------------------
# recreate_running_package <pkg> <envfile>
#
# Docker Compose only evaluates a compose file's ${VAR} interpolation
# once, at container-create time. `docker compose restart` (or
# `docker restart <container>`) reuses the container exactly as it was
# created, so a secret rewritten in packages/<pkg>/.env after the
# container is already up never reaches the running process on its
# own — the container just keeps the old value forever. This recreates
# the one package whose .env just changed (scoped to its own
# compose.yml, no --remove-orphans) so a rotated secret actually lands
# in the running config instead of silently going stale.
#
# No-op (with a warning, never fatal — this is a report-first script)
# when docker isn't available, or when the package has no containers
# up yet: nothing to recreate on a box that hasn't been brought up.
# --------------------------------------------------------------------
recreate_running_package() {
  local pkg="$1" envf="$2"
  local compose_file="$REPO/packages/$pkg/compose.yml"
  [[ -f "$compose_file" ]] || return 0

  if ! has_cmd docker; then
    warn "  docker not found; $pkg/.env was rotated on disk only — recreate its container(s) by hand"
    return 0
  fi

  local running
  running="$(docker compose -p aurora -f "$compose_file" ps -q 2>/dev/null)" || running=""
  if [[ -z "$running" ]]; then
    dim "  $pkg is not currently up; the rotated secret(s) take effect on the next up.sh"
    return 0
  fi

  log "  recreating $pkg container(s) so the rotated secret(s) reach the running config"
  if (
    set -a
    # shellcheck source=/dev/null
    . "$envf"
    set +a
    docker compose -p aurora -f "$compose_file" up -d --force-recreate
  ) >/dev/null; then
    ok "  $pkg recreated"
  else
    warn "  automatic recreate failed — run by hand: docker compose -p aurora -f packages/$pkg/compose.yml up -d --force-recreate"
  fi
}

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
      # Report which keys changed, never the values. A `diff -u` here
      # would print the freshly-rotated secret in plain text to
      # whatever terminal or log captures this script's stdout —
      # exactly the leak rotation exists to prevent.
      ok "applied; keys changed: $(printf '%s ' "${weak_here[@]%%=*}")"
      echo

      recreate_running_package "$pkg" "$envf"
    fi
  fi
done

if (( FOUND == 0 )); then
  ok "no weak secrets found across packages/*/.env"
elif (( ! APPLY )); then
  warn "$FOUND file(s) have weak values; re-run with --apply to rotate"
  exit 2
fi
