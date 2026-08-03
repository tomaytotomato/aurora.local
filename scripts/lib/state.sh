# shellcheck shell=bash
# aurora.local / scripts/lib/state.sh
#
# Read/write $REPO/.state.yml. Records what's currently enabled on this
# host so `add`, `remove`, `status`, `up`, `down` know the true set.
#
# Schema:
#   bootstrap_version: 1
#   hostname: aurora
#   domain: aurora.local
#   installed_at: 2025-07-31T14:34:00Z
#   enabled:
#     - core
#     - privacy
#   profiles:
#     - torrent
#
# Uses yq if present (round-trips YAML cleanly), else python3+PyYAML.

[[ -n "${_HOMELOCAL_STATE_SH:-}" ]] && return 0
_HOMELOCAL_STATE_SH=1

# shellcheck source=./log.sh
. "$(dirname "${BASH_SOURCE[0]}")/log.sh"

: "${REPO:?state.sh requires REPO to be set}"

STATE_FILE="${STATE_FILE:-$REPO/.state.yml}"
STATE_VERSION=1

_state_backend=""
_state_detect_backend() {
  [[ -n "$_state_backend" ]] && return 0
  if command -v yq >/dev/null 2>&1 && yq --version 2>/dev/null | grep -qE 'version v?4'; then
    _state_backend="yq"
  elif command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' 2>/dev/null; then
    _state_backend="python"
  else
    die "need yq or python3+PyYAML to manage state"
  fi
}

state_exists() { [[ -f "$STATE_FILE" ]]; }

state_init() {
  local hostname="$1" domain="$2"
  local ts
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  cat > "$STATE_FILE" <<EOF
bootstrap_version: $STATE_VERSION
hostname: "$hostname"
domain: "$domain"
installed_at: "$ts"
enabled: []
profiles: []
EOF
}

# state_get FIELD (top-level scalar) -> value or empty
state_get() {
  state_exists || { printf ''; return 0; }
  _state_detect_backend
  local field="$1"
  case "$_state_backend" in
    yq)
      yq -r ".${field} // \"\"" "$STATE_FILE"
      ;;
    python)
      python3 - "$STATE_FILE" "$field" <<'PY'
import sys, yaml
with open(sys.argv[1]) as f: d = yaml.safe_load(f) or {}
v = d.get(sys.argv[2], '')
if v is None: v = ''
if isinstance(v, (list, dict)):
    import json; print(json.dumps(v))
else:
    print(v)
PY
      ;;
  esac
}

# state_list_enabled -> newline-separated
state_list_enabled() {
  state_exists || return 0
  _state_detect_backend
  case "$_state_backend" in
    yq)     yq -r '.enabled[]?' "$STATE_FILE" ;;
    python) python3 - "$STATE_FILE" <<'PY'
import sys, yaml
with open(sys.argv[1]) as f: d = yaml.safe_load(f) or {}
for x in d.get('enabled') or []: print(x)
PY
      ;;
  esac
}

state_list_profiles() {
  state_exists || return 0
  _state_detect_backend
  case "$_state_backend" in
    yq)     yq -r '.profiles[]?' "$STATE_FILE" ;;
    python) python3 - "$STATE_FILE" <<'PY'
import sys, yaml
with open(sys.argv[1]) as f: d = yaml.safe_load(f) or {}
for x in d.get('profiles') or []: print(x)
PY
      ;;
  esac
}

state_is_enabled() {
  local pkg="$1"
  state_list_enabled | grep -qx "$pkg"
}

# state_set_enabled PKG1 PKG2 ...
state_set_enabled() {
  state_exists || die "state file missing; run bootstrap.sh first"
  _state_detect_backend
  local pkgs=("$@")
  local json_arr="["
  local first=1
  for p in "${pkgs[@]}"; do
    [[ $first -eq 1 ]] || json_arr+=","
    json_arr+="\"$p\""; first=0
  done
  json_arr+="]"
  case "$_state_backend" in
    yq)
      yq -i ".enabled = $json_arr" "$STATE_FILE"
      ;;
    python)
      python3 - "$STATE_FILE" "$json_arr" <<'PY'
import sys, yaml, json
path, arr = sys.argv[1], json.loads(sys.argv[2])
with open(path) as f: d = yaml.safe_load(f) or {}
d['enabled'] = arr
with open(path, 'w') as f: yaml.safe_dump(d, f, sort_keys=False)
PY
      ;;
  esac
}

state_set_profiles() {
  state_exists || die "state file missing; run bootstrap.sh first"
  _state_detect_backend
  local json_arr="["
  local first=1
  for p in "$@"; do
    [[ $first -eq 1 ]] || json_arr+=","
    json_arr+="\"$p\""; first=0
  done
  json_arr+="]"
  case "$_state_backend" in
    yq)
      yq -i ".profiles = $json_arr" "$STATE_FILE"
      ;;
    python)
      python3 - "$STATE_FILE" "$json_arr" <<'PY'
import sys, yaml, json
path, arr = sys.argv[1], json.loads(sys.argv[2])
with open(path) as f: d = yaml.safe_load(f) or {}
d['profiles'] = arr
with open(path, 'w') as f: yaml.safe_dump(d, f, sort_keys=False)
PY
      ;;
  esac
}

state_enable() {
  local pkg="$1"
  local -a cur=()
  local x
  while IFS= read -r x; do [[ -n "$x" ]] && cur+=("$x"); done < <(state_list_enabled)
  for x in "${cur[@]}"; do [[ "$x" == "$pkg" ]] && return 0; done
  cur+=("$pkg")
  state_set_enabled "${cur[@]}"
}

state_disable() {
  local pkg="$1"
  local -a cur=() out=()
  local x
  while IFS= read -r x; do [[ -n "$x" ]] && cur+=("$x"); done < <(state_list_enabled)
  for x in "${cur[@]}"; do [[ "$x" == "$pkg" ]] || out+=("$x"); done
  if [[ ${#out[@]} -eq 0 ]]; then
    state_set_enabled  # empty
  else
    state_set_enabled "${out[@]}"
  fi
}
