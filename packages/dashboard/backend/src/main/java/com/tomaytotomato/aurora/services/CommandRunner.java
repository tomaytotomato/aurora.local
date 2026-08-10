package com.tomaytotomato.aurora.services;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The single seam through which Aurora runs anything outside the JVM.
 *
 * <p>Why this exists: before it, {@code ProcessBuilder} appeared inline in
 * {@link LaunchService} and {@link MdnsAliasService}, which meant nothing
 * that shelled out could be tested without the real binary present and
 * the right privileges to run it. The backend now needs {@code docker
 * compose}, {@code docker manifest inspect}, {@code snapraid} and
 * {@code wg}; none of those belong in a unit test.
 *
 * <p>Two shapes, because the callers genuinely differ:
 * <ul>
 *   <li>{@link #run} collects output and returns it. For short reads
 *       where the caller wants an answer — a digest, a version, a status
 *       line.</li>
 *   <li>{@link #stream} hands each line to a consumer as it arrives and
 *       returns the exit code. For anything a person is watching, which
 *       is to say anything that becomes a {@link JobService} job.</li>
 * </ul>
 *
 * <p>Both take an explicit argv list rather than a command string. There
 * is deliberately no overload that accepts a single string to be split by
 * a shell: several of these commands take operator-supplied values
 * (package names, subdomains, snapshot ids) and the only reliable defence
 * is to never construct a shell command line in the first place.
 */
public interface CommandRunner {

  /**
   * Run to completion and collect stdout (with stderr merged in, since
   * every caller wants both interleaved as the operator would see them).
   *
   * @param workingDir directory to run in; null means the JVM's own
   * @param timeout    hard ceiling; the process is destroyed on expiry and
   *                   the result reports {@link Result#timedOut()}
   * @param env        extra environment entries, merged over the inherited
   *                   environment
   * @param argv       program and arguments, never shell-interpreted
   */
  Result run(Path workingDir, Duration timeout, Map<String, String> env, List<String> argv);

  /** Convenience for the common case: no working directory, no extra env, 30s ceiling. */
  default Result run(List<String> argv) {
    return run(null, Duration.ofSeconds(30), Map.of(), argv);
  }

  /** Convenience for a read in a specific directory. */
  default Result run(Path workingDir, List<String> argv) {
    return run(workingDir, Duration.ofSeconds(30), Map.of(), argv);
  }

  /**
   * Run and stream each output line to {@code onLine} as it arrives.
   *
   * @return the process exit code
   * @throws IOException          if the process could not be started
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  int stream(Path workingDir, Map<String, String> env, List<String> argv, Consumer<String> onLine)
      throws IOException, InterruptedException;

  /**
   * Outcome of a completed command.
   *
   * @param exitCode process exit status; -1 when it could not be started
   * @param lines    stdout and stderr interleaved, newlines stripped
   * @param timedOut true when the timeout expired and the process was killed
   * @param error    why it could not be started at all, else null
   */
  record Result(int exitCode, List<String> lines, boolean timedOut, String error) {

    public boolean ok() {
      return exitCode == 0 && !timedOut && error == null;
    }

    /** All output as one string, for pattern matching or JSON parsing. */
    public String text() {
      return String.join("\n", lines);
    }

    /** First line, or empty — the shape most single-value reads want. */
    public String firstLine() {
      return lines.isEmpty() ? "" : lines.getFirst();
    }

    public static Result of(int exitCode, List<String> lines) {
      return new Result(exitCode, List.copyOf(lines), false, null);
    }

    public static Result failedToStart(String error) {
      return new Result(-1, List.of(), false, error);
    }

    public static Result timedOut(List<String> lines) {
      return new Result(-1, List.copyOf(lines), true, null);
    }
  }
}
