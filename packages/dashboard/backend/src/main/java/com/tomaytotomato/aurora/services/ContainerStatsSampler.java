package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Statistics;
import com.tomaytotomato.aurora.persistence.MetricsRepo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * B2-followup (iter-20): per-container CPU% + memory sampler.
 *
 * <p>Deferred out of B2 (iter-10) because {@code docker stats} with a
 * streaming subscribe blocks ~1 s per container; running it inline on
 * the main scheduled thread would starve {@link MetricsSamplerService}.
 * This bean runs on a dedicated 4-thread pool at a slower cadence
 * (60 s) so the fan-out cost stays bounded even on the 10+ container
 * stacks Bruce runs (media + monitoring).
 *
 * <p>Emitted keys per sample:
 * <ul>
 *   <li>{@code container.<name>.cpu_pct} \u2014 CPU% using docker stats
 *       semantics (0..N*100 where N is {@code online_cpus} for the
 *       host; matches what {@code docker stats} prints so operators
 *       reading the metric see the number they expect). Null on
 *       first-tick / clock-stall gets skipped.</li>
 *   <li>{@code container.<name>.mem_used_bytes} \u2014 raw
 *       {@code memory_stats.usage} bytes. Frontend derives % from the
 *       host mem total emitted by MetricsSamplerService.</li>
 * </ul>
 *
 * <p>Skips non-running containers (docker stats on an exited container
 * either 404s or returns zeros). Prune already runs on
 * MetricsSamplerService so we deliberately do not re-run it here.
 */
@Service
public class ContainerStatsSampler {

  private static final Logger log = LoggerFactory.getLogger(ContainerStatsSampler.class);

  public static final long SAMPLE_INTERVAL_MS = 60_000L;
  public static final long SAMPLE_INITIAL_DELAY_MS = 15_000L;

  /** Per-container stat wait ceiling. */
  static final Duration PER_CONTAINER_TIMEOUT = Duration.ofSeconds(4);

  /** Overall batch ceiling so a stuck daemon can't block the schedule. */
  static final Duration BATCH_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Fixed-size fan-out pool. 4 threads keeps a 12-container media stack
   * finishing under BATCH_TIMEOUT even in the worst case where docker
   * blocks each read for the full PER_CONTAINER_TIMEOUT.
   */
  static final int POOL_SIZE = 4;

  private final DockerService dockerService;
  private final DockerClient docker;
  private final MetricsRepo repo;

  private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
    Thread t = new Thread(r, "aurora-container-stats");
    t.setDaemon(true);
    return t;
  });

  public ContainerStatsSampler(DockerService dockerService, DockerClient docker, MetricsRepo repo) {
    this.dockerService = dockerService;
    this.docker = docker;
    this.repo = repo;
  }

  @EventListener(ApplicationReadyEvent.class)
  void logReady() {
    log.info("container stats sampler online (interval {}s, pool {}, per-container timeout {}s)",
        SAMPLE_INTERVAL_MS / 1000, POOL_SIZE, PER_CONTAINER_TIMEOUT.toSeconds());
  }

  @PreDestroy
  void shutdown() {
    pool.shutdownNow();
  }

  @Scheduled(fixedRateString = "#{T(com.tomaytotomato.aurora.services.ContainerStatsSampler).SAMPLE_INTERVAL_MS}",
             initialDelayString = "#{T(com.tomaytotomato.aurora.services.ContainerStatsSampler).SAMPLE_INITIAL_DELAY_MS}")
  public void sample() {
    Instant now = Instant.now();
    List<Container> running = runningContainers();
    if (running.isEmpty()) return;

    Map<String, Double> batch = collect(running);
    if (!batch.isEmpty()) {
      repo.insertBatch(now, batch);
    }
  }

  /** Package-private for tests. */
  List<Container> runningContainers() {
    List<Container> out = new ArrayList<>();
    try {
      for (Container c : dockerService.listProjectContainers()) {
        if (c == null) continue;
        String state = c.getState();
        if (state != null && "running".equalsIgnoreCase(state)) out.add(c);
      }
    } catch (Exception e) {
      log.debug("container stats: listProjectContainers failed: {}", e.getMessage());
    }
    return out;
  }

  /**
   * Fan out to the pool, wait for {@link #BATCH_TIMEOUT}, aggregate. A
   * per-container timeout inside {@link #fetchStats} bounds each read,
   * so the overall wait is min(BATCH_TIMEOUT, POOL_SIZE * PER_CONTAINER_TIMEOUT + fan-in).
   */
  Map<String, Double> collect(List<Container> running) {
    Map<String, Double> out = new LinkedHashMap<>();
    List<CompletableFuture<SampleResult>> futures = new ArrayList<>(running.size());
    for (Container c : running) {
      futures.add(CompletableFuture.supplyAsync(() -> sampleOne(c), pool));
    }
    CompletableFuture<Void> all = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    try {
      all.get(BATCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      log.debug("container stats batch didn't finish in {}s: {}",
          BATCH_TIMEOUT.toSeconds(), e.getMessage());
      // Fall through — collect whatever completed.
    }
    for (CompletableFuture<SampleResult> f : futures) {
      if (!f.isDone() || f.isCompletedExceptionally() || f.isCancelled()) continue;
      SampleResult r = f.getNow(null);
      if (r == null || r.name == null) continue;
      if (r.cpuPct != null) out.put("container." + r.name + ".cpu_pct", r.cpuPct);
      if (r.memBytes != null) out.put("container." + r.name + ".mem_used_bytes", r.memBytes.doubleValue());
    }
    return out;
  }

  private SampleResult sampleOne(Container c) {
    String id = c.getId();
    String name = firstName(c);
    if (id == null || name == null) return new SampleResult(name, null, null);
    Statistics s = fetchStats(id);
    if (s == null) return new SampleResult(name, null, null);
    return new SampleResult(name, computeCpuPct(s), memBytes(s));
  }

  /**
   * Package-private for tests. Fetches a single Statistics frame via
   * {@code withNoStream(true)}. Returns null on timeout / error so the
   * caller drops the sample rather than propagating.
   */
  Statistics fetchStats(String containerId) {
    AtomicReference<Statistics> box = new AtomicReference<>();
    var cb = new ResultCallback.Adapter<Statistics>() {
      @Override public void onNext(Statistics stat) { box.compareAndSet(null, stat); }
    };
    try {
      docker.statsCmd(containerId).withNoStream(true).exec(cb);
      cb.awaitCompletion(PER_CONTAINER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.debug("statsCmd({}) failed: {}", containerId, e.getMessage());
    } finally {
      try { cb.close(); } catch (Exception ignore) { /* best-effort */ }
    }
    return box.get();
  }

  /**
   * Docker CPU percentage using the standard docker-CLI formula.
   * <pre>
   *   cpuDelta = cpu_stats.cpu_usage.total_usage - precpu_stats.cpu_usage.total_usage
   *   sysDelta = cpu_stats.system_cpu_usage - precpu_stats.system_cpu_usage
   *   cpu_pct  = (cpuDelta / sysDelta) * online_cpus * 100.0
   * </pre>
   * Returns null when any required field is missing (fresh container
   * without a prior sample), the system delta is zero (clock stall),
   * or the computed value is negative (host reboot / counter wrap).
   *
   * <p>Value range: {@code [0, online_cpus * 100]}. A 4-core host with
   * one container pegged on 2 cores reports 200. Kept in docker-CLI
   * semantics so operators see numbers matching {@code docker stats}.
   */
  static Double computeCpuPct(Statistics s) {
    if (s == null || s.getCpuStats() == null || s.getPreCpuStats() == null) return null;
    var cpu = s.getCpuStats();
    var pre = s.getPreCpuStats();
    var cpuUsage = cpu.getCpuUsage() == null ? null : cpu.getCpuUsage().getTotalUsage();
    var preCpuUsage = pre.getCpuUsage() == null ? null : pre.getCpuUsage().getTotalUsage();
    Long sysUsage = cpu.getSystemCpuUsage();
    Long preSysUsage = pre.getSystemCpuUsage();
    if (cpuUsage == null || preCpuUsage == null || sysUsage == null || preSysUsage == null) {
      return null;
    }
    long cpuDelta = cpuUsage - preCpuUsage;
    long sysDelta = sysUsage - preSysUsage;
    if (sysDelta <= 0 || cpuDelta < 0) return null;
    Long onlineCpus = cpu.getOnlineCpus();
    int cpus = (onlineCpus == null || onlineCpus <= 0) ? 1 : onlineCpus.intValue();
    double pct = ((double) cpuDelta / sysDelta) * cpus * 100.0;
    if (pct < 0) return 0.0;
    return pct;
  }

  /** Package-private for tests. */
  static Long memBytes(Statistics s) {
    if (s == null || s.getMemoryStats() == null) return null;
    return s.getMemoryStats().getUsage();
  }

  /** Package-private for tests. Strip leading '/' from docker's name. */
  static String firstName(Container c) {
    if (c == null) return null;
    String[] names = c.getNames();
    if (names == null || names.length == 0) return null;
    String n = names[0];
    return safeKey(n.startsWith("/") ? n.substring(1) : n);
  }

  /**
   * Translate a container name into a metric-key-safe suffix. Docker
   * allows {@code [a-zA-Z0-9_.-]}; everything else (should never
   * appear in practice) becomes {@code _} to keep the key SQL-safe.
   */
  static String safeKey(String name) {
    if (name == null || name.isEmpty()) return "unknown";
    StringBuilder b = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char ch = name.charAt(i);
      if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.') {
        b.append(ch);
      } else {
        b.append('_');
      }
    }
    return b.toString();
  }

  private record SampleResult(String name, Double cpuPct, Long memBytes) {}
}
