package com.tomaytotomato.aurora.persistence;

import com.tomaytotomato.aurora.domain.AdminUser;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminUserRepo {
  private final JdbcTemplate jdbc;

  public AdminUserRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Every column the mapper reads, so the SELECTs stay in step. */
  private static final String COLUMNS =
      "id, username, password_hash, tz, created_at, role, last_login_at, passkey_enrolled";

  private static final RowMapper<AdminUser> MAPPER = (rs, i) -> new AdminUser(
      rs.getLong("id"),
      rs.getString("username"),
      rs.getString("password_hash"),
      rs.getString("tz"),
      rs.getString("created_at"),
      rs.getString("role"),
      rs.getString("last_login_at"),
      rs.getInt("passkey_enrolled") != 0
  );

  public long count() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Long.class);
    return n == null ? 0L : n;
  }

  public Optional<AdminUser> findByUsername(String username) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT " + COLUMNS + " FROM admin_user WHERE username = ?",
          MAPPER, username));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /** Return the primary admin (lowest id). v0.1 only ever has one. */
  public Optional<AdminUser> findFirst() {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT " + COLUMNS + " FROM admin_user ORDER BY id LIMIT 1",
          MAPPER));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * Create the box's first admin. Kept for the onboarding bootstrap, which
   * has no notion of roles — the person setting the box up is the admin by
   * definition.
   */
  public long create(String username, String passwordHash, String tz) {
    return create(username, passwordHash, tz, AdminUser.ROLE_ADMIN);
  }

  public long create(String username, String passwordHash, String tz, String role) {
    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(conn -> {
      PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO admin_user (username, password_hash, tz, role) VALUES (?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, username);
      ps.setString(2, passwordHash);
      ps.setString(3, tz);
      ps.setString(4, role);
      return ps;
    }, kh);
    Number k = kh.getKey();
    return k == null ? -1L : k.longValue();
  }

  /** Everyone, oldest first, so the box's original owner is at the top. */
  public List<AdminUser> listAll() {
    return jdbc.query("SELECT " + COLUMNS + " FROM admin_user ORDER BY id", MAPPER);
  }

  public Optional<AdminUser> findById(long id) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT " + COLUMNS + " FROM admin_user WHERE id = ?", MAPPER, id));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /** @return true when a row changed */
  public boolean updateRole(long id, String role) {
    return jdbc.update("UPDATE admin_user SET role = ? WHERE id = ?", role, id) > 0;
  }

  /** @return true when a row was removed */
  public boolean delete(long id) {
    return jdbc.update("DELETE FROM admin_user WHERE id = ?", id) > 0;
  }

  /**
   * How many admins exist. The guard against locking everybody out of the
   * dashboard is built on this, so it counts rows rather than trusting a
   * cached list.
   */
  public long countAdmins() {
    Long n = jdbc.queryForObject(
        "SELECT COUNT(*) FROM admin_user WHERE role = ?", Long.class, AdminUser.ROLE_ADMIN);
    return n == null ? 0L : n;
  }

  /**
   * Stamp a successful sign-in. Without this the Users page would show
   * "never" for everyone forever, which is a lie rather than an absence.
   */
  public void touchLastLogin(long id) {
    jdbc.update("UPDATE admin_user SET last_login_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')"
        + " WHERE id = ?", id);
  }

  /**
   * TD5 (2026-08-02): wipe every admin user. Intended for the
   * {@code POST /api/onboarding/reset} E2E-only endpoint so Playwright
   * suites can rewind between specs. Guarded at the controller by
   * {@code aurora.e2e-mode}; do not call in production paths.
   */
  public int deleteAll() {
    return jdbc.update("DELETE FROM admin_user");
  }
}
