-- Outbound notification channels (packages/dashboard notifications domain).
--
-- Three channel kinds — ntfy, discord, generic webhook — each with a set
-- of events it fires on. Email is deliberately not a channel kind: see
-- openapi.yaml's comment on the /notifications/channels path.

CREATE TABLE IF NOT EXISTS notification_channel (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL,
  name         TEXT NOT NULL,
  target       TEXT NOT NULL,
  -- Comma-separated NotifyEvent values. A real join table would be more
  -- "correct" for a handful of enum rows per channel on a single-writer
  -- SQLite database; not worth the extra table.
  events       TEXT NOT NULL,
  enabled      INTEGER NOT NULL DEFAULT 1,
  last_sent_at TEXT,
  last_result  TEXT,
  last_error   TEXT
);

-- What Aurora has actually sent, kept even after the channel that sent it
-- is deleted — this is a record of history, not a mirror of current
-- config.
CREATE TABLE IF NOT EXISTS notification_delivery (
  id         TEXT PRIMARY KEY,
  channel_id TEXT NOT NULL,
  event      TEXT NOT NULL,
  subject    TEXT NOT NULL,
  sent_at    TEXT NOT NULL,
  result     TEXT NOT NULL,
  error      TEXT
);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_sent_at ON notification_delivery(sent_at DESC);
