#!/usr/bin/env bash
# aurora.local / scripts/lib/ops.sh
#
# Shared helpers for the OPS scripts (doctor, health, backup, pin,
# rotate-secrets). This file intentionally lives alongside a future
# scripts/lib/installer.sh (owned by the installer worker): keep
# ops-only functions here so the two libraries can evolve without
# stepping on each other. Both may be sourced together.
#
# Sourced with: `. "$(dirname "$0")/lib/ops.sh"`
#
# All functions are safe under `set -euo pipefail`.

# Guard against double-sourcing.
if [[ -n "${_HOME_LOCAL_OPS_LIB_LOADED:-}" ]]; then
  return 0
fi
_HOME_LOCAL_OPS_LIB_LOADED=1

# ---- paths -----------------------------------------------------------
# Callers set REPO before sourcing, or we infer it from BASH_SOURCE.
REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
export REPO

# ---- pretty logging --------------------------------------------------
_ops_isatty() { [[ -t 1 ]]; }
if _ops_isatty; then
  _C_BLUE=$'\033[1;34m'; _C_GREEN=$'\033[1;32m'
  _C_YELLOW=$'\033[1;33m'; _C_RED=$'\033[1;31m'
  _C_DIM=$'\033[2m'; _C_RESET=$'\033[0m'
else
  _C_BLUE=""; _C_GREEN=""; _C_YELLOW=""; _C_RED=""; _C_DIM=""; _C_RESET=""
fi

log()  { printf '%s==>%s %s\n' "$_C_BLUE"   "$_C_RESET" "$*"; }
ok()   { printf '%s OK%s %s\n' "$_C_GREEN"  "$_C_RESET" "$*"; }
warn() { printf '%sWARN%s %s\n' "$_C_YELLOW" "$_C_RESET" "$*" >&2; }
err()  { printf '%sERR %s %s\n' "$_C_RED"    "$_C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }
dim()  { printf '%s%s%s\n' "$_C_DIM" "$*" "$_C_RESET"; }

# ---- package discovery ----------------------------------------------

# list_all_packages: every directory under packages/ that has a compose.yml
# and is not the template.
list_all_packages() {
  local d name
  for d in "$REPO"/packages/*/; do
    [[ -f "$d/compose.yml" ]] || continue
    name="$(basename "$d")"
    [[ "$name" == _* ]] && continue
    printf '%s\n' "$name"
  done
}

# list_enabled_packages: read .state.yml if it exists (installer writes
# it), otherwise fall back to the historical default set.
list_enabled_packages() {
  local state="$REPO/.state.yml"
  if [[ -f "$state" ]]; then
    # Very small YAML parser: expect `enabled:` list with `- name` items.
    # Deliberately dependency-free.
    awk '
      /^enabled:/         { in_list=1; next }
      in_list && /^[[:space:]]*-/ {
        sub(/^[[:space:]]*-[[:space:]]*/, "", $0)
        sub(/[[:space:]]*#.*/, "", $0)
        gsub(/["'\'']/, "", $0)
        if ($0 != "") print $0
        next
      }
      in_list && /^[^[:space:]-]/ { in_list=0 }
    ' "$state"
    return
  fi
  # Fallback: original four.
  printf 'core\nprivacy\nmedia\nstorage\n'
}

# list_compose_files_for pkg [pkg...]  -> emits `-f path -f path` args
list_compose_files_for() {
  local p f args=()
  for p in "$@"; do
    f="$REPO/packages/$p/compose.yml"
    [[ -f "$f" ]] || { err "no compose.yml for package: $p"; return 1; }
    args+=(-f "$f")
  done
  printf '%s\n' "${args[@]}"
}

# manifest_field pkg field  -> prints the top-level scalar field or empty.
manifest_field() {
  local field="$2" m="$REPO/packages/$1/manifest.yml"
  [[ -f "$m" ]] || return 0
  awk -v k="$field" '
    $0 ~ "^"k":" { sub("^"k":[[:space:]]*", ""); sub(/[[:space:]]*#.*/, ""); print; exit }
  ' "$m"
}

# load_env_file path  -> `set -a` sources a file if it exists.
load_env_file() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  set -a
  # shellcheck source=/dev/null
  . "$f"
  set +a
}

# load_group_vars: source group_vars/all.yml as best-effort KEY=VALUE lines.
# Only picks up simple `key: value` scalars; we don't need nested state.
load_group_vars() {
  local f="$REPO/group_vars/all.yml"
  [[ -f "$f" ]] || return 0
  while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^([a-zA-Z_][a-zA-Z0-9_]*):[[:space:]]*(.+)$ ]] || continue
    local k="${BASH_REMATCH[1]}" v="${BASH_REMATCH[2]}"
    v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
    v="${v%%#*}"; v="${v%"${v##*[![:space:]]}"}"
    # shellcheck disable=SC2163
    export "$k=$v"
  done < "$f"
}

# require_cmd cmd [cmd...]  -> die if any is missing.
require_cmd() {
  local missing=() c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || missing+=("$c")
  done
  if (( ${#missing[@]} > 0 )); then
    die "missing required commands: ${missing[*]}"
  fi
}

# has_cmd cmd -> 0 if present, 1 otherwise. Never dies.
has_cmd() { command -v "$1" >/dev/null 2>&1; }
