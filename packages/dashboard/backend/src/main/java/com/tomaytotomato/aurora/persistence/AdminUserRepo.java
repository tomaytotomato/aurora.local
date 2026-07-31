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
      rs.getString("created_at")
  );

  public long count() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Long.class);
    return n == null ? 0L : n;
  }

  public Optional<AdminUser> findByUsername(String username) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT id, username, password_hash, tz, created_at FROM admin_user WHERE username = ?",
          MAPPER, username));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public long create(String username, String passwordHash, String tz) {
    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(conn -> {
      PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO admin_user (username, password_hash, tz) VALUES (?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, username);
      ps.setString(2, passwordHash);
      ps.setString(3, tz);
      return ps;
    }, kh);
    Number k = kh.getKey();
    return k == null ? -1L : k.longValue();
  }
}
