-- B4-followup (iter-23): security finding dismissal / snooze table.
-- Referenced by SecurityDismissalRepo + SecurityFindingsService so the
-- posture view can filter out findings the operator explicitly said 'not
-- now' to. Kept minimal — no per-user column because Aurora has one
-- admin (v0.1 shape).

CREATE TABLE IF NOT EXISTS security_dismissal (
  finding_id   TEXT PRIMARY KEY,
  -- ISO-8601 UTC. Never null — every dismissal records when it started.
  dismissed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  -- ISO-8601 UTC. Null = permanent dismissal (rare; keep visible in a
  -- future settings page rather than let it silently rot).
  expires_at   TEXT,
  -- Free-text reason the operator gave, if any. Not required.
  reason       TEXT
);

CREATE INDEX IF NOT EXISTS idx_security_dismissal_expires_at
  ON security_dismissal(expires_at);
