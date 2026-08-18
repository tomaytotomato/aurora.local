# shellcheck shell=bash
# aurora.local / scripts/lib/manifest.sh
#
# Read packages/*/manifest.yml. Uses yq (mikefarah v4) if available,
# else falls back to python3 + PyYAML.
#
# Requires REPO (absolute repo root) set by caller.

[[ -n "${_HOMELOCAL_MANIFEST_SH:-}" ]] && return 0
_HOMELOCAL_MANIFEST_SH=1

# shellcheck source=./log.sh
. "$(dirname "${BASH_SOURCE[0]}")/log.sh"

: "${REPO:?manifest.sh requires REPO to be set}"

_manifest_backend=""
_manifest_detect_backend() {
  [[ -n "$_manifest_backend" ]] && return 0
  if command -v yq >/dev/null 2>&1 && yq --version 2>/dev/null | grep -qE 'version v?4'; then
    _manifest_backend="yq"
  elif command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' 2>/dev/null; then
    _manifest_backend="python"
  else
    die "need yq (mikefarah v4) or python3+PyYAML to parse manifests"
  fi
}

# manifest_path PKG -> absolute path or exit 1
manifest_path() {
  local pkg="$1"
  local p="$REPO/packages/$pkg/manifest.yml"
  [[ -f "$p" ]] || die "no manifest for package '$pkg' (looked for $p)"
  printf '%s' "$p"
}

# manifest_exists PKG -> 0 if the package is installed in this repo
#
# The question manifest_path answers by dying, asked in a form a caller
# can act on. Deliberately silent: callers decide whether an absent
# package is a fatal error or something to warn about and move past.
manifest_exists() {
  [[ -f "$REPO/packages/$1/manifest.yml" ]]
}

# manifest_filter_known PKG... -> the subset that exists, one per line,
# warning on stderr about each one that does not.
#
# A retired package leaves its name behind in .state.yml, and every path
# that reads that file used to hand the dangling name straight to
# manifest_path, which dies. One stale entry therefore made EVERY other
# package unmanageable: up.sh and down.sh both aborted before touching
# anything. Removing Forgejo demonstrated it, and it recurs every time a
# package is retired.
#
# Warn and skip instead. Callers that end up with nothing left should say
# so themselves — an empty set means something different to `up` than it
# does to `down`.
#
# Note the trade-off: a mistyped name on the command line is warned about
# rather than rejected, so `up.sh mediaa` reports a warning and brings up
# nothing else it was not already asked for. Fail-fast on explicit
# arguments was considered and rejected as two behaviours to explain; the
# warning goes to stderr and names the package.
manifest_filter_known() {
  local pkg
  for pkg in "$@"; do
    if manifest_exists "$pkg"; then
      printf '%s\n' "$pkg"
    else
      log_warn "no such package '$pkg' (no packages/$pkg/manifest.yml) — skipping"
    fi
  done
}

# manifest_list_packages -> newline-separated pkg names (sorted, excludes _*)
manifest_list_packages() {
  local d
  for d in "$REPO"/packages/*/manifest.yml; do
    [[ -f "$d" ]] || continue
    local name
    name=$(basename "$(dirname "$d")")
    [[ "$name" == _* ]] && continue
    printf '%s\n' "$name"
  done | sort
}

# manifest_get FIELD PKG -> scalar or empty
manifest_get() {
  _manifest_detect_backend
  local field="$1" pkg="$2"
  local file; file=$(manifest_path "$pkg")
  case "$_manifest_backend" in
    yq)
      yq -r ".${field} // \"\"" "$file"
      ;;
    python)
      python3 - "$file" "$field" <<'PY'
import sys, yaml
path, field = sys.argv[1], sys.argv[2]
with open(path) as f: d = yaml.safe_load(f) or {}
v = d
for part in field.split('.'):
    if isinstance(v, dict): v = v.get(part, '')
    else: v = ''
if v is None: v = ''
if isinstance(v, (dict, list)):
    import json; print(json.dumps(v))
else:
    print(v)
PY
      ;;
  esac
}

# manifest_list FIELD PKG -> newline-separated list items
manifest_list() {
  _manifest_detect_backend
  local field="$1" pkg="$2"
  local file; file=$(manifest_path "$pkg")
  case "$_manifest_backend" in
    yq)
      yq -r ".${field}[]?" "$file"
      ;;
    python)
      python3 - "$file" "$field" <<'PY'
import sys, yaml
path, field = sys.argv[1], sys.argv[2]
with open(path) as f: d = yaml.safe_load(f) or {}
v = d
for part in field.split('.'):
    if isinstance(v, dict): v = v.get(part)
    else: v = None
if isinstance(v, list):
    for x in v: print(x)
PY
      ;;
  esac
}

# manifest_deps PKG -> newline-separated direct deps
manifest_deps() {
  manifest_list depends_on "$1"
}

# manifest_recommends PKG
manifest_recommends() {
  manifest_list recommends "$1"
}

# manifest_resolve_deps PKG [PKG...] -> deps closure (topological-ish),
# newline-separated, unique, in dependency-first order. Errors on cycles
# only if a package appears in its own transitive deps.
manifest_resolve_deps() {
  local -A seen=()
  local -a order=()
  _resolve_one() {
    local pkg="$1"
    [[ -n "${seen[$pkg]:-}" ]] && return 0
    seen[$pkg]="visiting"
    local dep
    while IFS= read -r dep; do
      [[ -z "$dep" ]] && continue
      [[ "${seen[$dep]:-}" == "visiting" ]] && die "dependency cycle involving '$dep'"
      _resolve_one "$dep"
    done < <(manifest_deps "$pkg")
    seen[$pkg]="done"
    order+=("$pkg")
  }
  local p
  for p in "$@"; do _resolve_one "$p"; done
  printf '%s\n' "${order[@]}"
}

# manifest_ports PKG -> "port/proto description" lines (all profiles)
manifest_ports() {
  _manifest_detect_backend
  local pkg="$1"
  local file; file=$(manifest_path "$pkg")
  case "$_manifest_backend" in
    yq)
      yq -r '.ports[]? | (.port|tostring) + "/" + (.proto // "tcp") + " " + (.description // "")' "$file"
      ;;
    python)
      python3 - "$file" <<'PY'
import sys, yaml
with open(sys.argv[1]) as f: d = yaml.safe_load(f) or {}
for p in d.get('ports') or []:
    print(f"{p.get('port')}/{p.get('proto','tcp')} {p.get('description','')}")
PY
      ;;
  esac
}
