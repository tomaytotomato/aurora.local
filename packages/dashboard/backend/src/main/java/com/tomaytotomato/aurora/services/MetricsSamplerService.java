package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.MetricsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B2 (v0.3 groundwork): schedule-driven collector for the metric_sample
 * table. Runs every {@link #SAMPLE_INTERVAL_MS} ms after a short warm-up
 * delay (host mem/disk lookups are cheap; skipping the very first tick
 * lets the JVM finish class-loading before we start blocking on I/O).
 *
 * <p>Emits these keys per tick (skips a key if its source read fails —
 * one bad probe doesn't torpedo the batch):
 * <ul>
 *   <li>{@code sys.cpu_pct} — 0..100. Skipped on the first tick because
 *       {@link ProcStatSampler} needs a prior sample to diff against.</li>
 *   <li>{@code sys.mem_used_bytes}, {@code sys.mem_total_bytes} — from
 *       {@code /proc/meminfo} via {@link SystemService#readMemInfoPublic()}.
 *       Both raw bytes; the frontend derives % from used/total.</li>
 *   <li>{@code sys.disk.<mount>.used_bytes} + {@code sys.disk.<mount>.total_bytes}
 *       — one pair per real filesystem returned by
 *       {@link SystemService#disks()}. Mount name has {@code /} translated
 *       to {@code _} so the key stays SQL-safe and URL-safe (see
 *       {@link #safeKey(String)}).</li>
 *   <li>{@code app.uptime_ms} — JVM uptime, kept from the iter-1 sampler
 *       so the health-check dashboard has continuity.</li>
 * </ul>
 *
 * <p>Deferred: per-container CPU% + memory samples via
 * {@code docker stats}. The docker-java stats call is a streaming
 * subscribe with a ~1s dwell time — bundling it here would starve the
 * scheduled thread. Ships as B2-followup in its own bean.
 *
 * <p>Retention: after every insert we prune everything older than
 * {@link MetricsRepo#RETENTION_HOURS} hours. Cheap; the
 * {@code idx_metric_sample_name_ts} index makes the range delete a
 * back-index scan.
 */
@Service
@EnableScheduling
public class MetricsSamplerService {

  private static final Logger log = LoggerFactory.getLogger(MetricsSamplerService.class);

  /** Sample cadence. 30s per DASHBOARD_BRIEF §4.2 (v0.3 update). */
  public static final long SAMPLE_INTERVAL_MS = 30_000L;

  /** Delay before the first sample fires so app boot is not gated on IO. */
  public static final long SAMPLE_INITIAL_DELAY_MS = 5_000L;

  private final MetricsRepo repo;
  private final SystemService systems;
  private final ProcStatSampler cpuSampler;

  public MetricsSamplerService(MetricsRepo repo, SystemService systems, ProcStatSampler cpuSampler) {
    this.repo = repo;
    this.systems = systems;
    this.cpuSampler = cpuSampler;
  }

  @EventListener(ApplicationReadyEvent.class)
  void logReady() {
    log.info("metrics sampler online (interval {}s, retention {}h)",
        SAMPLE_INTERVAL_MS / 1000, MetricsRepo.RETENTION_HOURS);
  }

  @Scheduled(fixedRateString = "#{T(com.tomaytotomato.aurora.services.MetricsSamplerService).SAMPLE_INTERVAL_MS}",
             initialDelayString = "#{T(com.tomaytotomato.aurora.services.MetricsSamplerService).SAMPLE_INITIAL_DELAY_MS}")
  public void sample() {
    Instant now = Instant.now();
    Map<String, Double> batch = collect(now);
    if (!batch.isEmpty()) {
      repo.insertBatch(now, batch);
    }
    Instant cutoff = now.minus(Duration.ofHours(MetricsRepo.RETENTION_HOURS));
    repo.pruneOlderThan(cutoff);
  }

  /**
   * Assemble the per-tick batch. Package-private so tests can exercise
   * the collection layer independently of the schedule.
   */
  Map<String, Double> collect(Instant now) {
    Map<String, Double> out = new LinkedHashMap<>();

    // CPU% — null on the very first tick (no delta yet).
    try {
      Double pct = cpuSampler.samplePercent();
      if (pct != null) out.put("sys.cpu_pct", pct);
    } catch (Exception e) {
      log.debug("cpu sample failed: {}", e.getMessage());
    }

    // Memory. readMemInfoPublic returns bytes.
    try {
      Map<String, Long> mem = systems.readMemInfoPublic();
      Long total = mem == null ? null : mem.get("MemTotal");
      Long avail = mem == null ? null : mem.get("MemAvailable");
      if (total != null) out.put("sys.mem_total_bytes", total.doubleValue());
      if (total != null && avail != null) {
        out.put("sys.mem_used_bytes", (double) (total - avail));
      }
    } catch (Exception e) {
      log.debug("mem sample failed: {}", e.getMessage());
    }

    // Per-mount disk. Skip mounts we couldn't statvfs.
    try {
      List<Map<String, Object>> disks = systems.disks();
      if (disks != null) {
        for (Map<String, Object> d : disks) {
          Object mount = d.get("mount");
          Object total = d.get("total_bytes");
          Object used = d.get("used_bytes");
          if (mount == null || total == null) continue;
          String key = "sys.disk." + safeKey(mount.toString());
          out.put(key + ".total_bytes", ((Number) total).doubleValue());
          if (used != null) out.put(key + ".used_bytes", ((Number) used).doubleValue());
        }
      }
    } catch (Exception e) {
      log.debug("disk sample failed: {}", e.getMessage());
    }

    // App uptime — cheap continuity metric.
    try {
      out.put("app.uptime_ms", (double) ManagementFactory.getRuntimeMXBean().getUptime());
    } catch (Exception ignore) {}

    return out;
  }

  /**
   * Translate a mount path into a metric-key-safe suffix. {@code /} is
   * the display prefix (mount roots are absolute), so leading slashes
   * are stripped; interior slashes become {@code _}. Empty mount (the
   * root) renders as {@code root} to avoid a dangling dot in the key.
   */
  static String safeKey(String mount) {
    if (mount == null || mount.isEmpty() || mount.equals("/")) return "root";
    String stripped = mount;
    while (stripped.startsWith("/")) stripped = stripped.substring(1);
    return stripped.isEmpty() ? "root" : stripped.replace('/', '_');
  }
}
