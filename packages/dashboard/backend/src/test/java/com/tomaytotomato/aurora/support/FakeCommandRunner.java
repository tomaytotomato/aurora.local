package com.tomaytotomato.aurora.support;

import com.tomaytotomato.aurora.services.CommandRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A real fake for {@link CommandRunner}: records what was asked for and
 * replays canned output. Not a mock — tests stub by intent ("when
 * something asks docker for a manifest, say this") and assert on what was
 * actually run, rather than on a call graph.
 *
 * <p>Which matters here because the argv is the interesting part. These
 * commands take operator-supplied values, so "was the package name passed
 * as one argument rather than spliced into a string" is exactly the
 * property worth asserting.
 *
 * <p>Unstubbed commands succeed silently with no output. That default is
 * deliberate: a test about update detection should not have to stub the
 * six unrelated things a service might also call, and a test that cares
 * asserts on {@link #invocations()}.
 */
public class FakeCommandRunner implements CommandRunner {

  /** One recorded call. */
  public record Invocation(Path workingDir, List<String> argv, Map<String, String> env) {
    public String command() {
      return String.join(" ", argv);
    }
  }

  private final List<Invocation> invocations = new ArrayList<>();

  /** Insertion-ordered so the first matching stub wins, like a router. */
  private final Map<String, Result> stubs = new LinkedHashMap<>();

  private Result fallback = Result.of(0, List.of());

  // ------------------------------------------------------------------
  // Stubbing
  // ------------------------------------------------------------------

  /**
   * Reply with {@code result} when the joined argv contains
   * {@code argvContains}. Substring matching rather than exact, so a test
   * can key on "manifest inspect" without restating the whole command.
   */
  public FakeCommandRunner stub(String argvContains, Result result) {
    stubs.put(argvContains, result);
    return this;
  }

  /** Reply successfully with these lines. */
  public FakeCommandRunner stubLines(String argvContains, String... lines) {
    return stub(argvContains, Result.of(0, List.of(lines)));
  }

  /** Reply with a non-zero exit and these lines, as a failing command would. */
  public FakeCommandRunner stubFailure(String argvContains, int exitCode, String... lines) {
    return stub(argvContains, Result.of(exitCode, List.of(lines)));
  }

  /** Reply as though the binary is not installed at all. */
  public FakeCommandRunner stubMissingBinary(String argvContains) {
    return stub(argvContains, Result.failedToStart("No such file or directory"));
  }

  /** Reply as though the command hung and was killed. */
  public FakeCommandRunner stubTimeout(String argvContains, String... linesBefore) {
    return stub(argvContains, Result.timedOut(List.of(linesBefore)));
  }

  /** What an unstubbed command returns. Defaults to a silent success. */
  public FakeCommandRunner defaultTo(Result result) {
    this.fallback = result;
    return this;
  }

  /** Forget every stub and every recorded call. */
  public void reset() {
    invocations.clear();
    stubs.clear();
    fallback = Result.of(0, List.of());
  }

  // ------------------------------------------------------------------
  // CommandRunner
  // ------------------------------------------------------------------

  @Override
  public synchronized Result run(Path workingDir, Duration timeout, Map<String, String> env,
                                 List<String> argv) {
    invocations.add(new Invocation(workingDir, List.copyOf(argv),
        env == null ? Map.of() : Map.copyOf(env)));
    return resultFor(argv);
  }

  @Override
  public synchronized int stream(Path workingDir, Map<String, String> env, List<String> argv,
                                 Consumer<String> onLine) {
    invocations.add(new Invocation(workingDir, List.copyOf(argv),
        env == null ? Map.of() : Map.copyOf(env)));
    Result result = resultFor(argv);
    for (String line : result.lines()) {
      onLine.accept(line);
    }
    // A binary that could not be started is a failure the caller must see,
    // and the real runner would have thrown; here the non-zero exit is
    // enough because every streaming caller checks it.
    return result.exitCode();
  }

  private Result resultFor(List<String> argv) {
    String joined = String.join(" ", argv);
    for (var entry : stubs.entrySet()) {
      if (joined.contains(entry.getKey())) {
        return entry.getValue();
      }
    }
    return fallback;
  }

  // ------------------------------------------------------------------
  // Assertions
  // ------------------------------------------------------------------

  public synchronized List<Invocation> invocations() {
    return List.copyOf(invocations);
  }

  /** True when some command's joined argv contained every one of these fragments. */
  public synchronized boolean ran(String... argvFragments) {
    return invocations.stream().anyMatch(i -> {
      String joined = i.command();
      for (String f : argvFragments) {
        if (!joined.contains(f)) return false;
      }
      return true;
    });
  }

  /** How many commands matched, for "exactly once" style assertions. */
  public synchronized long timesRan(String argvFragment) {
    return invocations.stream().filter(i -> i.command().contains(argvFragment)).count();
  }

  public synchronized Invocation lastInvocation() {
    if (invocations.isEmpty()) {
      throw new AssertionError("no commands were run");
    }
    return invocations.getLast();
  }

  /**
   * The first invocation whose argv contains the fragment, for asserting on
   * how arguments were passed.
   */
  public synchronized Invocation firstMatching(String argvFragment) {
    return invocations.stream()
        .filter(i -> i.command().contains(argvFragment))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "no command contained '" + argvFragment + "'; ran: " + invocations));
  }
}
