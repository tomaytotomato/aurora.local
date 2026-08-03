package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iter-3 §2a.i — classify raw launch failures into human copy + machine code.
 *
 * <p>Reason strings are for humans and MUST NOT contain shell substrings
 * (`sudo `, `docker `, `bash `, `./scripts/`, `ssh `). The last case in
 * this suite sweeps every classifier output for those substrings.
 */
class LaunchServiceClassifierTests {

  /** Shell-copy substrings that must never appear in user-facing reason strings. */
  private static final List<String> FORBIDDEN =
      List.of("sudo ", "docker ", "bash ", "./scripts/", "ssh ", "up.sh", "exited non-zero");

  private static void assertHumanCopy(String reason) {
    assertNotNull(reason, "reason must not be null");
    String lower = reason.toLowerCase();
    for (String f : FORBIDDEN) {
      assertFalse(lower.contains(f),
          "classifier reason must not contain '" + f + "': " + reason);
    }
  }

  @Test
  void port_conflict_extracts_port_number_and_prompts_action() {
    String tail = "Error response from daemon: driver failed programming external "
        + "connectivity on endpoint aurora-privacy-adguard "
        + "(abc): Bind for 0.0.0.0:53 failed: port is already allocated\n";
    var c = LaunchService.classify(tail, 1, "privacy", "up.sh exited 1");
    assertEquals("port_conflict", c.code());
    assertTrue(c.reason().contains("53"), "should surface the port number: " + c.reason());
    assertTrue(c.reason().toLowerCase().contains("in use"), c.reason());
    assertHumanCopy(c.reason());
  }

  @Test
  void port_conflict_bind_address_wording_also_matches() {
    String tail = "listen tcp 0.0.0.0:8080: bind: address already in use\n";
    var c = LaunchService.classify(tail, 1, "core", "up.sh exited 1");
    assertEquals("port_conflict", c.code());
    assertHumanCopy(c.reason());
  }

  @Test
  void pull_rate_limited_matches_toomanyrequests_and_429() {
    String a = "Error: toomanyrequests: You have reached your pull rate limit.\n";
    var ca = LaunchService.classify(a, 1, "media", "x");
    assertEquals("pull_rate_limited", ca.code());
    assertHumanCopy(ca.reason());

    String b = "unauthorized: 429 Too Many Requests\n";
    var cb = LaunchService.classify(b, 1, "media", "x");
    assertEquals("pull_rate_limited", cb.code());
    assertHumanCopy(cb.reason());
  }

  @Test
  void disk_full_classifies_as_disk_full() {
    String tail = "write /var/lib/docker/tmp/x: no space left on device\n";
    var c = LaunchService.classify(tail, 1, "storage", "x");
    assertEquals("disk_full", c.code());
    assertTrue(c.reason().toLowerCase().contains("full"), c.reason());
    assertHumanCopy(c.reason());
  }

  @Test
  void docker_down_classifies_as_docker_down_without_naming_docker_in_copy() {
    String tail = "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. "
        + "Is the docker daemon running?\n";
    var c = LaunchService.classify(tail, 1, "core", "x");
    assertEquals("docker_down", c.code());
    // Reason talks about "container engine" not "docker" so the copy stays
    // civilian-friendly and never trips the shell-copy sweep.
    assertHumanCopy(c.reason());
  }

  @Test
  void container_crashed_uses_container_name_when_available() {
    String tail = "Container aurora-media-sonarr Started\n"
        + "Container aurora-media-sonarr Exited (1)\n";
    var c = LaunchService.classify(tail, 1, "media", "x");
    assertEquals("container_crashed", c.code());
    assertTrue(c.reason().toLowerCase().contains("crashed"), c.reason());
    assertHumanCopy(c.reason());
  }

  @Test
  void bind_mount_missing_from_oci_runtime_error() {
    // Real failure captured 2026-08-01 when the aurora container mounted
    // the repo at /repo but shelled compose out to the host, so the host
    // daemon saw /repo/packages/core/caddy/Caddyfile (which didn't exist),
    // auto-created it as a directory, and mount failed with:
    String tail = "Error response from daemon: failed to create task for container: "
        + "failed to create shim task: OCI runtime create failed: runc create failed: "
        + "unable to start container process: error during container init: "
        + "error mounting \"/repo/packages/core/caddy/Caddyfile\" to rootfs at "
        + "\"/etc/caddy/Caddyfile\": mount src=/repo/packages/core/caddy/Caddyfile, "
        + "dst=/etc/caddy/Caddyfile, dstFd=/proc/thread-self/fd/14, flags=MS_BIND|MS_REC: "
        + "not a directory: Are you trying to mount a directory onto a file "
        + "(or vice-versa)?\n";
    var c = LaunchService.classify(tail, 1, "core", "up.sh exited 1");
    assertEquals("bind_mount_missing", c.code());
    assertTrue(c.reason().toLowerCase().contains("same path"),
        "reason should point at the same-path contract: " + c.reason());
    assertHumanCopy(c.reason());
  }

  @Test
  void unknown_fallback_still_returns_actionable_copy() {
    String tail = "something we've never seen before\n";
    var c = LaunchService.classify(tail, 1, "media", "up.sh exited 1");
    assertEquals("unknown", c.code());
    assertTrue(c.reason().toLowerCase().contains("log"), c.reason());
    assertHumanCopy(c.reason());
  }

  @Test
  void all_classifier_outputs_are_free_of_shell_substrings() {
    // Sweep across every branch with a fresh probe tail per row.
    String[][] cases = new String[][] {
        {"0.0.0.0:53 failed: port is already allocated", "port_conflict"},
        {"toomanyrequests: rate limit reached", "pull_rate_limited"},
        {"no space left on device", "disk_full"},
        {"Cannot connect to the Docker daemon", "docker_down"},
        {"Container aurora-x Exited (137)", "container_crashed"},
        {"mount src=/repo/x dst=/y flags=MS_BIND: not a directory", "bind_mount_missing"},
        {"something else", "unknown"},
    };
    for (String[] row : cases) {
      var c = LaunchService.classify(row[0], 1, "media", "x");
      assertEquals(row[1], c.code(), "row: " + row[0]);
      assertHumanCopy(c.reason());
    }
  }
}
