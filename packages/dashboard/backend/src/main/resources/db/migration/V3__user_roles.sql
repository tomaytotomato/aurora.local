-- Aurora dashboard: roles and sign-in metadata for admin_user.
--
-- Aurora is single-admin at first boot (the onboarding bootstrap), but a
-- home server is often shared with a partner or a housemate, so the
-- dashboard needs a small set of people with different reach. Three
-- levels, not a permission matrix: admin, operator, viewer.
--
-- SQLite has no ADD COLUMN IF NOT EXISTS, so re-running this on a
-- database that already has the columns fails. That is why
-- spring.sql.init.continue-on-error is true in production — the same
-- reason it was already set for V1 and V2. Tests run against a fresh
-- in-memory database, so they can and do keep it strict.

-- Existing rows are the box's original admin and must stay one, or the
-- upgrade would lock the owner out of their own dashboard.
ALTER TABLE admin_user ADD COLUMN role TEXT NOT NULL DEFAULT 'admin';

-- Null until they actually sign in. The Users page renders "never", and a
-- fabricated timestamp would be worse than an honest absence.
ALTER TABLE admin_user ADD COLUMN last_login_at TEXT;

-- Passkey enrolment is not built yet; the column exists so the API can
-- report the truth (false) rather than omitting the field the frontend
-- already reads.
ALTER TABLE admin_user ADD COLUMN passkey_enrolled INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_admin_user_role ON admin_user(role);
