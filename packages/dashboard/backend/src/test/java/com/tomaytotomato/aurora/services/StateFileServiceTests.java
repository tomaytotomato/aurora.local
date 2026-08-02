package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * iter-3 TD3 — StateFileService.mutateState() writes atomically.
 *
 * <p>Contract:
 * <ul>
 *   <li>Successful write does <em>not</em> leave a {@code .state.yml.tmp}
 *       sibling behind (the move consumed it).</li>
 *   <li>A mid-write crash (simulated by pre-existing {@code .state.yml.tmp}
 *       plus a mutation that would have used it) never truncates the
 *       original {@code .state.yml}; readState still parses cleanly.</li>
 *   <li>Round-trip preserves the pre-existing {@code hostname},
 *       {@code bootstrap_version}, and {@code installed_at} fields when
 *       only {@code enabled[]} is mutated.</li>
 * </ul>
 */
class StateFileServiceTests {

  private static StateFileService serviceFor(Path repoRoot) {
    AuroraProperties props = new AuroraProperties(
        repoRoot.toString(),
        "/host/proc",
        List.of(),
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
    return new StateFileService(props);
  }

  private static void seed(Path repoRoot) throws IOException {
    Files.writeString(repoRoot.resolve(".state.yml"),
        """
        bootstrap_version: 1
        hostname: aurora
        domain: aurora.local
        installed_at: '2026-08-02T09:00:00Z'
        enabled:
          - core
        profiles: []
        """);
  }

  @Test
  void mutateStateLeavesNoTmpSiblingOnSuccess(@TempDir Path repoRoot) throws IOException {
    seed(repoRoot);
    StateFileService svc = serviceFor(repoRoot);

    svc.writeEnabled(List.of("core", "media"));

    try (Stream<Path> siblings = Files.list(repoRoot)) {
      assertFalse(
          siblings.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
          "successful writeEnabled must not leak .state.yml.tmp");
    }
    assertEquals(List.of("core", "media"), svc.readState().enabled());
  }

  @Test
  void mutateStatePreservesOtherFieldsWhenMutatingEnabled(@TempDir Path repoRoot) throws IOException {
    seed(repoRoot);
    StateFileService svc = serviceFor(repoRoot);

    svc.writeEnabled(List.of("core", "privacy"));

    var state = svc.readState();
    assertEquals("aurora", state.hostname());
    assertEquals("aurora.local", state.domain());
    assertEquals(Integer.valueOf(1), state.bootstrapVersion());
    assertEquals("2026-08-02T09:00:00Z", state.installedAt());
    assertEquals(List.of("core", "privacy"), state.enabled());
  }

  @Test
  void mutateStateWritesViaSiblingTmpNotDirectly(@TempDir Path repoRoot) throws IOException {
    // Pre-write a broken .state.yml.tmp that would corrupt if the code
    // truncated it in-place: after the mutation the tmp must NOT exist
    // (it was moved onto .state.yml). And .state.yml must contain the
    // new value, not the broken tmp content.
    seed(repoRoot);
    Path tmp = repoRoot.resolve(".state.yml.tmp");
    Files.writeString(tmp, "!!!BROKEN YAML!!!\n");
    StateFileService svc = serviceFor(repoRoot);

    svc.writeDomain("home.local");

    assertFalse(Files.exists(tmp),
        ".state.yml.tmp must be consumed by the atomic move");
    assertEquals("home.local", svc.readState().domain());
    // Original hostname preserved by parse-modify-serialize round-trip.
    assertEquals("aurora", svc.readState().hostname());
  }

  @Test
  void simulatedMidWriteCrashLeavesOriginalIntact(@TempDir Path repoRoot) throws IOException {
    // The atomic-move contract is what protects mid-write crashes: if the
    // process died between .tmp write and the move, .state.yml still holds
    // the original bytes. Simulate the crash by manually recreating that
    // state: seed original, then only write the .tmp (never move).
    seed(repoRoot);
    Path state = repoRoot.resolve(".state.yml");
    Path tmp = repoRoot.resolve(".state.yml.tmp");
    Files.writeString(tmp, "domain: broken.example\n");

    StateFileService svc = serviceFor(repoRoot);
    // Reading state at this point must still see the original content, not
    // the half-written tmp; the read path only touches .state.yml.
    var state1 = svc.readState();
    assertEquals("aurora.local", state1.domain());
    assertTrue(Files.exists(state), "original .state.yml must survive a crash-during-.tmp-write");

    // And the next successful write cleans up the stale tmp on its own path
    // (it overwrites .state.yml.tmp before the move).
    svc.writeDomain("home.local");
    assertFalse(Files.exists(tmp));
    assertEquals("home.local", svc.readState().domain());
  }
}
