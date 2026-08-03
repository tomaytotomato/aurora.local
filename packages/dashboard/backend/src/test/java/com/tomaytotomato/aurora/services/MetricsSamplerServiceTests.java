package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.MetricsRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * B2 (v0.3): {@link MetricsSamplerService#collect(Instant)} shape +
 * scheduled {@link MetricsSamplerService#sample()} write / prune
 * contract. Real IO stays out of tests; both {@link SystemService} and
 * {@link ProcStatSampler} are mocked so the sampler exercises pure
 * aggregation logic.
 */
class MetricsSamplerServiceTests {

  private static MetricsRepo repo() { return Mockito.mock(MetricsRepo.class); }
  private static SystemService systems() { return Mockito.mock(SystemService.class); }
  private static ProcStatSampler cpuSampler() { return Mockito.mock(ProcStatSampler.class); }

  private static MetricsSamplerService svc(MetricsRepo r, SystemService sys, ProcStatSampler cpu) {
    return new MetricsSamplerService(r, sys, cpu);
  }

  @Test
  void collect_emits_cpu_mem_disk_uptime_keys() {
    var repo = repo();
    var sys = systems();
    var cpu = cpuSampler();

    Mockito.when(cpu.samplePercent()).thenReturn(12.5);
    Map<String, Long> mem = new LinkedHashMap<>();
    mem.put("MemTotal", 8L * 1024L * 1024L * 1024L);       // 8 GiB
    mem.put("MemAvailable", 4L * 1024L * 1024L * 1024L);   // 4 GiB
    Mockito.when(sys.readMemInfoPublic()).thenReturn(mem);
    Mockito.when(sys.disks()).thenReturn(List.of(
        Map.of("mount", "/", "total_bytes", 500_000_000_000L, "used_bytes", 100_000_000_000L),
        Map.of("mount", "/data", "total_bytes", 1_000_000_000_000L, "used_bytes", 250_000_000_000L)
    ));

    var s = svc(repo, sys, cpu);
    Map<String, Double> batch = s.collect(Instant.now());

    assertEquals(12.5, batch.get("sys.cpu_pct"), 0.001);
    assertEquals(8L * 1024L * 1024L * 1024L, batch.get("sys.mem_total_bytes"), 0.001);
    assertEquals(4L * 1024L * 1024L * 1024L, batch.get("sys.mem_used_bytes"), 0.001);
    assertEquals(500_000_000_000d, batch.get("sys.disk.root.total_bytes"), 0.001);
    assertEquals(100_000_000_000d, batch.get("sys.disk.root.used_bytes"), 0.001);
    assertEquals(1_000_000_000_000d, batch.get("sys.disk.data.total_bytes"), 0.001);
    assertEquals(250_000_000_000d, batch.get("sys.disk.data.used_bytes"), 0.001);
    assertNotNull(batch.get("app.uptime_ms"));
    assertTrue(batch.get("app.uptime_ms") >= 0);
  }

  @Test
  void collect_skips_cpu_on_first_tick() {
    // ProcStatSampler returns null on first sample; MetricsSamplerService
    // must NOT emit sys.cpu_pct in that case (would poison the chart
    // with a fake 0).
    var repo = repo();
    var sys = systems();
    var cpu = cpuSampler();
    Mockito.when(cpu.samplePercent()).thenReturn(null);
    Mockito.when(sys.readMemInfoPublic()).thenReturn(Map.of());
    Mockito.when(sys.disks()).thenReturn(List.of());

    var s = svc(repo, sys, cpu);
    Map<String, Double> batch = s.collect(Instant.now());

    assertEquals(false, batch.containsKey("sys.cpu_pct"));
    // Uptime is still emitted — cheap and always available.
    assertNotNull(batch.get("app.uptime_ms"));
  }

  @Test
  void collect_survives_probe_failures() {
    var repo = repo();
    var sys = systems();
    var cpu = cpuSampler();
    // Each probe throws; sampler must not propagate.
    Mockito.when(cpu.samplePercent()).thenThrow(new RuntimeException("stat"));
    Mockito.when(sys.readMemInfoPublic()).thenThrow(new RuntimeException("meminfo"));
    Mockito.when(sys.disks()).thenThrow(new RuntimeException("df"));

    var s = svc(repo, sys, cpu);
    Map<String, Double> batch = s.collect(Instant.now());
    // Uptime survives — cheap; other keys absent.
    assertEquals(1, batch.size());
    assertNotNull(batch.get("app.uptime_ms"));
  }

  @Test
  void sample_writes_batch_and_prunes() {
    var repo = repo();
    var sys = systems();
    var cpu = cpuSampler();
    Mockito.when(cpu.samplePercent()).thenReturn(50.0);
    Mockito.when(sys.readMemInfoPublic()).thenReturn(Map.of("MemTotal", 100L, "MemAvailable", 50L));
    Mockito.when(sys.disks()).thenReturn(List.of());

    var s = svc(repo, sys, cpu);
    s.sample();

    // insertBatch called once with a non-empty map.
    ArgumentCaptor<Map<String, Double>> batchCap = ArgumentCaptor.forClass(Map.class);
    Mockito.verify(repo).insertBatch(any(Instant.class), batchCap.capture());
    Map<String, Double> batch = batchCap.getValue();
    assertTrue(batch.containsKey("sys.cpu_pct"));
    assertTrue(batch.containsKey("sys.mem_total_bytes"));
    assertTrue(batch.containsKey("sys.mem_used_bytes"));

    // Prune called with a cutoff ~25h behind now.
    ArgumentCaptor<Instant> cutoffCap = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(repo).pruneOlderThan(cutoffCap.capture());
    Instant cutoff = cutoffCap.getValue();
    Instant expected = Instant.now().minus(java.time.Duration.ofHours(MetricsRepo.RETENTION_HOURS));
    long deltaSec = Math.abs(java.time.Duration.between(cutoff, expected).getSeconds());
    assertTrue(deltaSec < 5, "cutoff drift > 5s: " + deltaSec + "s");
  }

  @Test
  void sample_still_prunes_when_batch_is_empty() {
    var repo = repo();
    var sys = systems();
    var cpu = cpuSampler();
    // Every probe returns nothing → collect returns just app.uptime.
    Mockito.when(cpu.samplePercent()).thenReturn(null);
    Mockito.when(sys.readMemInfoPublic()).thenReturn(Map.of());
    Mockito.when(sys.disks()).thenReturn(List.of());
    var s = svc(repo, sys, cpu);
    s.sample();
    // insertBatch called (uptime is present) + prune called.
    Mockito.verify(repo).insertBatch(any(Instant.class), any());
    Mockito.verify(repo).pruneOlderThan(any(Instant.class));
  }

  @Test
  void safeKey_translates_mount_paths() {
    assertEquals("root", MetricsSamplerService.safeKey("/"));
    assertEquals("root", MetricsSamplerService.safeKey(""));
    assertEquals("root", MetricsSamplerService.safeKey(null));
    assertEquals("data", MetricsSamplerService.safeKey("/data"));
    assertEquals("data", MetricsSamplerService.safeKey("data"));
    assertEquals("mnt_backup", MetricsSamplerService.safeKey("/mnt/backup"));
    assertEquals("mnt_backup", MetricsSamplerService.safeKey("mnt/backup"));
    assertEquals("mnt_bulk-1", MetricsSamplerService.safeKey("/mnt/bulk-1"));
  }
}
