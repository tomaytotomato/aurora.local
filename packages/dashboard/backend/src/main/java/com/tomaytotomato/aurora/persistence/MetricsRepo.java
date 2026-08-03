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

/**
 * B2 (v0.3): SQLite-backed metric samples. Table shape is inherited from
 * V1__init.sql: {@code metric_sample(ts TEXT, name TEXT, value REAL,
 * PRIMARY KEY(ts, name))}. Task spec suggested {@code (id, ts, key,
 * value)}; we keep the existing shape to avoid a data-loss migration
 * and treat {@code name} as {@code key} at the API surface (see
 * {@link com.tomaytotomato.aurora.controllers.MetricsController}).
 *
 * <p>Retention: 25 hours of samples. Ring-buffer prune runs on every
 * write via {@link #pruneOlderThan(Instant)} — cheap because the
 * {@code idx_metric_sample_name_ts} index makes the range delete a
 * back-index scan.
 */
@Repository
public class MetricsRepo {

  private static final Logger log = LoggerFactory.getLogger(MetricsRepo.class);

  /**
   * Retention window. 25h so a 24h chart never edge-cases on
   * "sample just fell off" the moment a user opens the dashboard.
   */
  public static final long RETENTION_HOURS = 25L;

  private final JdbcTemplate jdbc;

  public MetricsRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Insert a single sample. {@code INSERT OR REPLACE} because a
   * (ts, name) collision (same-millisecond dual-write from two
   * threads) should overwrite rather than 500 — the sampler is
   * @Scheduled single-threaded, but tests + future manual writers
   * benefit from idempotency.
   */
  public void insert(Instant ts, String name, double value) {
    try {
      jdbc.update(
          "INSERT OR REPLACE INTO metric_sample (ts, name, value) VALUES (?, ?, ?)",
          ts.toString(), name, value);
    } catch (Exception e) {
      log.warn("metric insert failed for {}: {}", name, e.getMessage());
    }
  }

  /**
   * Batch insert. Same statement, one shot — cheaper than N round trips
   * when the sampler emits ~10 rows per tick.
   */
  public void insertBatch(Instant ts, Map<String, Double> samples) {
    if (samples == null || samples.isEmpty()) return;
    List<Object[]> args = new ArrayList<>(samples.size());
    String tsStr = ts.toString();
    for (Map.Entry<String, Double> e : samples.entrySet()) {
      args.add(new Object[] { tsStr, e.getKey(), e.getValue() });
    }
    try {
      jdbc.batchUpdate(
          "INSERT OR REPLACE INTO metric_sample (ts, name, value) VALUES (?, ?, ?)",
          args);
    } catch (Exception e) {
      log.warn("metric batch insert failed ({} rows): {}", args.size(), e.getMessage());
    }
  }

  /** Delete all samples older than the given cutoff. */
  public int pruneOlderThan(Instant cutoff) {
    try {
      return jdbc.update(
          "DELETE FROM metric_sample WHERE ts < ?",
          cutoff.toString());
    } catch (Exception e) {
      log.warn("metric prune failed: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Bucketed last-24h series for a single metric name. Returns a list
   * of {@code {tsMs, avg, min, max, count}} maps, one per bucket, oldest
   * first. Buckets are aligned to wall-clock minutes for stable X-axis
   * rendering (a 5-minute bucket starts at :00 / :05 / :10 / ... rather
   * than drifting with the sampler's initial delay).
   *
   * <p>Empty result when the metric has no samples in the window; the
   * caller (frontend) then renders the "no data" empty state.
   *
   * @param name           metric key (e.g. {@code sys.cpu_pct}).
   * @param bucketMinutes  bucket width in minutes; must divide 60 evenly
   *                       for the SQL to align cleanly (5, 10, 15, 30, 60).
   * @param now            "now" for the query; injected so tests can pin
   *                       the window.
   */
  public List<Map<String, Object>> bucketed24h(String name, int bucketMinutes, Instant now) {
    Instant cutoff = now.minusSeconds(24 * 3600L);
    long bucketMs = bucketMinutes * 60_000L;
    // SQLite's julianday * 86400 gives seconds-since-julian-epoch; we
    // convert to unix ms via strftime('%s'). Bucket by integer division.
    // Result ts (bucket_start_ms) is the left edge of the bucket, so
    // the frontend can render "12:00-12:05" ranges without ambiguity.
    String sql =
        "SELECT " +
        "  (CAST(strftime('%s', ts) AS INTEGER) / (?/1000)) * ? AS bucket_start_ms, " +
        "  AVG(value) AS avg_v, " +
        "  MIN(value) AS min_v, " +
        "  MAX(value) AS max_v, " +
        "  COUNT(*)  AS n " +
        "FROM metric_sample " +
        "WHERE name = ? AND ts >= ? " +
        "GROUP BY bucket_start_ms " +
        "ORDER BY bucket_start_ms ASC";
    try {
      return jdbc.query(sql,
          (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", rs.getLong("bucket_start_ms"));
            row.put("avg", rs.getDouble("avg_v"));
            row.put("min", rs.getDouble("min_v"));
            row.put("max", rs.getDouble("max_v"));
            row.put("count", rs.getInt("n"));
            return row;
          },
          bucketMs, bucketMs, name, cutoff.toString());
    } catch (Exception e) {
      log.warn("bucketed24h failed for {}: {}", name, e.getMessage());
      return List.of();
    }
  }
}
