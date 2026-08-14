package com.tomaytotomato.aurora.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** What Aurora has actually sent — kept even after the sending channel is deleted. */
@Repository
public class NotificationDeliveryRepo {

  private final JdbcTemplate jdbc;

  public NotificationDeliveryRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Row(String id, String channelId, String event, String subject,
                     String sentAt, String result, String error) {}

  public void insert(String id, String channelId, String event, String subject,
                      String sentAt, String result, String error) {
    jdbc.update("INSERT INTO notification_delivery (id, channel_id, event, subject, sent_at, result, error) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        id, channelId, event, subject, sentAt, result, error);
  }

  /** Newest first. */
  public List<Row> findAll() {
    return jdbc.query(
        "SELECT id, channel_id, event, subject, sent_at, result, error "
            + "FROM notification_delivery ORDER BY sent_at DESC, rowid DESC",
        (rs, i) -> new Row(
            rs.getString("id"),
            rs.getString("channel_id"),
            rs.getString("event"),
            rs.getString("subject"),
            rs.getString("sent_at"),
            rs.getString("result"),
            rs.getString("error")));
  }
}
