-- Phase D iter-2 — add role column to admin_user.
--
-- Aurora becomes multi-user with RBAC (admin | user | guest). Existing
-- rows created by the v0.1/v0.2 wizard were always the primary admin,
-- so we backfill them to 'admin'. New rows default to 'user' at DDL
-- level so a caller that forgets to specify a role can't accidentally
-- create an admin; explicit intent required.
--
-- SQLite does not support adding a CHECK constraint after CREATE TABLE,
-- so the enum is enforced via BEFORE INSERT + BEFORE UPDATE triggers
-- that RAISE(FAIL) on any value outside the allowed set. The triggers
-- also enforce lowercase (canonical form). Case-insensitive callers
-- should normalise before hitting the repo, but the DB is authoritative.

ALTER TABLE admin_user ADD COLUMN role TEXT NOT NULL DEFAULT 'user';

-- Backfill every existing row to 'admin'. In v0.1/v0.2 there was only
-- ever one admin_user row (the wizard-created primary admin), so this
-- is safe. If a future release ships multi-user with a different
-- default, replace this UPDATE with a targeted one keyed on id=1.
UPDATE admin_user SET role = 'admin';

CREATE TRIGGER IF NOT EXISTS admin_user_role_ins_check
  BEFORE INSERT ON admin_user
  FOR EACH ROW WHEN NEW.role NOT IN ('admin', 'user', 'guest')
  BEGIN
    SELECT RAISE(FAIL, 'invalid role — must be admin|user|guest');
  END;

CREATE TRIGGER IF NOT EXISTS admin_user_role_upd_check
  BEFORE UPDATE OF role ON admin_user
  FOR EACH ROW WHEN NEW.role NOT IN ('admin', 'user', 'guest')
  BEGIN
    SELECT RAISE(FAIL, 'invalid role — must be admin|user|guest');
  END;

-- Index the role column for the future GET /api/users?role=admin
-- filter. Cardinality is very low (3 distinct values across a homelab
-- user set); the index earns its keep once the users list grows past
-- ~10 rows and role-scoped views hit the table.
CREATE INDEX IF NOT EXISTS idx_admin_user_role ON admin_user(role);
