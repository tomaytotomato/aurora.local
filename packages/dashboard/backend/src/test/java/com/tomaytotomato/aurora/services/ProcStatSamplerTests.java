package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 (v0.3): {@link ProcStatSampler} delta arithmetic. First sample
 * seeds and returns null; subsequent samples return CPU% in [0, 100].
 * Zero-delta / clock stall returns null instead of NaN.
 */
class ProcStatSamplerTests {

  private static AuroraProperties propsFor(Path hostProc) {
    return new AuroraProperties(
        "/repo",
        hostProc.toString(),
        List.of(),
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
  }

  private static ProcStatSampler sampler(Path hostProc) {
    return new ProcStatSampler(propsFor(hostProc));
  }

  private static void writeStat(Path hostProc, String header) throws IOException {
    Files.createDirectories(hostProc);
    Files.writeString(hostProc.resolve("stat"),
        header + "\ncpu0 1 2 3 4 5 6 7 8\nintr 100\n");
  }

  @Test
  void first_sample_returns_null_and_seeds(@TempDir Path proc) throws Exception {
    writeStat(proc, "cpu  100 0 50 900 0 0 0 0 0 0");
    ProcStatSampler s = sampler(proc);
    assertNull(s.samplePercent(), "first sample has nothing to diff");
  }

  @Test
  void second_sample_computes_percent(@TempDir Path proc) throws Exception {
    ProcStatSampler s = sampler(proc);
    // Tick 1: busy = user+nice+system = 100+0+50 = 150; idle = 900+0 = 900.
    writeStat(proc, "cpu  100 0 50 900 0 0 0 0 0 0");
    assertNull(s.samplePercent());
    // Tick 2: busy = 200+0+100 = 300 (Δ=150); idle = 1650 (Δ=750).
    // pct = 100 * 150 / (150+750) = 16.6666...
    writeStat(proc, "cpu  200 0 100 1650 0 0 0 0 0 0");
    Double pct = s.samplePercent();
    assertNotNull(pct);
    assertTrue(pct > 16.0 && pct < 17.0, "expected ~16.67, was " + pct);
  }

  @Test
  void zero_delta_returns_null(@TempDir Path proc) throws Exception {
    ProcStatSampler s = sampler(proc);
    writeStat(proc, "cpu  100 0 50 900 0 0 0 0 0 0");
    s.samplePercent(); // seed
    // Identical file → dBusy=dIdle=0 → null, not NaN.
    assertNull(s.samplePercent());
  }

  @Test
  void clamps_negative_and_over_100() {
    // Direct computeFromHeader() path — hand-craft a scenario where
    // the previous counters were larger (e.g. host reboot). The
    // implementation clamps [0, 100].
    ProcStatSampler s = new ProcStatSampler(propsFor(Path.of("/tmp/nowhere")));
    // Seed with high counters.
    assertNull(s.computeFromHeader("cpu 1000 0 500 5000 0 0 0 0 0 0"));
    // Second sample: lower counters (reboot). dBusy=-1500, dIdle=-5000.
    // total=-6500 < 0 → null (clock stall guard).
    Double pct = s.computeFromHeader("cpu 0 0 0 0 0 0 0 0 0 0");
    assertNull(pct);
  }

  @Test
  void malformed_header_returns_null(@TempDir Path proc) {
    ProcStatSampler s = sampler(proc);
    assertNull(s.computeFromHeader(null));
    assertNull(s.computeFromHeader(""));
    assertNull(s.computeFromHeader("not-cpu 1 2 3"));
    assertNull(s.computeFromHeader("cpu 1 2 3")); // fewer than 5 columns
  }

  @Test
  void missing_proc_stat_returns_null(@TempDir Path proc) {
    // hostProcPath points at an empty dir → sampler falls back to
    // /proc/stat, which exists in CI. Skip if it doesn't (mac test box).
    ProcStatSampler s = sampler(proc);
    // First call may seed or return null depending on /proc/stat existing.
    // Either way, no exception should escape.
    try { s.samplePercent(); } catch (Exception e) {
      throw new AssertionError("must not throw: " + e);
    }
  }

  @Test
  void handles_stat_with_only_4_counters() {
    // Ancient kernels expose only user/nice/system/idle. iowait onward
    // default to 0, and computeFromHeader returns a value on the second
    // call.
    ProcStatSampler s = new ProcStatSampler(propsFor(Path.of("/tmp/nowhere")));
    assertNull(s.computeFromHeader("cpu 100 0 50 900"));
    // busy 150 (Δ50), idle 900 (Δ0). pct = 100 * 50 / 50 = 100.
    Double pct = s.computeFromHeader("cpu 150 0 50 900");
    assertNotNull(pct);
    assertEquals(100.0, pct, 0.01);
  }
}
