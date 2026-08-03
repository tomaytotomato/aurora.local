package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for {@link LaunchService}. Do not actually shell out
 * to the real {@code scripts/up.sh}; instead, stage a fake {@code scripts/up.sh}
 * inside a temp repo whose exit code and output we control.
 */
class LaunchServiceTests {

  private static AuroraProperties props(Path repo) {
    return new AuroraProperties(
        repo.toString(),
        "/host/proc",
        List.of(),
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
  }

  private static Path stageFakeUpSh(Path repo, String body) throws IOException {
    Path scripts = repo.resolve("scripts");
    Files.createDirectories(scripts);
    Path up = scripts.resolve("up.sh");
    Files.writeString(up, "#!/usr/bin/env bash\n" + body);
    up.toFile().setExecutable(true);
    return up;
  }

  private static void awaitTerminal(LaunchService.Job job) throws InterruptedException {
    for (int i = 0; i < 200 && job.state == LaunchService.State.RUNNING; i++) {
      Thread.sleep(50);
    }
  }

  @Test
  void success_path_runs_and_finishes_with_exit_0(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo, "echo 'hello from up'\necho 'core healthy'\nexit 0\n");
    var audit = Mockito.mock(AuditEventRepo.class);
    var svc = new LaunchService(props(repo), audit);

    LaunchService.Job job = svc.startLaunch(List.of("core"));
    assertNotNull(job.id);
    assertEquals(LaunchService.State.RUNNING, job.state);

    awaitTerminal(job);
    assertEquals(LaunchService.State.SUCCESS, job.state);
    assertEquals(0, job.exitCode);
    assertNotNull(job.finishedAt);
    // Tail should have captured the two echoed lines.
    var status = job.toStatusMap();
    @SuppressWarnings("unchecked")
    List<String> tail = (List<String>) status.get("tail");
    assertTrue(tail.stream().anyMatch(s -> s.contains("hello from up")));
    assertTrue(tail.stream().anyMatch(s -> s.contains("core healthy")));
  }

  @Test
  void failure_path_reports_nonzero_exit(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo, "echo 'oh no'\nexit 7\n");
    var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));

    LaunchService.Job job = svc.startLaunch(List.of("core", "media"));
    awaitTerminal(job);

    assertEquals(LaunchService.State.FAILED, job.state);
    assertEquals(7, job.exitCode);
    assertNotNull(job.failureReason);
  }

  @Test
  void one_launch_at_a_time_rejects_second_start(@TempDir Path repo) throws Exception {
    // Slow enough to keep the first job in RUNNING while we attempt the second.
    stageFakeUpSh(repo, "sleep 1\necho done\nexit 0\n");
    var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));

    LaunchService.Job first = svc.startLaunch(List.of("core"));
    assertEquals(LaunchService.State.RUNNING, first.state);

    LaunchService.LaunchInProgressException ex = assertThrows(
        LaunchService.LaunchInProgressException.class,
        () -> svc.startLaunch(List.of("core")));
    assertEquals(first.id, ex.activeJobId);

    awaitTerminal(first);
    // After the first job terminates a new launch is allowed.
    LaunchService.Job second = svc.startLaunch(List.of("core"));
    awaitTerminal(second);
    assertEquals(LaunchService.State.SUCCESS, second.state);
  }

  @Test
  void missing_up_sh_yields_failed_job(@TempDir Path repo) throws Exception {
    // Intentionally do NOT stage scripts/up.sh.
    var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));
    LaunchService.Job job = svc.startLaunch(List.of("core"));
    awaitTerminal(job);
    assertEquals(LaunchService.State.FAILED, job.state);
    // Iter-3: failure reason is now classified user copy, not raw stderr.
    // The launcher never surfaces the raw "scripts/up.sh not found" string;
    // classify() falls through to the `unknown` fallback with actionable copy.
    assertNotNull(job.failureReason);
    assertEquals("unknown", job.failureCode);
    // Human copy sweep: must not contain shell substrings.
    String r = job.failureReason.toLowerCase();
    assertTrue(!r.contains("up.sh") && !r.contains("./scripts/") && !r.contains("sudo "),
        "classified reason must be human copy, was: " + job.failureReason);
  }

  @Test
  void log_file_is_bounded_when_up_sh_spews_more_than_cap(@TempDir Path repo) throws Exception {
    // P2 #3: emit ~7 MB of output. On-disk log must stop growing at ~5 MB
    // (+ a single truncation marker). In-memory tail is unaffected.
    String body = "for i in $(seq 1 70000); do "
        + "printf '%s\\n' '" + "x".repeat(96) + "'; "
        + "done\nexit 0\n";
    stageFakeUpSh(repo, body);
    var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));
    LaunchService.Job job = svc.startLaunch(List.of("core"));

    for (int i = 0; i < 600 && job.state == LaunchService.State.RUNNING; i++) {
      Thread.sleep(50);
    }
    assertEquals(LaunchService.State.SUCCESS, job.state, "launch should finish");

    if (job.logFile != null && Files.exists(job.logFile)) {
      long size = Files.size(job.logFile);
      assertTrue(size <= LaunchService.LOG_FILE_MAX_BYTES + 512,
          "on-disk log must be bounded (~" + LaunchService.LOG_FILE_MAX_BYTES + " bytes); was " + size);
      assertTrue(job.logTruncated, "truncated flag must be set once cap is hit");
    }
    @SuppressWarnings("unchecked")
    List<String> tail = (List<String>) job.toStatusMap().get("tail");
    assertTrue(tail.size() <= 200, "status-map tail is capped at 200 lines");
  }

  @Test
  void status_map_shape_before_and_after_completion(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo, "echo one\nexit 0\n");
    var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));
    LaunchService.Job job = svc.startLaunch(List.of("core"));
    awaitTerminal(job);

    var m = job.toStatusMap();
    assertEquals(job.id, m.get("id"));
    assertEquals("success", m.get("state"));
    assertEquals(0, m.get("exit_code"));
    assertNotNull(m.get("started_at"));
    assertNotNull(m.get("finished_at"));
    assertEquals(List.of("core"), m.get("packages"));
    assertNull(m.get("failure_reason"));
  }
}
