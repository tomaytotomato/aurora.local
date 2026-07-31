#!/usr/bin/env bash
# home.local / scripts/status.sh
#
# Print a quick health snapshot: hostname, enabled packages, container
# state, listening ports (that we care about), disk usage on media_root.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export REPO

# shellcheck source=lib/log.sh
. "$REPO/scripts/lib/log.sh"
# shellcheck source=lib/manifest.sh
. "$REPO/scripts/lib/manifest.sh"
# shellcheck source=lib/state.sh
. "$REPO/scripts/lib/state.sh"

echo "==== host ===="
printf '  hostname: %s\n' "$(hostname)"
printf '  uptime:   %s\n' "$(uptime -p 2>/dev/null || uptime)"
printf '  kernel:   %s\n' "$(uname -sr)"

echo
echo "==== state ===="
if state_exists; then
  printf '  configured host:  %s\n' "$(state_get hostname)"
  printf '  domain:           %s\n' "$(state_get domain)"
  printf '  installed_at:     %s\n' "$(state_get installed_at)"
  mapfile -t enabled < <(state_list_enabled)
  printf '  enabled packages: %s\n' "${enabled[*]:-(none)}"
  mapfile -t profs < <(state_list_profiles)
  printf '  profiles:         %s\n' "${profs[*]:-(none)}"
else
  log_warn "no .state.yml — this host was not installed via bootstrap.sh"
  enabled=()
fi

echo
echo "==== docker ===="
if command -v docker >/dev/null 2>&1; then
  if [[ ${#enabled[@]} -gt 0 ]]; then
    files=()
    for p in "${enabled[@]}"; do
      f="$REPO/packages/$p/compose.yml"
      [[ -f "$f" ]] && files+=(-f "$f")
    done
    if [[ ${#files[@]} -gt 0 ]]; then
      docker compose -p home "${files[@]}" ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' \
        2>/dev/null || docker ps --filter label=com.docker.compose.project=home
    fi
  else
    docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' | head -20
  fi
else
  log_warn "docker not installed"
fi

echo
echo "==== declared ports (from manifests) ===="
if [[ ${#enabled[@]} -gt 0 ]]; then
  for p in "${enabled[@]}"; do
    printf '  [%s]\n' "$p"
    manifest_ports "$p" | sed 's/^/    /'
  done
else
  echo "  (no packages enabled)"
fi

echo
echo "==== disk ===="
mounts=(/ /home /mnt/media /var/lib/docker)
# also media_root from group_vars
if [[ -f "$REPO/group_vars/all.yml" ]]; then
  mr=$(grep -E '^media_root:' "$REPO/group_vars/all.yml" | awk '{print $2}')
  [[ -n "$mr" ]] && mounts+=("$mr")
fi
seen=""
for m in "${mounts[@]}"; do
  [[ -d "$m" ]] || continue
  case " $seen " in *" $m "*) continue ;; esac
  seen="$seen $m"
  df -h "$m" 2>/dev/null | awk -v m="$m" 'NR==2 {printf "  %-24s %5s used / %5s total (%s)\n", m, $3, $2, $5}'
done

echo
echo "==== memory ===="
free -h | awk 'NR==1{printf "  %-8s %8s %8s %8s\n",$1,$2,$3,$4} NR==2{printf "  %-8s %8s %8s %8s\n",$1,$2,$3,$4}'
