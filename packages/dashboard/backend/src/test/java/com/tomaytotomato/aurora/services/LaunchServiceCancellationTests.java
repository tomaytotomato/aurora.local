package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Findings 1 and part of the "make it impossible to wedge silently" brief:
 * a launch that stalls or gets cancelled must reach a terminal state and,
 * critically, must release {@code activeJobId} so the single-in-flight
 * lock does not outlive the job that held it.
 *
 * <p>Real {@code up.sh} scripts are staged on disk here, same as
 * {@link LaunchServiceTests} — {@link LaunchService} always talks to a
 * real {@link ProcessCommandRunner}, and the bug these tests guard is in
 * the interaction between that runner and a real process, not something
 * a stubbed command runner would reproduce.
 */
class LaunchServiceCancellationTests {

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

  /** Wait until the launched process has actually produced output. */
  private static void awaitOutput(LaunchService.Job job, String needle) throws Exception {
    for (int i = 0; i < 200; i++) {
      if (job.logFile != null && Files.exists(job.logFile)
          && Files.readString(job.logFile).contains(needle)) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("launched process never produced: " + needle);
  }

  private static void awaitTerminal(LaunchService.Job job) throws InterruptedException {
    for (int i = 0; i < 200 && job.state == LaunchService.State.RUNNING; i++) {
      Thread.sleep(50);
    }
  }

  @Nested
  class Cancel {

    @Test
    @Timeout(10)
    void cancelling_a_running_launch_marks_it_failed_and_releases_the_lock(@TempDir Path repo)
        throws Exception {
      // Long enough to still be RUNNING when cancel() is called; short
      // enough that a bug leaving it un-killed would still fail the test
      // promptly rather than hanging the suite.
      // "started" first, so the test can wait for the child process to
      // genuinely exist before cancelling it. Cancelling in the window
      // between startLaunch() returning and the runner thread spawning bash
      // made this flaky (~1 run in 200 in CI-like conditions), which is
      // worse than a slow test: a gate that fails at random teaches people
      // to re-run it rather than read it.
      stageFakeUpSh(repo, "echo started\nsleep 30\necho done\nexit 0\n");
      var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));

      LaunchService.Job job = svc.startLaunch(List.of("core"));
      assertThat(job.state).isEqualTo(LaunchService.State.RUNNING);
      awaitOutput(job, "started");

      boolean cancelled = svc.cancel(job.id);
      assertThat(cancelled).isTrue();

      awaitTerminal(job);
      assertThat(job.state).isEqualTo(LaunchService.State.FAILED);
      assertThat(job.failureCode).isEqualTo("cancelled");
      assertThat(job.failureReason).isNotBlank();

      // The lock must not outlive the job: a fresh launch should be
      // accepted immediately, not rejected with LaunchInProgressException.
      LaunchService.Job second = svc.startLaunch(List.of("core"));
      assertThat(second.id).isNotEqualTo(job.id);
    }

    @Test
    @Timeout(10)
    void cancelling_an_unknown_job_id_is_a_no_op(@TempDir Path repo) throws Exception {
      var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));
      assertThat(svc.cancel("no-such-job")).isFalse();
    }

    @Test
    @Timeout(10)
    void cancelling_a_job_that_already_finished_is_a_no_op(@TempDir Path repo) throws Exception {
      stageFakeUpSh(repo, "echo done\nexit 0\n");
      var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class));

      LaunchService.Job job = svc.startLaunch(List.of("core"));
      awaitTerminal(job);
      assertThat(job.state).isEqualTo(LaunchService.State.SUCCESS);

      assertThat(svc.cancel(job.id)).isFalse();
      // Cancelling after the fact must not retroactively flip a
      // successful job to failed.
      assertThat(job.state).isEqualTo(LaunchService.State.SUCCESS);
    }
  }

  @Nested
  class StalledLaunch {

    /**
     * The other half of Finding 1: a launch that stalls with no output at
     * all — the exact "docker compose pull hangs on a slow home
     * connection" shape — must not wedge the single-in-flight lock
     * forever either. A short inactivity ceiling is injected via
     * {@link ProcessCommandRunner}'s package-private constructor so this
     * proves the behaviour in milliseconds rather than the production
     * 10-minute one.
     */
    @Test
    @Timeout(10)
    void a_launch_that_produces_no_output_is_killed_and_the_lock_is_released(@TempDir Path repo)
        throws Exception {
      stageFakeUpSh(repo, "sleep 5\necho done\nexit 0\n");
      var commands = new ProcessCommandRunner(Duration.ofMillis(200));
      var svc = new LaunchService(props(repo), Mockito.mock(AuditEventRepo.class), null, null, commands);

      LaunchService.Job job = svc.startLaunch(List.of("core"));
      awaitTerminal(job);

      assertThat(job.state).isEqualTo(LaunchService.State.FAILED);
      assertThat(job.failureCode).isEqualTo("stalled");
      assertThat(job.failureReason).isNotBlank();

      LaunchService.Job second = svc.startLaunch(List.of("core"));
      assertThat(second.id).isNotEqualTo(job.id);
    }
  }
}
