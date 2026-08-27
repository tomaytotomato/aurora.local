package com.tomaytotomato.aurora.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AuditEventRepo {

  private static final Logger log = LoggerFactory.getLogger(AuditEventRepo.class);

  private final JdbcTemplate jdbc;

  public AuditEventRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(Long userId, String action, String target, String diffJson) {
    jdbc.update(
        "INSERT INTO audit_event (user_id, action, target, diff_json) VALUES (?, ?, ?, ?)",
        userId, action, target, diffJson);
  }

  /**
   * Insert only when the newest row for {@code (action, target)} has a
   * different {@code diff_json} than the one supplied. Used by callers
   * whose events are "state now looks like X" heartbeats — the mdns
   * publisher re-audits on every restart even when the alias set has
   * not changed since the last audit, and the log fills up with rows
   * that carry no new information.
   *
   * <p>The uniqueness check is scoped to {@code (action, target)} — two
   * different aliases changing to the same target IP each get their
   * own row — and to the immediately previous row — a temporary
   * change and a change back to the earlier value still records twice.
   * That is the honest thing to do: the log is meant to show change,
   * and "changed and changed back" is a change.
   *
   * @return true when the row was inserted, false when suppressed as a duplicate
   */
  public boolean recordIfChanged(Long userId, String action, String target, String diffJson) {
    String prior = lastDiff(action, target);
    if (prior != null && prior.equals(diffJson == null ? "" : diffJson)) {
      return false;
    }
    if (prior == null && (diffJson == null || diffJson.isEmpty())) {
      // First-ever record for this (action, target) with a null-ish
      // diff: still record so the log carries the "we noticed this
      // target for the first time" milestone.
      record(userId, action, target, diffJson);
      return true;
    }
    record(userId, action, target, diffJson);
    return true;
  }

  /**
   * Newest {@code diff_json} for the given {@code (action, target)} pair,
   * or null when nothing has been recorded yet. Empty-string diffs are
   * returned verbatim (they mean "we recorded an event but had no diff
   * to attach") so callers can distinguish that from "no prior row".
   */
  String lastDiff(String action, String target) {
    try {
      List<String> rows = jdbc.query(
          "SELECT COALESCE(diff_json, '') FROM audit_event "
              + "WHERE action = ? AND target = ? "
              + "ORDER BY ts DESC, id DESC LIMIT 1",
          (rs, i) -> rs.getString(1),
          action, target);
      return rows.isEmpty() ? null : rows.get(0);
    } catch (Exception e) {
      log.warn("audit lastDiff failed: {}", e.getMessage());
      // Fail open: if we cannot check, record the event. Better a
      // duplicate row than a swallowed one.
      return null;
    }
  }

  /**
   * iter-30 (v0.3 followup): paged audit-event query. Supports optional
   * action-prefix, actor-id, and time-range filters so a settings-side
   * viewer can render "who suppressed what" or "launches since {ts}".
   *
   * <p>Returns newest first (indexed by {@code idx_audit_event_ts DESC}).
   * {@code limit} clamped to [1, {@link #MAX_LIMIT}] to keep the response
   * bounded even for a hostile client.
   *
   * @param actionPrefix optional; matches actions LIKE '{@code prefix%}'.
   * @param userId       optional; exact match on the acting admin id.
   * @param since        optional; inclusive lower bound (ISO-8601 UTC).
   * @param until        optional; exclusive upper bound.
   * @param limit        row cap after filtering.
   */
  public List<Map<String, Object>> query(String actionPrefix, Long userId,
                                          Instant since, Instant until, int limit) {
    int cap = Math.max(1, Math.min(MAX_LIMIT, limit));
    StringBuilder sql = new StringBuilder(
        "SELECT id, ts, user_id, action, target, diff_json " +
            "FROM audit_event WHERE 1=1 ");
    List<Object> args = new ArrayList<>(5);
    if (actionPrefix != null && !actionPrefix.isEmpty()) {
      sql.append("AND action LIKE ? ESCAPE '\\' ");
      args.add(escapeLike(actionPrefix) + "%");
    }
    if (userId != null) {
      sql.append("AND user_id = ? ");
      args.add(userId);
    }
    if (since != null) {
      sql.append("AND ts >= ? ");
      args.add(since.toString());
    }
    if (until != null) {
      sql.append("AND ts < ? ");
      args.add(until.toString());
    }
    sql.append("ORDER BY ts DESC, id DESC LIMIT ?");
    args.add(cap);

    try {
      return jdbc.query(sql.toString(),
          (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("ts", rs.getString("ts"));
            long uid = rs.getLong("user_id");
            row.put("user_id", rs.wasNull() ? null : uid);
            row.put("action", rs.getString("action"));
            row.put("target", rs.getString("target"));
            row.put("diff_json", rs.getString("diff_json"));
            return row;
          },
          args.toArray());
    } catch (Exception e) {
      log.warn("audit query failed: {}", e.getMessage());
      return List.of();
    }
  }

  /** Absolute upper bound on a single {@link #query} response. */
  public static final int MAX_LIMIT = 500;

  /** Escape SQL LIKE wildcards (mirrors MetricsRepo.escapeLike). */
  static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
