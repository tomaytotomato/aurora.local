package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the two claims the read-only audit made about the real
 * {@link CommandRunner}:
 *
 * <ol>
 *   <li>Finding 2 — {@link ProcessCommandRunner#run} read output to EOF
 *       before ever checking its own deadline, so a process that never
 *       closed its stdout kept the declared timeout from firing until the
 *       process exited on its own.</li>
 *   <li>Finding 1 — {@link ProcessCommandRunner#stream} had no ceiling at
 *       all, so a wedged {@code docker compose} would hold the launch job
 *       (and its single-in-flight lock) forever.</li>
 * </ol>
 *
 * <p>Real {@code sh} processes are used throughout rather than a fake:
 * the bug lives in how a blocking {@code InputStream.read()} interacts
 * with a real OS pipe, and a fake reader would not reproduce it. To keep
 * the suite fast, every process here is bounded to a couple of seconds at
 * most even in the un-fixed case, and every test carries a JUnit
 * {@code @Timeout} as a backstop against a regression turning this back
 * into a hang.
 */
class ProcessCommandRunnerTests {

  @Nested
  class RunTimeout {

    /**
     * The reproduction the task asked for, kept as a permanent regression
     * test. Against the pre-fix implementation this process (which sleeps
     * for 3 seconds and produces no output at all) made {@code run()} take
     * the full 3 seconds and return a *successful* exit 0 — the declared
     * 250ms timeout never fired, because the read-to-EOF loop blocked
     * until the process closed its own stdout by exiting naturally. Fixed,
     * this returns within a few hundred milliseconds of the declared
     * timeout and reports {@link CommandRunner.Result#timedOut()}.
     */
    @Test
    @Timeout(10)
    void bounds_wall_clock_time_even_though_the_process_never_produces_output() {
      var runner = new ProcessCommandRunner();
      Instant start = Instant.now();

      var result = runner.run(null, Duration.ofMillis(250), java.util.Map.of(),
          List.of("sh", "-c", "sleep 3"));

      Duration elapsed = Duration.between(start, Instant.now());
      assertThat(result.timedOut())
          .as("declared 250ms timeout should have fired instead of waiting out the 3s sleep")
          .isTrue();
      assertThat(result.ok()).isFalse();
      assertThat(elapsed)
          .as("elapsed wall-clock time should be bounded near the declared timeout, not the process's own runtime")
          .isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @Timeout(10)
    void kills_descendant_processes_not_just_the_direct_child() throws InterruptedException {
      var runner = new ProcessCommandRunner();

      // The direct child is `sh`; the real work — and the process that
      // would otherwise be orphaned and keep running — is the `sleep`
      // it backgrounds and then waits on. This is the shape of `bash
      // up.sh` running `docker compose`, not a single flat process.
      var result = runner.run(null, Duration.ofMillis(250), java.util.Map.of(),
          List.of("sh", "-c", "sleep 4321 & wait"));

      assertThat(result.timedOut()).isTrue();

      // Give the OS a brief, bounded window to finish reaping before we
      // assert nothing with our marker is still running.
      boolean stillRunning = true;
      for (int i = 0; i < 40 && stillRunning; i++) {
        stillRunning = ProcessHandle.allProcesses()
            .anyMatch(ph -> ph.info().commandLine().map(cl -> cl.contains("4321")).orElse(false));
        if (stillRunning) Thread.sleep(50);
      }
      assertThat(stillRunning)
          .as("the sleep 4321 descendant must be killed along with its parent shell")
          .isFalse();
    }

    @Test
    @Timeout(10)
    void still_collects_full_output_for_a_command_that_finishes_within_the_timeout() {
      var runner = new ProcessCommandRunner();

      var result = runner.run(null, Duration.ofSeconds(5), java.util.Map.of(),
          List.of("sh", "-c", "echo hello; echo world"));

      assertThat(result.timedOut()).isFalse();
      assertThat(result.ok()).isTrue();
      assertThat(result.lines()).containsExactly("hello", "world");
    }
  }

  @Nested
  class StreamInactivityTimeout {

    /**
     * A short inactivity ceiling injected via the package-private
     * constructor so this proves the kill-on-silence behaviour in
     * milliseconds rather than the production 10-minute ceiling.
     */
    @Test
    @Timeout(10)
    void kills_a_silent_process_and_throws_a_distinguishable_timeout() {
      var runner = new ProcessCommandRunner(Duration.ofMillis(200));
      Instant start = Instant.now();

      assertThatThrownBy(() -> runner.stream(null, java.util.Map.of(),
          List.of("sh", "-c", "sleep 10"), line -> { }))
          .isInstanceOf(CommandTimeoutException.class);

      Duration elapsed = Duration.between(start, Instant.now());
      assertThat(elapsed)
          .as("should be killed near the 200ms inactivity ceiling, not run out the 10s sleep")
          .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @Timeout(10)
    void does_not_kill_a_process_that_keeps_producing_output() throws Exception {
      // Inactivity ceiling of 300ms, but this process never goes silent
      // for that long: five lines roughly 80ms apart. Total runtime
      // (~400ms) exceeds the ceiling, which is exactly the case a
      // total-duration timeout would get wrong and an inactivity one
      // should not.
      var runner = new ProcessCommandRunner(Duration.ofMillis(300));
      List<String> collected = new ArrayList<>();

      int exit = runner.stream(null, java.util.Map.of(), List.of("sh", "-c",
          "for i in 1 2 3 4 5; do echo line$i; sleep 0.08; done"), collected::add);

      assertThat(exit).isEqualTo(0);
      assertThat(collected).containsExactly("line1", "line2", "line3", "line4", "line5");
    }
  }

  @Nested
  class StreamCancellation {

    @Test
    @Timeout(10)
    void cancelling_the_token_stops_a_wedged_process_early() throws Exception {
      var runner = new ProcessCommandRunner();
      var cancelToken = new CommandRunner.CancelToken();
      AtomicInteger linesSeen = new AtomicInteger();

      CompletableFuture<Exception> outcome = CompletableFuture.supplyAsync(() -> {
        try {
          runner.stream(null, java.util.Map.of(), List.of("sh", "-c", "sleep 10"),
              line -> linesSeen.incrementAndGet(), cancelToken);
          return null;
        } catch (Exception e) {
          return e;
        }
      });

      Thread.sleep(150);
      Instant cancelledAt = Instant.now();
      cancelToken.cancel();

      Exception thrown = outcome.get(5, java.util.concurrent.TimeUnit.SECONDS);
      Duration elapsed = Duration.between(cancelledAt, Instant.now());

      assertThat(thrown).isInstanceOf(CommandCancelledException.class);
      assertThat(elapsed)
          .as("cancellation should take effect within a poll interval or two, not the 10s sleep")
          .isLessThan(Duration.ofSeconds(3));
    }
  }
}
