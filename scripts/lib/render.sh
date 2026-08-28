#!/usr/bin/env bash
# scripts/lib/render.sh
#
# Rendering helpers that stitch per-package fragments together into
# the runtime files that `packages/core/` bind-mounts:
#
#   * data/caddy/snippets/<pkg>.caddy   — per-package Caddy vhosts
#   * data/authelia/users_database.yml  — seeded from example (core/SSO)
#
# Called by scripts/up.sh. All functions idempotent and safe to re-run.

[[ -n "${_HOMELOCAL_RENDER_SH:-}" ]] && return 0
_HOMELOCAL_RENDER_SH=1

# shellcheck source=log.sh
[[ -n "${_HOMELOCAL_LOG_SH:-}" ]] || . "${BASH_SOURCE%/*}/log.sh"

# --------------------------------------------------------------------
# render_caddy_snippets <pkg> [<pkg>...]
#
# Materialises every enabled package's caddy.snippet into
# $REPO/data/caddy/snippets/<pkg>.caddy. Prunes stale snippets from
# packages no longer in the enabled set.
# --------------------------------------------------------------------
render_caddy_snippets() {
  local pkgs=("$@")
  local dst="$REPO/data/caddy/snippets"
  mkdir -p "$dst"

  # Track wanted files so we can prune.
  local -A wanted=()
  local p src
  for p in "${pkgs[@]}"; do
    src="$REPO/packages/$p/caddy.snippet"
    [[ -f "$src" ]] || continue
    install -m 0644 "$src" "$dst/$p.caddy"
    wanted[$p.caddy]=1
  done

  # Prune snippets whose package is no longer enabled.
  local f base
  for f in "$dst"/*.caddy; do
    [[ -e "$f" ]] || break
    base="${f##*/}"
    if [[ -z "${wanted[$base]:-}" ]]; then
      rm -f "$f"
      log_info "pruned stale caddy snippet: $base"
    fi
  done
}

# --------------------------------------------------------------------
# render_authelia_seed
#
# Authelia ships in core (SSO always-on), so whenever core is in the
# enabled set seed data/authelia/users_database.yml from the checked-in
# example (only if the real file doesn't exist). This lets Authelia boot
# before Aurora projects real users over it (it crash-loops on an empty
# users: map).
# --------------------------------------------------------------------
render_authelia_seed() {
  local pkgs=("$@")
  local want=0
  local p
  for p in "${pkgs[@]}"; do [[ "$p" == "core" ]] && want=1; done
  [[ $want -eq 1 ]] || return 0

  local src="$REPO/packages/core/authelia/users_database.example.yml"
  local dst_dir="$REPO/data/authelia"
  local dst="$dst_dir/users_database.yml"

  mkdir -p "$dst_dir"
  if [[ -f "$src" && ! -f "$dst" ]]; then
    log_info "seeding data/authelia/users_database.yml from example"
    install -m 0640 "$src" "$dst"
    # No scary IMPORTANT block here any more. This placeholder exists only
    # so Authelia can boot (it crash-loops on an empty users: map) and it is
    # overwritten by AutheliaService the moment the wizard creates the real
    # admin, minutes later in the same install. Telling the operator to go
    # and run `docker run --rm authelia/authelia crypto hash generate argon2`
    # was both terminal-first and untrue: nobody has to do it.
    log_info "placeholder sign-in file written; the wizard replaces it with your admin account"
  fi
}

# --------------------------------------------------------------------
# render_pins <pkg> [<pkg>...]
#
# Sources any packages/<pkg>/pins.env into the shell env so pinned
# image digests take effect in compose interpolation. See scripts/pin.sh.
# --------------------------------------------------------------------
render_pins() {
  local p pins
  for p in "$@"; do
    pins="$REPO/packages/$p/pins.env"
    if [[ -f "$pins" ]]; then
      set -a
      # shellcheck source=/dev/null
      . "$pins"
      set +a
      log_info "loaded pinned digests from $p/pins.env"
    fi
  done
}

# --------------------------------------------------------------------
# render_stalwart_config
#
# Stalwart v0.16 boots into a setup WIZARD unless a config.json exists at
# /etc/stalwart describing its datastore. Aurora seeds that file pointing
# at the shared core-db (Postgres), so Stalwart comes up already
# configured — no operator wizard. See docs/CORE_SHARED_SERVICES_PLAN.md.
#
# The template carries no secret: the DB password is read at runtime from
# the STALWART_DB_PASSWORD env var (authSecret.@type = EnvironmentVariable).
# Only seeds when the file is absent, so a config Stalwart itself rewrote
# after first-run provisioning is never clobbered.
# --------------------------------------------------------------------
render_stalwart_config() {
  local pkgs=("$@")
  local want=0
  local p
  for p in "${pkgs[@]}"; do [[ "$p" == "core" ]] && want=1; done
  [[ $want -eq 1 ]] || return 0

  local src="$REPO/packages/core/stalwart/config.template.json"
  local dst_dir="$REPO/data/stalwart/etc"
  local dst="$dst_dir/config.json"

  mkdir -p "$dst_dir"
  if [[ -f "$src" && ! -f "$dst" ]]; then
    log_info "seeding data/stalwart/etc/config.json (datastore -> core-db), skips the setup wizard"
    install -m 0644 "$src" "$dst"
  fi
}

# --------------------------------------------------------------------
# render_all <pkg> [<pkg>...]
#
# One-call convenience wrapper for scripts/up.sh.
# --------------------------------------------------------------------
render_all() {
  render_caddy_snippets "$@"
  render_authelia_seed "$@"
  render_stalwart_config "$@"
  render_pins "$@"
}
