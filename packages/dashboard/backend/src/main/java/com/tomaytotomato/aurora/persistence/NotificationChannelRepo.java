package com.tomaytotomato.aurora.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Configured outbound notification channels — ntfy, Discord webhook, generic webhook. */
@Repository
public class NotificationChannelRepo {

  private final JdbcTemplate jdbc;

  public NotificationChannelRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Row(String id, String kind, String name, String target, List<String> events,
                     boolean enabled, String lastSentAt, String lastResult, String lastError) {}

  private static final String COLUMNS =
      "id, kind, name, target, events, enabled, last_sent_at, last_result, last_error";

  public List<Row> findAll() {
    return jdbc.query("SELECT " + COLUMNS + " FROM notification_channel ORDER BY rowid", NotificationChannelRepo::map);
  }

  public Optional<Row> findById(String id) {
    return jdbc.query("SELECT " + COLUMNS + " FROM notification_channel WHERE id = ?",
            NotificationChannelRepo::map, id)
        .stream().findFirst();
  }

  public Row insert(String id, String kind, String name, String target, List<String> events) {
    jdbc.update("INSERT INTO notification_channel (id, kind, name, target, events, enabled) "
            + "VALUES (?, ?, ?, ?, ?, 1)",
        id, kind, name, target, joinEvents(events));
    return findById(id).orElseThrow();
  }

  /**
   * Applies a partial patch — any of {@code kind}/{@code name}/{@code
   * target}/{@code events}/{@code enabled} that is non-null overwrites
   * the current value; everything else is left alone. Matches "change a
   * channel, or mute it" being one endpoint rather than two.
   */
  public Optional<Row> update(String id, String kind, String name, String target,
                               List<String> events, Boolean enabled) {
    Row existing = findById(id).orElse(null);
    if (existing == null) return Optional.empty();
    jdbc.update("UPDATE notification_channel SET kind = ?, name = ?, target = ?, events = ?, enabled = ? WHERE id = ?",
        kind != null ? kind : existing.kind(),
        name != null ? name : existing.name(),
        target != null ? target : existing.target(),
        events != null ? joinEvents(events) : joinEvents(existing.events()),
        (enabled != null ? enabled : existing.enabled()) ? 1 : 0,
        id);
    return findById(id);
  }

  public void recordTestResult(String id, String sentAt, String result, String error) {
    jdbc.update("UPDATE notification_channel SET last_sent_at = ?, last_result = ?, last_error = ? WHERE id = ?",
        sentAt, result, error, id);
  }

  public boolean delete(String id) {
    return jdbc.update("DELETE FROM notification_channel WHERE id = ?", id) > 0;
  }

  private static String joinEvents(List<String> events) {
    return events == null ? "" : String.join(",", events);
  }

  private static List<String> splitEvents(String raw) {
    List<String> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) return out;
    for (String s : raw.split(",")) {
      if (!s.isBlank()) out.add(s.trim());
    }
    return out;
  }

  private static Row map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new Row(
        rs.getString("id"),
        rs.getString("kind"),
        rs.getString("name"),
        rs.getString("target"),
        splitEvents(rs.getString("events")),
        rs.getInt("enabled") != 0,
        rs.getString("last_sent_at"),
        rs.getString("last_result"),
        rs.getString("last_error")
    );
  }
}
