-- Hand-added reverse-proxy routes (packages/dashboard proxy domain).
--
-- Only holds routes an operator added by hand through the dashboard.
-- Package-generated ("managed") routes are never written here — they
-- are derived live from each enabled package's caddy.snippet by
-- ProxyService, so there is nothing to keep in sync when a package is
-- enabled, disabled, or its manifest changes.

CREATE TABLE IF NOT EXISTS proxy_route (
  id         TEXT PRIMARY KEY,
  subdomain  TEXT NOT NULL UNIQUE,
  target     TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
