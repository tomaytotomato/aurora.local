package com.tomaytotomato.aurora.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * v0.1 sampler: inserts a single "app.uptime_ms" row every 15s so the metrics
 * table isn't empty when the frontend queries. Real samplers (cpu/mem/disk +
 * per-container stats) land in v0.3 (brief §M3).
 */
@Component
@EnableScheduling
public class MetricSampler {

  private static final Logger log = LoggerFactory.getLogger(MetricSampler.class);
  private final JdbcTemplate jdbc;

  public MetricSampler(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void logReady() {
    log.info("aurora dashboard v0.1 ready — metric sampler online");
  }

  @Scheduled(fixedRate = 15_000L, initialDelay = 5_000L)
  public void sample() {
    long up = ManagementFactory.getRuntimeMXBean().getUptime();
    String ts = Instant.now().toString();
    try {
      jdbc.update(
          "INSERT OR REPLACE INTO metric_sample (ts, name, value) VALUES (?, ?, ?)",
          ts, "app.uptime_ms", (double) up);
    } catch (Exception e) {
      log.warn("metric insert failed: {}", e.getMessage());
    }
  }
}
