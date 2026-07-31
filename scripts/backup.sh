#!/usr/bin/env bash
# aurora.local / scripts/backup.sh
#
# Snapshot the *configuration* of an aurora.local box into a timestamped
# tarball. Deliberately does NOT back up the giant media/library data;
# only the state that would be painful to recreate: env files, ansible
# vars, homepage/caddy config, and each package's small config bind
# mounts (the *config* subdir of packages/*/data/*/ — see EXCLUDES).
#
# Bulk media, sonarr's Media/ dir, jellyfin's transcodes, etc. are all
# excluded by pattern; back those up with restic/borg separately.
#
# Optional: if RCLONE_REMOTE is set (e.g. b2:mybucket/aurora.local), the
# resulting tarball is pushed with `rclone copy`.
#
# Retention: keeps the most recent $KEEP backups (default 14).
#
# Usage:
#   ./scripts/backup.sh                          # write to $BACKUP_DIR
#   BACKUP_DIR=/mnt/backup ./scripts/backup.sh   # override dest
#   KEEP=7 ./scripts/backup.sh                   # keep 7 most recent
#   RCLONE_REMOTE=b2:aurora.local ./scripts/backup.sh
#   ./scripts/backup.sh --dry-run                # list what'd be included

set -euo pipefail

# shellcheck source=lib/ops.sh
. "$(dirname "$0")/lib/ops.sh"

BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/aurora.local}"
KEEP="${KEEP:-14}"
RCLONE_REMOTE="${RCLONE_REMOTE:-}"
DRY_RUN=0

for a in "$@"; do
  case "$a" in
    --dry-run|-n) DRY_RUN=1 ;;
    -h|--help)    sed -n '2,25p' "$0"; exit 0 ;;
    *)            die "unknown arg: $a" ;;
  esac
done

# What to INCLUDE (relative to $REPO). Missing entries are silently skipped.
INCLUDES=(
  "group_vars/all.yml"
  "inventory.ini"
  ".state.yml"
)
# Glob-includes evaluated at pack time.
GLOB_INCLUDES=(
  "packages/*/.env"
  "packages/core/homepage/config"
  "packages/core/caddy/Caddyfile"
  "packages/privacy/adguard/rewrites.yaml"
  "packages/*/data/*/config"
  "packages/*/data/*/conf"
)
# What to EXCLUDE (tar --exclude patterns). Applied to the whole archive.
EXCLUDES=(
  "*/log"
  "*/logs"
  "*/Logs"
  "*/MediaCover"
  "*/Backups"
  "*/cache"
  "*/Cache"
  "*/transcodes"
  "*/metadata"
  "*/thumbnails"
  "*/data/caddy/data"     # caddy PKI is regeneratable
  "*.log"
  "*.log.*"
  "*.db-shm"
  "*.db-wal"
)

mkdir -p "$BACKUP_DIR"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
host="$(hostname -s 2>/dev/null || echo host)"
out="$BACKUP_DIR/aurora.local-$host-$ts.tar.gz"

# Assemble the list of items that actually exist.
cd "$REPO"
paths=()
for p in "${INCLUDES[@]}"; do
  [[ -e "$p" ]] && paths+=("$p")
done
for g in "${GLOB_INCLUDES[@]}"; do
  # shellcheck disable=SC2206
  matches=( $g )
  for m in "${matches[@]}"; do
    [[ -e "$m" ]] && paths+=("$m")
  done
done

if (( ${#paths[@]} == 0 )); then
  die "nothing to back up under $REPO"
fi

log "backup source: $REPO"
log "backup target: $out"
log "keep: $KEEP most recent"
[[ -n "$RCLONE_REMOTE" ]] && log "rclone remote: $RCLONE_REMOTE"
echo
dim "included paths:"
printf '  %s\n' "${paths[@]}"
echo
dim "exclude patterns:"
printf '  %s\n' "${EXCLUDES[@]}"
echo

if (( DRY_RUN )); then
  log "dry-run: not writing archive"
  exit 0
fi

require_cmd tar
tar_excludes=()
for e in "${EXCLUDES[@]}"; do tar_excludes+=(--exclude="$e"); done

# Sudo may be needed if adguard config is root-owned; try without first.
if tar -czf "$out" "${tar_excludes[@]}" "${paths[@]}" 2>/dev/null; then
  :
else
  warn "plain tar failed (permissions?); retrying with sudo"
  sudo tar -czf "$out" "${tar_excludes[@]}" "${paths[@]}"
  sudo chown "$(id -u):$(id -g)" "$out"
fi

size=$(du -h "$out" | cut -f1)
count=$(tar -tzf "$out" | wc -l)
ok "wrote $out ($size, $count entries)"

# ---- retention -------------------------------------------------------
if (( KEEP > 0 )); then
  # shellcheck disable=SC2012
  mapfile -t old < <(ls -1t "$BACKUP_DIR"/aurora.local-*.tar.gz 2>/dev/null | tail -n +$((KEEP+1)))
  if (( ${#old[@]} > 0 )); then
    log "pruning ${#old[@]} old backup(s)"
    for f in "${old[@]}"; do
      dim "  rm $f"
      rm -f "$f"
    done
  fi
fi

# ---- remote push -----------------------------------------------------
if [[ -n "$RCLONE_REMOTE" ]]; then
  if has_cmd rclone; then
    log "rclone copy -> $RCLONE_REMOTE"
    rclone copy "$out" "$RCLONE_REMOTE" --progress
    ok "uploaded"
  else
    warn "RCLONE_REMOTE set but rclone not installed"
  fi
fi
