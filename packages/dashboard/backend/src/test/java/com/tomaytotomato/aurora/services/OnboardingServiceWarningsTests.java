package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the manifest-warning evaluator internals in
 * {@link OnboardingService}: predicate evaluation, ${…} interpolation,
 * byte-unit path resolution, and the running resource-budget check.
 *
 * <p>Helpers were intentionally left package-private so these tests can
 * hit them without reflection or plumbing crafted state through the
 * public {@code plan(...)} entry point.
 */
class OnboardingServiceWarningsTests {

  private static OnboardingService svc() {
    // Pure-helper tests: no repos, no docker, no state files touched.
    return new OnboardingService(null, null, null, null, null, null, null, null);
  }

  private static Map<String, Object> host(long memBytes, int threads,
                                          boolean gpu, long freeBytes) {
    return Map.of(
        "cpu", Map.of("threads", threads),
        "memory", Map.of("MemTotal", memBytes),
        "gpu", Map.of("present", gpu),
        "disks", List.of(Map.of("free_bytes", freeBytes)));
  }

  // ------------------ evaluateWarningCondition ------------------

  @Test
  void ramBelowMb_firesWhenTotalUnderThreshold() {
    var w = Map.<String, Object>of("if", Map.of("ram_below_mb", 8192));
    // 4 GB host, threshold 8 GB → fires.
    assertTrue(svc().evaluateWarningCondition(w,
        host(4L * 1024 * 1024 * 1024, 4, false, 100L * 1024 * 1024 * 1024)));
    // 16 GB host, threshold 8 GB → does not fire (byte↔MB math correct).
    assertFalse(svc().evaluateWarningCondition(w,
        host(16L * 1024 * 1024 * 1024, 4, false, 100L * 1024 * 1024 * 1024)));
  }

  @Test
  void freeDiskGbBelow_firesWhenLargestFreeUnderThreshold() {
    var w = Map.<String, Object>of("if", Map.of("free_disk_gb_below", 50));
    assertTrue(svc().evaluateWarningCondition(w,
        host(16L * 1024 * 1024 * 1024, 4, false, 10L * 1024 * 1024 * 1024)));
    assertFalse(svc().evaluateWarningCondition(w,
        host(16L * 1024 * 1024 * 1024, 4, false, 200L * 1024 * 1024 * 1024)));
  }

  @Test
  void cpuThreadsLt_firesOnLowThreadCount() {
    var w = Map.<String, Object>of("if", Map.of("cpu_threads_lt", 4));
    assertTrue(svc().evaluateWarningCondition(w,
        host(16L * 1024 * 1024 * 1024, 2, false, 100L * 1024 * 1024 * 1024)));
    assertFalse(svc().evaluateWarningCondition(w,
        host(16L * 1024 * 1024 * 1024, 8, false, 100L * 1024 * 1024 * 1024)));
  }

  @Test
  void noGpu_firesWhenGpuAbsentOnly() {
    var w = Map.<String, Object>of("if", Map.of("no_gpu", true));
    assertTrue(svc().evaluateWarningCondition(w,
        host(16L * 1024 * 1024 * 1024, 4, false, 100L * 1024 * 1024 * 1024)));
    assertFalse(svc().evaluateWarningCondition(w,
        host(16L * 1024 * 1024 * 1024, 4, true, 100L * 1024 * 1024 * 1024)));
  }

  @Test
  void unknownConditionKey_failsClosed() {
    var w = Map.<String, Object>of("if", Map.of("magnetic_flux_below", 42));
    assertFalse(svc().evaluateWarningCondition(w,
        host(1L * 1024 * 1024 * 1024, 1, false, 1L * 1024 * 1024 * 1024)));
  }

  @Test
  void missingHostFact_failsClosed() {
    var w = Map.<String, Object>of("if", Map.of("ram_below_mb", 8192));
    // Empty snapshot → no MemTotal → should NOT fire.
    assertFalse(svc().evaluateWarningCondition(w, Map.of()));
  }

  // ------------------ interpolate / resolvePath ------------------

  @Test
  void interpolate_expandsGbUnitSuffixOnBytesValue() {
    var h = host(16L * 1024 * 1024 * 1024, 4, false, 100L * 1024 * 1024 * 1024);
    String out = svc().interpolate("You have ${memory.MemTotal_gb} GB RAM.", h);
    assertEquals("You have 16.0 GB RAM.", out);
  }

  @Test
  void interpolate_leavesUnknownPathLiteral() {
    var h = host(16L * 1024 * 1024 * 1024, 4, false, 100L * 1024 * 1024 * 1024);
    String out = svc().interpolate("cpu=${cpu.threads} extra=${nope.nothing}", h);
    assertEquals("cpu=4 extra=${nope.nothing}", out);
  }

  @Test
  void resolvePath_returnsScalarWithoutUnit() {
    var h = host(16L * 1024 * 1024 * 1024, 4, false, 100L * 1024 * 1024 * 1024);
    assertEquals(4, svc().resolvePath("cpu.threads", h));
  }

  @Test
  void resolvePath_mbSuffixConvertsBytes() {
    var h = host(2L * 1024 * 1024 * 1024, 1, false, 0L);
    assertEquals("2048", svc().resolvePath("memory.MemTotal_mb", h));
  }

  // ------------------ evaluateResourceBudget ------------------

  @Test
  void budget_ramFiresWhenSelectionExceedsHostCeiling(@TempDir Path tmp) throws Exception {
    var svc = onboardingWithRepo(tmp);
    writeManifest(tmp, "heavy", "min_ram_mb: 16000\nmin_disk_gb: 1\n");
    writeManifest(tmp, "hog",   "min_ram_mb: 8000\nmin_disk_gb: 1\n");
    // 16 GB host → 85% ≈ 13927 MB. Sum = 24000 MB → fires.
    var host = host(16L * 1024 * 1024 * 1024, 4, false, 500L * 1024 * 1024 * 1024);
    var out = svc.evaluateResourceBudget(List.of("heavy", "hog"), host);
    assertEquals(1, out.size(), "expected one budget warning, got " + out);
    assertTrue(out.get(0).startsWith("budget_ram_high:"), out.get(0));
  }

  @Test
  void budget_diskFiresWhenSelectionExceedsFree(@TempDir Path tmp) throws Exception {
    var svc = onboardingWithRepo(tmp);
    writeManifest(tmp, "photos", "min_ram_mb: 100\nmin_disk_gb: 200\n");
    writeManifest(tmp, "media",  "min_ram_mb: 100\nmin_disk_gb: 300\n");
    // 100 GB free → 85% = 85 GB. Sum = 500 GB → fires disk, not ram.
    var host = host(64L * 1024 * 1024 * 1024, 8, true, 100L * 1024 * 1024 * 1024);
    var out = svc.evaluateResourceBudget(List.of("photos", "media"), host);
    assertEquals(1, out.size(), out.toString());
    assertTrue(out.get(0).startsWith("budget_disk_high:"), out.get(0));
  }

  @Test
  void budget_silentWhenHostFactsMissing(@TempDir Path tmp) throws Exception {
    var svc = onboardingWithRepo(tmp);
    writeManifest(tmp, "heavy", "min_ram_mb: 999999\nmin_disk_gb: 999999\n");
    // Empty host snapshot → no MemTotal / no disks → silent.
    var out = svc.evaluateResourceBudget(List.of("heavy"), Map.of());
    assertTrue(out.isEmpty(), "expected silent, got " + out);
  }

  @Test
  void budget_missingRequiresTreatedAsZero(@TempDir Path tmp) throws Exception {
    var svc = onboardingWithRepo(tmp);
    // No requires block at all.
    writeManifest(tmp, "core", "name: core\n");
    var host = host(1L * 1024 * 1024 * 1024, 1, false, 1L * 1024 * 1024 * 1024);
    var out = svc.evaluateResourceBudget(List.of("core"), host);
    assertTrue(out.isEmpty());
  }

  // ------------------ scaffolding for budget tests ------------------

  private static OnboardingService onboardingWithRepo(Path repo) throws Exception {
    Files.createDirectories(repo.resolve("packages"));
    AuroraProperties props = new AuroraProperties(
        repo.toString(),
        "/host/proc",
        null,
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
    // PackagesService needs docker only for enabled/running diffing, which
    // this test does not exercise. Pass null and it is not dereferenced by
    // readRequires / readWarnings.
    PackagesService pkgs = new PackagesService(props, null, null, null);
    return new OnboardingService(null, null, null, null, null, pkgs, null, props);
  }

  private static void writeManifest(Path repo, String name, String requiresBody) throws Exception {
    Path dir = repo.resolve("packages").resolve(name);
    Files.createDirectories(dir);
    // Wrap the body under a `requires:` key unless the caller already provided a
    // full manifest (detected by presence of a top-level `name:`).
    String body;
    if (requiresBody.contains("name:")) {
      body = requiresBody;
    } else {
      StringBuilder sb = new StringBuilder("name: ").append(name).append('\n')
          .append("requires:\n");
      for (String line : requiresBody.split("\n")) {
        if (!line.isBlank()) sb.append("  ").append(line).append('\n');
      }
      body = sb.toString();
    }
    Files.writeString(dir.resolve("manifest.yml"), body);
  }
}
