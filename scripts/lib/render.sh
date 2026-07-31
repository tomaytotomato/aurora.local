#!/usr/bin/env bash
# scripts/lib/render.sh
#
# Rendering helpers that stitch per-package fragments together into
# the runtime files that `packages/core/` bind-mounts:
#
#   * data/caddy/snippets/<pkg>.caddy   — per-package Caddy vhosts
#   * packages/core/homepage/config/services.yaml — base + fragments
#   * data/identity/authelia/users_database.yml — seeded from example
#
# Called by scripts/up.sh. All functions idempotent and safe to re-run.

# shellcheck source=log.sh
[[ -z "${_LOG_SH_LOADED:-}" ]] && . "${BASH_SOURCE%/*}/log.sh"

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
# render_homepage_services <pkg> [<pkg>...]
#
# Writes packages/core/homepage/config/services.yaml =
#   services.base.yaml + every enabled package's homepage.yml
# --------------------------------------------------------------------
render_homepage_services() {
  local pkgs=("$@")
  local cfg="$REPO/packages/core/homepage/config"
  local base="$cfg/services.base.yaml"
  local out="$cfg/services.yaml"

  [[ -f "$base" ]] || { log_warn "missing services.base.yaml; skipping homepage merge"; return 0; }

  {
    cat "$base"
    local p frag
    for p in "${pkgs[@]}"; do
      frag="$REPO/packages/$p/homepage.yml"
      [[ -f "$frag" ]] || continue
      printf '\n# ---- %s -----------------------------------------------\n' "$p"
      cat "$frag"
    done
  } > "$out"
}

# --------------------------------------------------------------------
# render_identity_seed
#
# If identity is enabled, seed data/identity/authelia/users_database.yml
# from the checked-in example (only if the real file doesn't exist).
# --------------------------------------------------------------------
render_identity_seed() {
  local pkgs=("$@")
  local want=0
  local p
  for p in "${pkgs[@]}"; do [[ "$p" == "identity" ]] && want=1; done
  [[ $want -eq 1 ]] || return 0

  local src="$REPO/packages/identity/authelia/users_database.example.yml"
  local dst_dir="$REPO/data/identity/authelia"
  local dst="$dst_dir/users_database.yml"

  mkdir -p "$dst_dir"
  if [[ -f "$src" && ! -f "$dst" ]]; then
    log_info "seeding data/identity/authelia/users_database.yml from example"
    install -m 0640 "$src" "$dst"
    log_warn "IMPORTANT: replace the example password hash in $dst"
    log_warn "generate one with:  docker run --rm authelia/authelia:latest \\"
    log_warn "                       authelia crypto hash generate argon2 --password 'yourpass'"
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
      # shellcheck disable=SC1090
      set -a; . "$pins"; set +a
      log_info "loaded pinned digests from $p/pins.env"
    fi
  done
}

# --------------------------------------------------------------------
# render_all <pkg> [<pkg>...]
#
# One-call convenience wrapper for scripts/up.sh.
# --------------------------------------------------------------------
render_all() {
  render_caddy_snippets "$@"
  render_homepage_services "$@"
  render_identity_seed "$@"
  render_pins "$@"
}
