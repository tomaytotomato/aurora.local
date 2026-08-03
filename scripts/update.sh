#!/usr/bin/env bash
# aurora.local / scripts/update.sh
#
# Pull latest images and re-up. Use this as a scheduled cronjob if
# you're the "auto-update" type, or run manually.

set -euo pipefail

cd "$(dirname "$0")/.."
exec ./scripts/up.sh "$@"
