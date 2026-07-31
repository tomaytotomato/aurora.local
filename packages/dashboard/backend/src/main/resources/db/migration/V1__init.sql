-- Aurora dashboard v0.1 initial schema.
-- SQLite; all timestamps UTC ISO-8601 strings.
-- IF NOT EXISTS on every object so spring.sql.init can run this repeatedly.

CREATE TABLE IF NOT EXISTS admin_user (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT    NOT NULL UNIQUE,
  password_hash TEXT    NOT NULL,
  tz            TEXT    NOT NULL DEFAULT 'UTC',
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS audit_event (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  ts        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  user_id   INTEGER,
  action    TEXT    NOT NULL,
  target    TEXT,
  diff_json TEXT,
  FOREIGN KEY (user_id) REFERENCES admin_user(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_event_ts ON audit_event(ts DESC);

CREATE TABLE IF NOT EXISTS metric_sample (
  ts    TEXT NOT NULL,
  name  TEXT NOT NULL,
  value REAL NOT NULL,
  PRIMARY KEY (ts, name)
);
CREATE INDEX IF NOT EXISTS idx_metric_sample_name_ts ON metric_sample(name, ts DESC);

-- Simple k/v for onboarding progress + future toggles.
CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
