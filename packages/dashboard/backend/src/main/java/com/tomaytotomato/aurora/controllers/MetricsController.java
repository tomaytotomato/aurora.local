package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.persistence.MetricsRepo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B2 (v0.3 groundwork): read surface for the {@code metric_sample}
 * ring buffer. Backend-only for now; the DashboardHome "Metrics" card
 * stays on its "Metrics land next release" empty state until a
 * follow-up wires uPlot against this endpoint.
 *
 * <p>Auth: falls under {@code SecurityConfig.anyRequest().authenticated()}
 * — admin session required. Metric keys leak container names and disk
 * paths; not a public surface.
 *
 * <p>Endpoint:
 * <pre>
 *   GET /api/metrics/last24h?key=sys.cpu_pct&amp;bucketMinutes=5
 * </pre>
 * Returns a list of buckets, oldest first:
 * <pre>
 *   [{"ts": 1735689000000, "avg": 12.4, "min": 8.1, "max": 18.9, "count": 10}, ...]
 * </pre>
 * Empty list when the key has no samples in the window (frontend
 * renders the "no data" empty state).
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

  /**
   * Metric-key allowlist regex. Guards against SQL parameter binding
   * mishaps + keeps a hostile client from probing internals with
   * '; drop table --' shaped inputs (JdbcTemplate parameterises the
   * value; the regex is defence-in-depth + a clean 400).
   */
  private static final Pattern KEY_SHAPE = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");

  /** Prefix shares the key shape — same charset, same length ceiling. */
  private static final Pattern PREFIX_SHAPE = KEY_SHAPE;

  /**
   * Only allow bucket widths that divide 60 evenly so the SQL grouping
   * lands on clean wall-clock boundaries. 1h buckets are the coarsest
   * useful window for a 24h chart.
   */
  private static final Set<Integer> ALLOWED_BUCKETS = Set.of(1, 2, 5, 10, 15, 30, 60);

  private final MetricsRepo repo;

  public MetricsController(MetricsRepo repo) {
    this.repo = repo;
  }

  @GetMapping("/last24h")
  public List<Map<String, Object>> last24h(
      @RequestParam("key") String key,
      @RequestParam(name = "bucketMinutes", defaultValue = "5") int bucketMinutes
  ) {
    if (key == null || !KEY_SHAPE.matcher(key).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "key must match ^[a-z][a-z0-9._-]{0,63}$");
    }
    if (!ALLOWED_BUCKETS.contains(bucketMinutes)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "bucketMinutes must be one of " + ALLOWED_BUCKETS);
    }
    return repo.bucketed24h(key, bucketMinutes, Instant.now());
  }

  /**
   * B2-followup (iter-21): metric key discovery. Enables an FE dropdown
   * of populated keys without hardcoding container names or per-mount
   * disk keys. Prefix filter is optional; when supplied it must match the
   * same shape as a full key. Prefixes with a trailing '.' are the
   * intended usage (e.g. {@code container.} or {@code sys.disk.}); the
   * regex accepts partial keys as well because a lookup for
   * {@code container.aurora-} filtered to a package prefix is useful.
   *
   * <p>Auth: same as {@code /last24h} — falls under
   * {@code SecurityConfig.anyRequest().authenticated()}.
   */
  @GetMapping("/keys")
  public List<String> keys(
      @RequestParam(name = "prefix", required = false) String prefix
  ) {
    if (prefix != null && !prefix.isEmpty() && !PREFIX_SHAPE.matcher(prefix).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "prefix must match ^[a-z][a-z0-9._-]{0,63}$");
    }
    return repo.distinctKeys(prefix);
  }
}
