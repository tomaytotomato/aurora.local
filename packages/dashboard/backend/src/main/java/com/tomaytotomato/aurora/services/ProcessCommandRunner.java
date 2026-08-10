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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The real {@link CommandRunner}: {@code ProcessBuilder}, stderr merged
 * into stdout, hard timeout.
 *
 * <p>stderr is merged deliberately. Every one of these commands is
 * something an operator would have run by hand, and the interleaved
 * output is what they would have seen; splitting the streams would put
 * the error message somewhere other than next to the line that caused it.
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

  @Override
  public Result run(Path workingDir, Duration timeout, Map<String, String> env, List<String> argv) {
    if (argv == null || argv.isEmpty()) {
      return Result.failedToStart("no command given");
    }

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

    List<String> lines = new ArrayList<>();
    boolean truncated = false;
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (lines.size() < MAX_COLLECTED_LINES) {
          lines.add(line);
        } else if (!truncated) {
          truncated = true;
          lines.add("[aurora] output truncated at " + MAX_COLLECTED_LINES + " lines");
        }
      }
    } catch (IOException e) {
      log.debug("read failed for {}: {}", argv.getFirst(), e.getMessage());
    }

    try {
      long millis = timeout == null ? 30_000L : Math.max(1L, timeout.toMillis());
      if (!proc.waitFor(millis, TimeUnit.MILLISECONDS)) {
        proc.destroyForcibly();
        log.warn("command timed out after {}ms: {}", millis, argv);
        return Result.timedOut(lines);
      }
      return Result.of(proc.exitValue(), lines);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      proc.destroyForcibly();
      return new Result(-1, List.copyOf(lines), false, "interrupted");
    }
  }

  @Override
  public int stream(Path workingDir, Map<String, String> env, List<String> argv,
                    Consumer<String> onLine) throws IOException, InterruptedException {
    Process proc = builder(workingDir, env, argv).start();
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        onLine.accept(line);
      }
    }
    return proc.waitFor();
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
