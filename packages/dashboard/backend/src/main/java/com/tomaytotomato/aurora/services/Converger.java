package com.tomaytotomato.aurora.services;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Brings a requested set of packages up. The seam that lets
 * {@link LaunchService} own jobs, SSE and failure classification while the
 * actual converge — dependency resolution, {@code docker compose up}, state
 * — lives behind this interface.
 *
 * <p>The production implementation is {@link ComposeConvergeService} (Java
 * owns the orchestration, {@code docker compose} is the engine). Before it,
 * {@code LaunchService} shelled out to {@code scripts/up.sh}; that path
 * survives only as a null-converger fallback for the legacy unit tests.
 */
public interface Converger {

  /**
   * Resolve the request to its full dependency closure and bring it up,
   * streaming progress line by line.
   *
   * @param requestedPackages package names as asked for (not necessarily
   *                          dependency-resolved — a single {@code start}
   *                          may name one package)
   * @param selfLaunch        true when the converge is issued from inside
   *                          the dashboard's own container, so its own
   *                          service(s) are excluded from {@code up -d}
   * @param onLine            each line of output as it arrives
   * @param cancelToken       cooperative cancellation
   * @return the {@code docker compose up} exit code
   */
  int converge(List<String> requestedPackages, boolean selfLaunch,
               Consumer<String> onLine, CommandRunner.CancelToken cancelToken)
      throws IOException, InterruptedException;
}
