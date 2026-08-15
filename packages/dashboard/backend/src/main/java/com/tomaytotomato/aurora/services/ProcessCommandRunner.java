package com.tomaytotomato.aurora.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The real {@link CommandRunner}: {@code ProcessBuilder}, stderr merged
 * into stdout, hard timeout.
 *
 * <p>stderr is merged deliberately. Every one of these commands is
 * something an operator would have run by hand, and the interleaved
 * output is what they would have seen; splitting the streams would put
 * the error message somewhere other than next to the line that caused it.
 *
 * <p>Both {@link #run} and {@link #stream} read process output on a
 * dedicated reader thread rather than the calling thread. That is not
 * incidental: a process that never closes its stdout — because it is
 * silently wedged rather than merely slow — keeps a blocking
 * {@code readLine()} call parked forever. If the deadline check only ran
 * after that loop returned, as it did before, the declared timeout would
 * never fire until the process exited on its own, which is exactly the
 * bug this class exists to not have.
 */
@Service
public class ProcessCommandRunner implements CommandRunner {

  private static final Logger log = LoggerFactory.getLogger(ProcessCommandRunner.class);

  /**
   * Ceiling on collected output for {@link #run}. A command that produces
   * more than this is one whose output nobody is reading in full, and an
   * unbounded list is a memory leak waiting for a pathological case.
   * Streaming callers are unaffected.
   */
  static final int MAX_COLLECTED_LINES = 10_000;

  /**
   * Ceiling on total silence for {@link #stream}: no output for this long
   * and the process (plus any descendants) is killed.
   *
   * <p>A total-duration ceiling would be the wrong shape here. A
   * {@code docker compose pull} of a large multi-gigabyte image legitimately
   * takes many minutes on home broadband, and killing a pull that is
   * working, just slowly, would turn "this box has a slow uplink" into
   * "this box is broken". What actually distinguishes a wedge from a slow
   * pull is silence: Docker keeps printing per-layer progress throughout a
   * real pull, so genuine inactivity this long means the daemon, the
   * network, or the process itself has stopped doing anything — which is
   * the situation Finding 1 describes, and the one worth killing for.
   */
  static final Duration DEFAULT_STREAM_INACTIVITY_TIMEOUT = Duration.ofMinutes(10);

  /** How often the watchdog re-checks activity and cancellation. */
  private static final Duration WATCHDOG_POLL_INTERVAL = Duration.ofMillis(250);

  private final Duration streamInactivityTimeout;

  public ProcessCommandRunner() {
    this(DEFAULT_STREAM_INACTIVITY_TIMEOUT);
  }

  /**
   * Test seam: a shorter inactivity ceiling so the timeout path can be
   * proven in milliseconds rather than minutes. Package-private —
   * production always gets {@link #DEFAULT_STREAM_INACTIVITY_TIMEOUT} via
   * the no-arg constructor Spring wires.
   */
  ProcessCommandRunner(Duration streamInactivityTimeout) {
    this.streamInactivityTimeout = streamInactivityTimeout;
  }

  @Override
  public Result run(Path workingDir, Duration timeout, Map<String, String> env, List<String> argv) {
    if (argv == null || argv.isEmpty()) {
      return Result.failedToStart("no command given");
    }

    long timeoutMillis = timeout == null ? 30_000L : Math.max(1L, timeout.toMillis());

    Process proc;
    try {
      proc = builder(workingDir, env, argv).start();
    } catch (IOException e) {
      // Commonly "No such file or directory" when the binary is not in the
      // image. That is a legitimate answer, not an exception to propagate:
      // the caller decides whether an absent smartctl means "unknown" or
      // "broken".
      log.debug("could not start {}: {}", argv.getFirst(), e.getMessage());
      return Result.failedToStart(e.getMessage());
    }

    Instant deadline = Instant.now().plusMillis(timeoutMillis);
    List<String> lines = Collections.synchronizedList(new ArrayList<>());
    AtomicBoolean truncated = new AtomicBoolean(false);

    Thread reader = new Thread(() -> readLines(proc, argv, lines, truncated), "aurora-command-reader");
    reader.setDaemon(true);
    reader.start();

    try {
      long remaining = Duration.between(Instant.now(), deadline).toMillis();
      boolean exited = remaining > 0 && proc.waitFor(remaining, TimeUnit.MILLISECONDS);
      if (!exited) {
        log.warn("command timed out after {}ms: {}", timeoutMillis, argv);
        killTree(proc);
        // The kill closes the pipe, which unblocks the reader with EOF;
        // join so the copy below is not racing its last append.
        joinQuietly(reader, Duration.ofSeconds(2));
        return Result.timedOut(new ArrayList<>(lines));
      }
      // Process has exited; give the reader a moment to drain whatever was
      // already buffered in the pipe before handing back its output.
      joinQuietly(reader, Duration.ofSeconds(5));
      return Result.of(proc.exitValue(), new ArrayList<>(lines));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      killTree(proc);
      joinQuietly(reader, Duration.ofSeconds(2));
      return new Result(-1, new ArrayList<>(lines), false, "interrupted");
    }
  }

  private void readLines(Process proc, List<String> argv, List<String> lines, AtomicBoolean truncated) {
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (lines.size() < MAX_COLLECTED_LINES) {
          lines.add(line);
        } else if (truncated.compareAndSet(false, true)) {
          lines.add("[aurora] output truncated at " + MAX_COLLECTED_LINES + " lines");
        }
      }
    } catch (IOException e) {
      log.debug("read failed for {}: {}", argv.getFirst(), e.getMessage());
    }
  }

  @Override
  public int stream(Path workingDir, Map<String, String> env, List<String> argv,
                    Consumer<String> onLine, CancelToken cancelToken)
      throws IOException, InterruptedException {
    Process proc = builder(workingDir, env, argv).start();
    AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    AtomicReference<StreamOutcome> outcome = new AtomicReference<>(StreamOutcome.COMPLETED);

    Thread watchdog = new Thread(() -> watchStream(proc, argv, cancelToken, lastActivityNanos, outcome),
        "aurora-command-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();

    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        lastActivityNanos.set(System.nanoTime());
        onLine.accept(line);
      }
    } finally {
      // Nudges the watchdog out of its poll sleep promptly once the pipe
      // has closed; harmless if it has already returned on its own.
      watchdog.interrupt();
    }

    int exit = proc.waitFor();
    StreamOutcome result = outcome.get();
    if (result == StreamOutcome.CANCELLED) {
      throw new CommandCancelledException("command cancelled: " + String.join(" ", argv));
    }
    if (result == StreamOutcome.TIMED_OUT) {
      throw new CommandTimeoutException("command produced no output for " + streamInactivityTimeout
          + " and was killed: " + String.join(" ", argv));
    }
    return exit;
  }

  private void watchStream(Process proc, List<String> argv, CancelToken cancelToken,
                           AtomicLong lastActivityNanos, AtomicReference<StreamOutcome> outcome) {
    while (proc.isAlive()) {
      if (cancelToken != null && cancelToken.isCancelled()) {
        outcome.set(StreamOutcome.CANCELLED);
        log.info("command cancelled: {}", argv);
        killTree(proc);
        return;
      }
      long idleNanos = System.nanoTime() - lastActivityNanos.get();
      if (idleNanos > streamInactivityTimeout.toNanos()) {
        outcome.set(StreamOutcome.TIMED_OUT);
        log.warn("command produced no output for {} and was killed: {}", streamInactivityTimeout, argv);
        killTree(proc);
        return;
      }
      try {
        Thread.sleep(WATCHDOG_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private enum StreamOutcome { COMPLETED, TIMED_OUT, CANCELLED }

  /**
   * Kill a process and every descendant it has spawned so far. A plain
   * {@code Process#destroyForcibly()} only reaches the direct child;
   * anything that child forked (which is exactly the shape of
   * {@code bash up.sh} running {@code docker compose}, which runs its own
   * helpers) would otherwise be orphaned and keep running.
   */
  private void killTree(Process proc) {
    proc.descendants().forEach(ph -> ph.destroyForcibly());
    proc.destroyForcibly();
  }

  private void joinQuietly(Thread t, Duration wait) {
    try {
      t.join(wait.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private ProcessBuilder builder(Path workingDir, Map<String, String> env, List<String> argv) {
    ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
    if (workingDir != null) {
      pb.directory(workingDir.toFile());
    }
    if (env != null) {
      pb.environment().putAll(env);
    }
    return pb;
  }
}
