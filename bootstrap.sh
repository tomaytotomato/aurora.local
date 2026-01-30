#!/usr/bin/env bash
# home.local bootstrap
#
# Turns a fresh Debian/Ubuntu box into a home.local server:
#   1. Installs ansible + git
#   2. Runs the host/ site.yml against localhost
#   3. Brings up the packages listed in ENABLE_PACKAGES
#
# Usage on a new box:
#   curl -fsSL https://raw.githubusercontent.com/tomaytotomato/home.local/main/bootstrap.sh | bash
#
# Or clone-and-run:
#   git clone git@github.com:tomaytotomato/home.local.git ~/home.local
#   cd ~/home.local && ./bootstrap.sh

set -euo pipefail

REPO_URL="${REPO_URL:-git@github.com:tomaytotomato/home.local.git}"
REPO_DIR="${REPO_DIR:-$HOME/home.local}"
ENABLE_PACKAGES="${ENABLE_PACKAGES:-core privacy media storage}"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERR\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] && die "run as your normal user; sudo is invoked where needed"

log "installing prerequisites (git, ansible, python3-apt)"
sudo apt-get update -qq
sudo apt-get install -y -qq git ansible python3-apt

if [[ ! -d "$REPO_DIR/.git" ]]; then
  log "cloning $REPO_URL -> $REPO_DIR"
  git clone "$REPO_URL" "$REPO_DIR"
fi
cd "$REPO_DIR"

log "running host bootstrap (ansible)"
ansible-playbook -i inventory.example.ini host/site.yml --connection=local --limit localhost -K

log "bringing up packages: $ENABLE_PACKAGES"
./scripts/up.sh $ENABLE_PACKAGES

log "done. homepage should be reachable on http://home.local/"
