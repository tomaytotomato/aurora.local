#!/bin/sh
# aurora.local / packages/core / core-db init
#
# Runs once, on first initialisation of the core-db Postgres data
# directory (postgres:*-alpine executes every *.sh / *.sql in
# /docker-entrypoint-initdb.d/ exactly once, when PGDATA is empty). It
# provisions one database + one owner role per core app that shares the
# instance — the "common core DB" from docs/CORE_SHARED_SERVICES_PLAN.md.
#
# Passwords come from env (POSTGRES_* + the per-app *_DB_PASSWORD), which
# Aurora seeds into packages/core/.env on first `up.sh` via
# rotate-secrets.sh --apply (the keys match its *_PASSWORD secret hint).
#
# Schemas stay app-owned: Authelia and Stalwart each own their own
# database on the shared instance; this script never touches their tables,
# only creates the empty database + role for them to populate.
#
# Idempotent-by-construction: it only runs when the data dir is fresh, so
# a restart never re-runs it. The DO blocks are still guarded so a manual
# re-run is harmless.
set -eu

# psql runs as the superuser against the default DB during init.
provision() {
  db="$1"
  user="$2"
  pass="$3"
  if [ -z "$pass" ]; then
    echo "core-db init: WARNING no password for '$user' — skipping (set ${4} in packages/core/.env)" >&2
    return 0
  fi
  echo "core-db init: provisioning database '$db' owned by '$user'"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${user}') THEN
    CREATE ROLE ${user} LOGIN PASSWORD '${pass}';
  ELSE
    ALTER ROLE ${user} WITH LOGIN PASSWORD '${pass}';
  END IF;
END
\$\$;
SQL
  # CREATE DATABASE cannot run inside the DO block / a transaction, and
  # has no IF NOT EXISTS — gate it with a psql-side check.
  if ! psql -tAc "SELECT 1 FROM pg_database WHERE datname='${db}'" \
        --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" | grep -q 1; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
      -c "CREATE DATABASE ${db} OWNER ${user};"
  fi
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -c "GRANT ALL PRIVILEGES ON DATABASE ${db} TO ${user};"
}

# One line per core app that shares the instance. Add a line here (and a
# *_DB_PASSWORD in .env.example) when a new core app joins the common DB.
provision "authelia" "authelia" "${AUTHELIA_DB_PASSWORD:-}" "AUTHELIA_DB_PASSWORD"
provision "stalwart" "stalwart" "${STALWART_DB_PASSWORD:-}" "STALWART_DB_PASSWORD"

echo "core-db init: done"
