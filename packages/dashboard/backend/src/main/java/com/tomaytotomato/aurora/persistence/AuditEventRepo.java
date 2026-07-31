package com.tomaytotomato.aurora.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEventRepo {
  private final JdbcTemplate jdbc;

  public AuditEventRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(Long userId, String action, String target, String diffJson) {
    jdbc.update(
        "INSERT INTO audit_event (user_id, action, target, diff_json) VALUES (?, ?, ?, ?)",
        userId, action, target, diffJson);
  }
}
