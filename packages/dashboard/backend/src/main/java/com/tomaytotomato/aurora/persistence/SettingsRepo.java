package com.tomaytotomato.aurora.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SettingsRepo {
  private final JdbcTemplate jdbc;

  public SettingsRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> get(String key) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT value FROM settings WHERE key = ?", String.class, key));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public void put(String key, String value) {
    jdbc.update(
        "INSERT INTO settings (key, value) VALUES (?, ?) "
            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, "
            + "updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')",
        key, value);
  }
}
