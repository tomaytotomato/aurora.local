# shellcheck shell=bash
# home.local / scripts/lib/log.sh
#
# Color + TTY-aware logging helpers. Safe to source multiple times.

[[ -n "${_HOMELOCAL_LOG_SH:-}" ]] && return 0
_HOMELOCAL_LOG_SH=1

if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
  _c_reset=$'\033[0m'
  _c_blue=$'\033[1;34m'
  _c_green=$'\033[1;32m'
  _c_yellow=$'\033[1;33m'
  _c_red=$'\033[1;31m'
  _c_dim=$'\033[2m'
else
  _c_reset=""; _c_blue=""; _c_green=""; _c_yellow=""; _c_red=""; _c_dim=""
fi

log_step() { printf '%s==>%s %s\n'   "$_c_blue"   "$_c_reset" "$*" >&2; }
log_info() { printf '%s   %s%s\n'    "$_c_dim"    "$*"        "$_c_reset" >&2; }
log_ok()   { printf '%sOK%s  %s\n'   "$_c_green"  "$_c_reset" "$*" >&2; }
log_warn() { printf '%sWARN%s %s\n'  "$_c_yellow" "$_c_reset" "$*" >&2; }
log_err()  { printf '%sERR%s  %s\n'  "$_c_red"    "$_c_reset" "$*" >&2; }
die()      { log_err "$*"; exit 1; }
