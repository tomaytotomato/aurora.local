package com.tomaytotomato.aurora.services;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
   * <p>{@code timeout} bounds total wall-clock time, not just the final
   * {@code waitFor}: a process that never closes its output (because it is
   * silently wedged rather than merely slow to finish) is killed within
   * {@code timeout} of being started, regardless of whether anything was
   * ever read from it.
   *
   * @param workingDir directory to run in; null means the JVM's own
   * @param timeout    hard ceiling on total wall-clock time; the process
   *                   (and any descendants it spawned) is killed on expiry
   *                   and the result reports {@link Result#timedOut()}
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
   * Run and stream each output line to {@code onLine} as it arrives, with
   * no way to cancel it early. Equivalent to calling the four-argument
   * overload with a fresh, never-cancelled {@link CancelToken}.
   *
   * @return the process exit code
   * @throws IOException              if the process could not be started
   * @throws InterruptedException     if the calling thread is interrupted while waiting
   * @throws CommandTimeoutException  if the process produced no output for
   *     longer than the implementation's inactivity ceiling; it and any
   *     descendants have already been killed by the time this is thrown
   */
  default int stream(Path workingDir, Map<String, String> env, List<String> argv,
                     Consumer<String> onLine) throws IOException, InterruptedException {
    return stream(workingDir, env, argv, onLine, new CancelToken());
  }

  /**
   * Run and stream each output line to {@code onLine} as it arrives.
   *
   * <p>Unlike {@link #run}, a streamed command is often legitimately
   * long-running (a {@code docker compose pull} of a large image can take
   * many minutes on home broadband), so there is no total-duration
   * ceiling here. Instead the implementation applies an inactivity
   * ceiling — no output for N minutes means the process has stopped
   * doing anything useful, whatever the reason — and {@code cancelToken}
   * lets a caller stop a job the operator asked to cancel without waiting
   * for that ceiling.
   *
   * @param cancelToken cooperative cancellation; call {@link CancelToken#cancel()}
   *                    from another thread to stop this command early
   * @return the process exit code
   * @throws IOException                if the process could not be started
   * @throws InterruptedException       if the calling thread is interrupted while waiting
   * @throws CommandTimeoutException    if the process produced no output for
   *     longer than the implementation's inactivity ceiling; it and any
   *     descendants have already been killed by the time this is thrown
   * @throws CommandCancelledException  if {@code cancelToken} was cancelled
   *     while the process was running; it and any descendants have already
   *     been killed by the time this is thrown
   */
  int stream(Path workingDir, Map<String, String> env, List<String> argv, Consumer<String> onLine,
      CancelToken cancelToken) throws IOException, InterruptedException;

  /**
   * Cooperative cancellation flag for {@link #stream}. One token per
   * command invocation; cancelling has no effect on anything else.
   *
   * <p>Cancelling is a request, not an instant: the implementation notices
   * on its next check (see the implementation for the poll interval), so
   * {@link #cancel()} returning does not itself mean the process is dead
   * yet.
   */
  final class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
      cancelled.set(true);
    }

    public boolean isCancelled() {
      return cancelled.get();
    }
  }

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
