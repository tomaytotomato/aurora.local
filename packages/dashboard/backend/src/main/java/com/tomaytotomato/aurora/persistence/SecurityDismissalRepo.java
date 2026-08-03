package com.tomaytotomato.aurora.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B4-followup (iter-23): SQLite-backed dismiss / snooze store for
 * {@link com.tomaytotomato.aurora.domain.SecurityFinding} ids.
 *
 * <p>Shape (V2__security_dismissal.sql):
 * <pre>
 *   security_dismissal(
 *     finding_id   TEXT PK,
 *     dismissed_at TEXT NOT NULL DEFAULT now,
 *     expires_at   TEXT (nullable = permanent),
 *     reason       TEXT (nullable)
 *   )
 * </pre>
 *
 * <p>Expired snoozes are transparently ignored on read via a
 * {@code WHERE expires_at IS NULL OR expires_at > ?} clause; explicit
 * pruning is offered as an idempotent maintenance call rather than an
 * auto-cleanup so an operator can inspect what's expired if a
 * dismissed-findings settings view lands later.
 */
@Repository
public class SecurityDismissalRepo {

  private static final Logger log = LoggerFactory.getLogger(SecurityDismissalRepo.class);

  private final JdbcTemplate jdbc;

  public SecurityDismissalRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Insert or overwrite a dismissal record. {@code expiresAt} null
   * means permanent; the {@code INSERT OR REPLACE} shape means a repeat
   * dismissal (say, extending a snooze) just updates the row.
   */
  public void dismiss(String findingId, Instant expiresAt, String reason) {
    if (findingId == null || findingId.isBlank()) return;
    try {
      jdbc.update(
          "INSERT OR REPLACE INTO security_dismissal " +
              "(finding_id, dismissed_at, expires_at, reason) VALUES (?, ?, ?, ?)",
          findingId,
          Instant.now().toString(),
          expiresAt == null ? null : expiresAt.toString(),
          reason);
    } catch (Exception e) {
      log.warn("security dismissal insert failed for {}: {}", findingId, e.getMessage());
    }
  }

  /** Restore a dismissed finding (delete the row). */
  public boolean restore(String findingId) {
    if (findingId == null || findingId.isBlank()) return false;
    try {
      return jdbc.update("DELETE FROM security_dismissal WHERE finding_id = ?", findingId) > 0;
    } catch (Exception e) {
      log.warn("security dismissal delete failed for {}: {}", findingId, e.getMessage());
      return false;
    }
  }

  /**
   * Set of finding ids that are currently dismissed (expires_at null OR
   * expires_at > now). {@link com.tomaytotomato.aurora.security.SecurityFindingsService}
   * filters against this set to hide dismissed findings from the main
   * feed.
   */
  public Set<String> activeDismissals(Instant now) {
    if (now == null) now = Instant.now();
    try {
      List<String> ids = jdbc.queryForList(
          "SELECT finding_id FROM security_dismissal " +
              "WHERE expires_at IS NULL OR expires_at > ?",
          String.class,
          now.toString());
      return Set.copyOf(ids);
    } catch (Exception e) {
      log.warn("activeDismissals query failed: {}", e.getMessage());
      return Set.of();
    }
  }

  /**
   * Return every dismissal row (for a future settings-side view). Kept
   * in Map form so the caller renders keys stably without a domain
   * record.
   */
  public List<Map<String, Object>> listAll() {
    try {
      return jdbc.query(
          "SELECT finding_id, dismissed_at, expires_at, reason FROM security_dismissal " +
              "ORDER BY dismissed_at DESC",
          (rs, i) -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("finding_id", rs.getString("finding_id"));
            row.put("dismissed_at", rs.getString("dismissed_at"));
            row.put("expires_at", rs.getString("expires_at"));
            row.put("reason", rs.getString("reason"));
            return row;
          });
    } catch (Exception e) {
      log.warn("listAll dismissals failed: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Delete rows whose {@code expires_at} is in the past. Idempotent;
   * safe to run on a schedule if one lands later. Returns count deleted.
   */
  public int pruneExpired(Instant now) {
    if (now == null) now = Instant.now();
    try {
      return jdbc.update(
          "DELETE FROM security_dismissal " +
              "WHERE expires_at IS NOT NULL AND expires_at <= ?",
          now.toString());
    } catch (Exception e) {
      log.warn("pruneExpired failed: {}", e.getMessage());
      return 0;
    }
  }
}
