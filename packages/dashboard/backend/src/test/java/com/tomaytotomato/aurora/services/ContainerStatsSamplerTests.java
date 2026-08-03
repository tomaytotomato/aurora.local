package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import com.tomaytotomato.aurora.persistence.MetricsRepo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2-followup: {@link ContainerStatsSampler} CPU-percent arithmetic +
 * key-safety + run-state filter. The async {@code statsCmd().exec(cb)}
 * path is not driven end-to-end (heavy async mock surface); the
 * {@code fetchStats} short-circuits when the callback never emits, so
 * we exercise the collector via a subclass that overrides that hook.
 */
class ContainerStatsSamplerTests {

  private static Statistics statsWithCpu(long curCpu, long preCpu,
                                          long curSys, long preSys,
                                          int onlineCpus) {
    return statsWithAll(curCpu, preCpu, curSys, preSys, onlineCpus, null);
  }

  private static Statistics statsWithAll(long curCpu, long preCpu,
                                          long curSys, long preSys,
                                          int onlineCpus,
                                          Long memUsage) {
    CpuUsageConfig curCpuUsage = Mockito.mock(CpuUsageConfig.class);
    Mockito.when(curCpuUsage.getTotalUsage()).thenReturn(curCpu);
    CpuUsageConfig preCpuUsage = Mockito.mock(CpuUsageConfig.class);
    Mockito.when(preCpuUsage.getTotalUsage()).thenReturn(preCpu);

    CpuStatsConfig cpu = Mockito.mock(CpuStatsConfig.class);
    Mockito.when(cpu.getCpuUsage()).thenReturn(curCpuUsage);
    Mockito.when(cpu.getSystemCpuUsage()).thenReturn(curSys);
    Mockito.when(cpu.getOnlineCpus()).thenReturn((long) onlineCpus);

    CpuStatsConfig pre = Mockito.mock(CpuStatsConfig.class);
    Mockito.when(pre.getCpuUsage()).thenReturn(preCpuUsage);
    Mockito.when(pre.getSystemCpuUsage()).thenReturn(preSys);
    Mockito.when(pre.getOnlineCpus()).thenReturn((long) onlineCpus);

    Statistics s = Mockito.mock(Statistics.class);
    Mockito.when(s.getCpuStats()).thenReturn(cpu);
    Mockito.when(s.getPreCpuStats()).thenReturn(pre);
    if (memUsage != null) {
      MemoryStatsConfig mem = Mockito.mock(MemoryStatsConfig.class);
      Mockito.when(mem.getUsage()).thenReturn(memUsage);
      Mockito.when(s.getMemoryStats()).thenReturn(mem);
    }
    return s;
  }

  // -- computeCpuPct ----------------------------------------------------

  @Test
  void computeCpuPct_matches_docker_cli_formula() {
    // dCpu = 1_000_000_000 (1s of CPU time), dSys = 10_000_000_000 (10s wall clock ×
    // 4 cpus at ns granularity is not right; docker actually reports sysUsage
    // in ns of aggregate). With online_cpus=4 and (dCpu/dSys)=0.1, pct = 40.0.
    Statistics s = statsWithCpu(2_000_000_000L, 1_000_000_000L,
                                20_000_000_000L, 10_000_000_000L, 4);
    Double pct = ContainerStatsSampler.computeCpuPct(s);
    assertNotNull(pct);
    assertEquals(40.0, pct, 0.001);
  }

  @Test
  void computeCpuPct_single_cpu_saturated() {
    Statistics s = statsWithCpu(1_000_000_000L, 0L, 1_000_000_000L, 0L, 1);
    Double pct = ContainerStatsSampler.computeCpuPct(s);
    // Single CPU pegged: dCpu/dSys = 1.0, cpus=1 → 100.
    assertEquals(100.0, pct, 0.001);
  }

  @Test
  void computeCpuPct_multi_core_saturated_reports_gt_100() {
    Statistics s = statsWithCpu(4_000_000_000L, 0L, 4_000_000_000L, 0L, 4);
    Double pct = ContainerStatsSampler.computeCpuPct(s);
    // All 4 cores pegged: 400% in docker-CLI terms.
    assertEquals(400.0, pct, 0.001);
  }

  @Test
  void computeCpuPct_null_on_missing_precpu() {
    Statistics s = Mockito.mock(Statistics.class);
    // getPreCpuStats() returns null by default → helper returns null.
    assertNull(ContainerStatsSampler.computeCpuPct(s));
  }

  @Test
  void computeCpuPct_null_on_zero_system_delta() {
    // Clock stall: sys delta = 0 → null.
    Statistics s = statsWithCpu(1L, 0L, 1_000_000L, 1_000_000L, 2);
    assertNull(ContainerStatsSampler.computeCpuPct(s));
  }

  @Test
  void computeCpuPct_null_on_negative_cpu_delta() {
    // Counter wrap / reboot: cpuDelta negative → null.
    Statistics s = statsWithCpu(500L, 1_000L, 20_000_000L, 10_000_000L, 2);
    assertNull(ContainerStatsSampler.computeCpuPct(s));
  }

  @Test
  void computeCpuPct_clamps_null_input_and_partial_fields() {
    assertNull(ContainerStatsSampler.computeCpuPct(null));
    CpuStatsConfig empty1 = Mockito.mock(CpuStatsConfig.class);
    CpuStatsConfig empty2 = Mockito.mock(CpuStatsConfig.class);
    Statistics s = Mockito.mock(Statistics.class);
    Mockito.when(s.getCpuStats()).thenReturn(empty1);
    Mockito.when(s.getPreCpuStats()).thenReturn(empty2);
    // Both empty configs → all Long fields null → helper returns null.
    assertNull(ContainerStatsSampler.computeCpuPct(s));
  }

  @Test
  void computeCpuPct_defaults_cpus_to_one_when_online_cpus_missing() {
    CpuUsageConfig curU = Mockito.mock(CpuUsageConfig.class);
    Mockito.when(curU.getTotalUsage()).thenReturn(1_000L);
    CpuUsageConfig preU = Mockito.mock(CpuUsageConfig.class);
    Mockito.when(preU.getTotalUsage()).thenReturn(0L);
    CpuStatsConfig cpu = Mockito.mock(CpuStatsConfig.class);
    Mockito.when(cpu.getCpuUsage()).thenReturn(curU);
    Mockito.when(cpu.getSystemCpuUsage()).thenReturn(10_000L);
    // No onlineCpus → helper defaults to 1.
    CpuStatsConfig pre = Mockito.mock(CpuStatsConfig.class);
    Mockito.when(pre.getCpuUsage()).thenReturn(preU);
    Mockito.when(pre.getSystemCpuUsage()).thenReturn(0L);
    Statistics s = Mockito.mock(Statistics.class);
    Mockito.when(s.getCpuStats()).thenReturn(cpu);
    Mockito.when(s.getPreCpuStats()).thenReturn(pre);
    // (1000/10000)*1*100 = 10.
    assertEquals(10.0, ContainerStatsSampler.computeCpuPct(s), 0.001);
  }

  // -- memBytes ---------------------------------------------------------

  @Test
  void memBytes_returns_usage_when_present() {
    Statistics s = statsWithAll(1L, 0L, 10L, 5L, 1, 1024L * 1024L * 512L);
    assertEquals(1024L * 1024L * 512L, ContainerStatsSampler.memBytes(s));
  }

  @Test
  void memBytes_null_on_no_memory_stats() {
    assertNull(ContainerStatsSampler.memBytes(null));
    assertNull(ContainerStatsSampler.memBytes(Mockito.mock(Statistics.class)));
  }

  // -- safeKey / firstName ---------------------------------------------

  @Test
  void safeKey_preserves_valid_chars_and_replaces_the_rest() {
    assertEquals("aurora-media-sonarr", ContainerStatsSampler.safeKey("aurora-media-sonarr"));
    assertEquals("dot.name.ok", ContainerStatsSampler.safeKey("dot.name.ok"));
    assertEquals("colon_and_slash_", ContainerStatsSampler.safeKey("colon:and/slash "));
    assertEquals("unknown", ContainerStatsSampler.safeKey(null));
    assertEquals("unknown", ContainerStatsSampler.safeKey(""));
  }

  @Test
  void firstName_strips_leading_slash_and_normalises() {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/aurora-media-sonarr" });
    assertEquals("aurora-media-sonarr", ContainerStatsSampler.firstName(c));
  }

  @Test
  void firstName_null_on_null_or_empty_names() {
    assertNull(ContainerStatsSampler.firstName(null));
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(null);
    assertNull(ContainerStatsSampler.firstName(c));
    Mockito.when(c.getNames()).thenReturn(new String[0]);
    assertNull(ContainerStatsSampler.firstName(c));
  }

  // -- runningContainers filter ----------------------------------------

  @Test
  void runningContainers_filters_to_running_state_only() {
    DockerService ds = Mockito.mock(DockerService.class);
    DockerClient dc = Mockito.mock(DockerClient.class);
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);

    Container running = Mockito.mock(Container.class);
    Mockito.when(running.getState()).thenReturn("running");
    Mockito.when(running.getNames()).thenReturn(new String[] { "/aurora-media-sonarr" });
    Container exited = Mockito.mock(Container.class);
    Mockito.when(exited.getState()).thenReturn("exited");
    Mockito.when(exited.getNames()).thenReturn(new String[] { "/aurora-old" });
    Container created = Mockito.mock(Container.class);
    Mockito.when(created.getState()).thenReturn("created");
    Mockito.when(created.getNames()).thenReturn(new String[] { "/aurora-new" });
    Mockito.when(ds.listProjectContainers())
        .thenReturn(List.of(running, exited, created));

    var sampler = new ContainerStatsSampler(ds, dc, repo);
    var kept = sampler.runningContainers();
    assertEquals(1, kept.size());
    assertEquals(running, kept.get(0));
  }

  @Test
  void runningContainers_swallows_docker_exceptions() {
    DockerService ds = Mockito.mock(DockerService.class);
    Mockito.when(ds.listProjectContainers()).thenThrow(new RuntimeException("socket down"));
    var sampler = new ContainerStatsSampler(ds,
        Mockito.mock(DockerClient.class), Mockito.mock(MetricsRepo.class));
    assertTrue(sampler.runningContainers().isEmpty());
  }

  // -- collect + sample() end-to-end via a fetchStats override ---------

  private static ContainerStatsSampler samplerWithCannedStats(
      DockerService ds, MetricsRepo repo, Map<String, Statistics> perId) {
    return new ContainerStatsSampler(ds, Mockito.mock(DockerClient.class), repo) {
      @Override
      Statistics fetchStats(String containerId) {
        return perId.get(containerId);
      }
    };
  }

  private static Container containerWithId(String id, String name) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getId()).thenReturn(id);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    Mockito.when(c.getState()).thenReturn("running");
    return c;
  }

  @Test
  void collect_emits_per_container_cpu_and_mem_keys() {
    DockerService ds = Mockito.mock(DockerService.class);
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    Container sonarr = containerWithId("id-sonarr", "aurora-media-sonarr");
    Container radarr = containerWithId("id-radarr", "aurora-media-radarr");
    Statistics sSonarr = statsWithAll(1_000_000_000L, 0L, 1_000_000_000L, 0L, 1, 128L * 1024L * 1024L);
    Statistics sRadarr = statsWithAll(2_000_000_000L, 1_000_000_000L,
        20_000_000_000L, 10_000_000_000L, 4, 256L * 1024L * 1024L);

    var sampler = samplerWithCannedStats(ds, repo, Map.of(
        "id-sonarr", sSonarr, "id-radarr", sRadarr));

    Map<String, Double> batch = sampler.collect(List.of(sonarr, radarr));
    assertEquals(100.0, batch.get("container.aurora-media-sonarr.cpu_pct"), 0.001);
    assertEquals(128d * 1024 * 1024, batch.get("container.aurora-media-sonarr.mem_used_bytes"), 0.001);
    assertEquals(40.0, batch.get("container.aurora-media-radarr.cpu_pct"), 0.001);
    assertEquals(256d * 1024 * 1024, batch.get("container.aurora-media-radarr.mem_used_bytes"), 0.001);
  }

  @Test
  void collect_skips_container_when_fetchStats_returns_null() {
    DockerService ds = Mockito.mock(DockerService.class);
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    Container ok = containerWithId("id-ok", "aurora-ok");
    Container bad = containerWithId("id-bad", "aurora-bad");
    Statistics good = statsWithAll(1L, 0L, 10L, 0L, 1, 10L);

    // Only 'ok' has a canned stats result.
    var sampler = samplerWithCannedStats(ds, repo, Map.of("id-ok", good));
    Map<String, Double> batch = sampler.collect(List.of(ok, bad));
    assertTrue(batch.containsKey("container.aurora-ok.cpu_pct"));
    assertTrue(batch.containsKey("container.aurora-ok.mem_used_bytes"));
    assertTrue(!batch.containsKey("container.aurora-bad.cpu_pct"));
    assertTrue(!batch.containsKey("container.aurora-bad.mem_used_bytes"));
  }

  @Test
  void collect_skips_cpu_key_when_computeCpuPct_returns_null() {
    // First-tick style: no preCpuStats. mem_used_bytes still emitted.
    DockerService ds = Mockito.mock(DockerService.class);
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    Container c = containerWithId("id-a", "aurora-a");
    Statistics s = Mockito.mock(Statistics.class);
    // Only mem stats present.
    MemoryStatsConfig mem = Mockito.mock(MemoryStatsConfig.class);
    Mockito.when(mem.getUsage()).thenReturn(42L);
    Mockito.when(s.getMemoryStats()).thenReturn(mem);
    var sampler = samplerWithCannedStats(ds, repo, Map.of("id-a", s));
    Map<String, Double> batch = sampler.collect(List.of(c));
    assertTrue(!batch.containsKey("container.aurora-a.cpu_pct"));
    assertEquals(42.0, batch.get("container.aurora-a.mem_used_bytes"), 0.001);
  }

  @Test
  void sample_writes_batch_via_repo() {
    DockerService ds = Mockito.mock(DockerService.class);
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    Container c = containerWithId("id-a", "aurora-a");
    Statistics s = statsWithAll(1L, 0L, 10L, 0L, 1, 100L);
    Mockito.when(ds.listProjectContainers()).thenReturn(List.of(c));

    var sampler = samplerWithCannedStats(ds, repo, Map.of("id-a", s));
    sampler.sample();

    Mockito.verify(repo).insertBatch(Mockito.any(), Mockito.argThat(m ->
        m.containsKey("container.aurora-a.cpu_pct")
            && m.containsKey("container.aurora-a.mem_used_bytes")));
    // Prune deliberately NOT invoked from this sampler — MetricsSamplerService
    // owns retention on its own schedule.
    Mockito.verify(repo, Mockito.never()).pruneOlderThan(Mockito.any());
  }

  @Test
  void sample_no_op_when_no_running_containers() {
    DockerService ds = Mockito.mock(DockerService.class);
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    Mockito.when(ds.listProjectContainers()).thenReturn(List.of());
    var sampler = new ContainerStatsSampler(ds, Mockito.mock(DockerClient.class), repo);
    sampler.sample();
    Mockito.verifyNoInteractions(repo);
  }
}
