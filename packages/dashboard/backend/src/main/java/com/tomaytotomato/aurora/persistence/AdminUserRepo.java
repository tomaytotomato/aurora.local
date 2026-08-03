package com.tomaytotomato.aurora.persistence;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
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

  private static final RowMapper<AdminUser> MAPPER = (rs, i) -> new AdminUser(
      rs.getLong("id"),
      rs.getString("username"),
      rs.getString("password_hash"),
      rs.getString("tz"),
      rs.getString("created_at"),
      // Fall back to USER on the extremely unlikely event that a row
      // predates the V3 migration and the DB default is not applied;
      // the DB trigger will reject inserts / updates outside the enum
      // so this fallback exists solely for read paths.
      Role.fromWireName(rs.getString("role")).orElse(Role.USER)
  );

  public long count() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Long.class);
    return n == null ? 0L : n;
  }

  /** Count users at a given role. Backs the "must keep at least one admin" invariant. */
  public long countByRole(Role role) {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user WHERE role = ?",
        Long.class, role.wireName());
    return n == null ? 0L : n;
  }

  public Optional<AdminUser> findByUsername(String username) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT id, username, password_hash, tz, created_at, role FROM admin_user WHERE username = ?",
          MAPPER, username));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /** Return the primary admin (lowest id). v0.1 only ever has one. */
  public Optional<AdminUser> findFirst() {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT id, username, password_hash, tz, created_at, role FROM admin_user ORDER BY id LIMIT 1",
          MAPPER));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /** Full user list ordered by id (oldest first, matches audit expectations). */
  public List<AdminUser> findAll() {
    return jdbc.query(
        "SELECT id, username, password_hash, tz, created_at, role FROM admin_user ORDER BY id",
        MAPPER
    );
  }

  /**
   * Create a user with an explicit role. The role goes through the enum
   * so callers can't smuggle an unknown value past the DB trigger.
   */
  public long create(String username, String passwordHash, String tz, Role role) {
    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(conn -> {
      PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO admin_user (username, password_hash, tz, role) VALUES (?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, username);
      ps.setString(2, passwordHash);
      ps.setString(3, tz);
      ps.setString(4, role.wireName());
      return ps;
    }, kh);
    Number k = kh.getKey();
    return k == null ? -1L : k.longValue();
  }

  /**
   * Backward-compat overload — pre-Phase-D callers created THE admin
   * without knowing about roles. Preserves the old signature so the
   * onboarding path and the E2E reset flow keep working without a
   * churn commit; all new call sites should pass {@link Role} explicitly.
   */
  public long create(String username, String passwordHash, String tz) {
    return create(username, passwordHash, tz, Role.ADMIN);
  }

  /** Update the role for a given user id. Enforced by DB triggers. */
  public int updateRole(long id, Role role) {
    return jdbc.update("UPDATE admin_user SET role = ? WHERE id = ?",
        role.wireName(), id);
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
