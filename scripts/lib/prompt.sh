# shellcheck shell=bash
# aurora.local / scripts/lib/prompt.sh
#
# Interactive prompt helpers. Uses whiptail when available on a TTY,
# falls back to plain read otherwise. All functions echo their result
# on stdout; log/chrome goes to stderr.

[[ -n "${_HOMELOCAL_PROMPT_SH:-}" ]] && return 0
_HOMELOCAL_PROMPT_SH=1

# Force headless: HOMELOCAL_NONINTERACTIVE=1
_prompt_has_whiptail() {
  [[ -z "${HOMELOCAL_NONINTERACTIVE:-}" ]] \
    && [[ -t 0 && -t 1 ]] \
    && command -v whiptail >/dev/null 2>&1
}

# prompt_inputbox TITLE MESSAGE DEFAULT
prompt_inputbox() {
  local title="$1" msg="$2" default="${3:-}"
  if _prompt_has_whiptail; then
    whiptail --title "$title" --inputbox "$msg" 10 70 "$default" 3>&1 1>&2 2>&3
  else
    local reply
    printf '%s\n' "$title" >&2
    printf '  %s [%s]: ' "$msg" "$default" >&2
    IFS= read -r reply || reply=""
    printf '%s' "${reply:-$default}"
  fi
}

# prompt_yesno TITLE MESSAGE DEFAULT(yes|no)
# Returns 0 for yes, 1 for no.
prompt_yesno() {
  local title="$1" msg="$2" default="${3:-yes}"
  if _prompt_has_whiptail; then
    local flag=""
    [[ "$default" == "no" ]] && flag="--defaultno"
    # shellcheck disable=SC2086
    whiptail --title "$title" $flag --yesno "$msg" 10 70
  else
    local reply
    printf '%s\n' "$title" >&2
    printf '  %s [%s/%s]: ' "$msg" \
      "$([[ $default == yes ]] && echo Y || echo y)" \
      "$([[ $default == no  ]] && echo N || echo n)" >&2
    IFS= read -r reply || reply=""
    reply="${reply:-$default}"
    [[ "$reply" =~ ^([Yy]|yes|YES)$ ]]
  fi
}

# prompt_menu TITLE MESSAGE tag1 label1 tag2 label2 ...
# Echoes the selected tag on stdout.
prompt_menu() {
  local title="$1" msg="$2"; shift 2
  if _prompt_has_whiptail; then
    whiptail --title "$title" --menu "$msg" 20 78 12 "$@" 3>&1 1>&2 2>&3
  else
    printf '%s\n%s\n' "$title" "$msg" >&2
    local i=1 tag_list=()
    while [[ $# -gt 0 ]]; do
      printf '  %d) %s — %s\n' "$i" "$1" "$2" >&2
      tag_list+=("$1"); shift 2; ((i++))
    done
    local reply
    printf 'Choose [1]: ' >&2
    IFS= read -r reply || reply=""
    reply="${reply:-1}"
    printf '%s' "${tag_list[$((reply-1))]}"
  fi
}

# prompt_checklist TITLE MESSAGE tag1 label1 on|off tag2 label2 on|off ...
# Echoes selected tags space-separated on stdout.
prompt_checklist() {
  local title="$1" msg="$2"; shift 2
  if _prompt_has_whiptail; then
    # whiptail returns tags quoted, e.g. "core" "media"
    local result
    result=$(whiptail --title "$title" --checklist "$msg" 22 78 12 "$@" 3>&1 1>&2 2>&3) \
      || return 1
    # strip surrounding quotes on each token
    printf '%s' "$result" | tr -d '"'
  else
    printf '%s\n%s\n' "$title" "$msg" >&2
    printf '  (comma or space separated tags; * = default on)\n' >&2
    local tags=() defaults=()
    while [[ $# -gt 0 ]]; do
      local tag="$1" label="$2" onoff="$3"
      shift 3
      tags+=("$tag")
      [[ "$onoff" == "on" ]] && defaults+=("$tag")
      local marker=" "; [[ "$onoff" == "on" ]] && marker="*"
      printf '  [%s] %-14s %s\n' "$marker" "$tag" "$label" >&2
    done
    local default_str="${defaults[*]}"
    local reply
    printf 'Select [%s]: ' "$default_str" >&2
    IFS= read -r reply || reply=""
    reply="${reply:-$default_str}"
    # normalize: commas -> spaces
    printf '%s' "${reply//,/ }"
  fi
}
