package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.async.ResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thin wrapper around docker-java. Read-only for v0.1; no compose invocation
 * here — that lives in {@code ScriptRunner} (v0.2).
 */
@Service
public class DockerService {

  private static final Logger log = LoggerFactory.getLogger(DockerService.class);
  private static final String PROJECT_LABEL = "com.docker.compose.project";
  private static final String PROJECT_NAME = "aurora";
  /**
   * {@code scripts/up.sh} always runs {@code docker compose -p aurora ...},
   * overriding each compose.yml's own {@code name:} — verified on a
   * running testbed box, every container is labelled {@code aurora}
   * regardless of package. {@code aurora-<pkg>} only shows up if compose
   * is invoked directly, bypassing {@code up.sh}. Match both.
   */
  private static final String PROJECT_PREFIX = "aurora-";

  private final DockerClient docker;

  public DockerService(DockerClient docker) {
    this.docker = docker;
  }

  /**
   * Containers belonging to one package: project label {@code aurora-<pkg>}
   * (or, for {@code core}, {@code aurora} too), plus a container under the
   * shared {@code aurora} project whose own name equals
   * {@code expectedContainer} — the normal case per {@code PROJECT_PREFIX}'s
   * javadoc. Same tolerance {@link #findByName} already gives
   * {@code GET /services/status}, so the two surfaces agree.
   *
   * @param expectedContainer manifest {@code probe.container}, or the
   *                           package name when unset; {@code null} skips
   *                           the name-based match.
   */
  public List<Container> containersForPackage(String pkg, String expectedContainer) {
    Set<String> targetProjects = "core".equals(pkg)
        ? Set.of(PROJECT_NAME, PROJECT_PREFIX + "core")
        : Set.of(PROJECT_PREFIX + pkg);
    List<Container> out = new ArrayList<>();
    for (Container c : listProjectContainers()) {
      Map<String, String> labels = c.getLabels();
      String project = labels == null ? null : labels.get(PROJECT_LABEL);
      boolean projectMatches = targetProjects.contains(project);
      boolean legacyMatches = !projectMatches
          && PROJECT_NAME.equals(project)
          && expectedContainer != null
          && expectedContainer.equals(primaryName(c));
      if (projectMatches || legacyMatches) out.add(c);
    }
    return out;
  }

  public List<Container> listProjectContainers() {
    // Docker's label filter can't express prefix or OR. Two options:
    //   1. Fetch everything and post-filter in Java.
    //   2. Issue two calls (exact + label-exists) and merge.
    // Option 1 is simpler and cheap on a homelab-sized daemon.
    List<Container> raw = docker.listContainersCmd()
        .withShowAll(true)
        .exec();
    List<Container> out = new ArrayList<>();
    for (Container c : raw) {
      if (c.getLabels() == null) continue;
      String project = c.getLabels().get(PROJECT_LABEL);
      if (project == null) continue;
      if (PROJECT_NAME.equals(project) || project.startsWith(PROJECT_PREFIX)) {
        out.add(c);
      }
    }
    return out;
  }

  /**
   * Look up a container by its {@code container_name} (not compose service).
   * Returns empty when the container does not exist. Only inspects containers
   * from the aurora compose project.
   */
  public Optional<ContainerInfo> findByName(String containerName) {
    if (containerName == null || containerName.isBlank()) return Optional.empty();
    String target = "/" + containerName;
    for (Container c : listProjectContainers()) {
      String[] names = c.getNames();
      if (names == null) continue;
      for (String n : names) {
        if (target.equals(n) || containerName.equals(n)) {
          return Optional.of(new ContainerInfo(
              containerName,
              c.getState() == null ? "" : c.getState(),
              c.getStatus() == null ? "" : c.getStatus()));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Trimmed view of a container for status-probe consumers. Keeps
   * {@link StatusProbeService} independent of docker-java's mockability.
   */
  public record ContainerInfo(String name, String state, String status) {
    public boolean isRunning() { return "running".equalsIgnoreCase(state); }
    public boolean isExited() { return "exited".equalsIgnoreCase(state) || "dead".equalsIgnoreCase(state); }
  }

  /**
   * Trimmed view of a running container for {@code /api/proxy/targets}:
   * name, the ports it listens on, and the package that owns it (if any).
   */
  public record ContainerSummary(String name, List<Integer> ports, String pkg) {}

  /**
   * Every aurora-managed container worth pointing an address at, with the
   * ports it exposes and (best-effort) the package that owns it. Package
   * attribution reuses the same {@code com.docker.compose.project.config_files}
   * label parsing as {@code PackagesService.runningPackageNames()} — a
   * container not launched from a {@code packages/<name>/} compose file
   * (or with no labels at all) simply gets a {@code null} package.
   */
  public List<ContainerSummary> listContainerSummaries() {
    List<ContainerSummary> out = new ArrayList<>();
    for (Container c : listProjectContainers()) {
      String name = primaryName(c);
      if (name == null) continue;
      out.add(new ContainerSummary(name, ports(c), packageLabel(c)));
    }
    return out;
  }

  private static String primaryName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return null;
    String n = names[0];
    return n.startsWith("/") ? n.substring(1) : n;
  }

  private static List<Integer> ports(Container c) {
    var raw = c.getPorts();
    if (raw == null) return List.of();
    List<Integer> out = new ArrayList<>();
    for (var p : raw) {
      Integer priv = p.getPrivatePort();
      if (priv != null && !out.contains(priv)) out.add(priv);
    }
    out.sort(null);
    return out;
  }

  private static String packageLabel(Container c) {
    if (c.getLabels() == null) return null;
    String cfg = c.getLabels().get("com.docker.compose.project.config_files");
    if (cfg == null) return null;
    for (String seg : cfg.split(",")) {
      int i = seg.indexOf("/packages/");
      if (i < 0) continue;
      String rest = seg.substring(i + "/packages/".length());
      int slash = rest.indexOf('/');
      if (slash > 0) return rest.substring(0, slash);
    }
    return null;
  }

  public Optional<String> version() {
    try {
      var v = docker.versionCmd().exec();
      return Optional.ofNullable(v.getVersion());
    } catch (Exception e) {
      log.warn("docker version failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * B3 (v0.3): O(1) existence check for a container id/name against the
   * daemon. Distinct from {@link #findByName(String)} because that one is
   * scoped to the aurora compose project; log tail needs to work on any
   * container the operator can see (a rogue {@code docker run nextcloud}
   * should still be tailable from Aurora if the operator asks). Returns
   * empty on {@code NotFoundException} so the caller can emit a 404.
   */
  public Optional<String> inspectContainer(String idOrName) {
    if (idOrName == null || idOrName.isBlank()) return Optional.empty();
    try {
      var res = docker.inspectContainerCmd(idOrName).exec();
      return Optional.ofNullable(res.getId());
    } catch (com.github.dockerjava.api.exception.NotFoundException nfe) {
      return Optional.empty();
    } catch (Exception e) {
      log.debug("inspectContainer {} failed: {}", idOrName, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Full container inspect, or null when the container is gone or the
   * daemon is unreachable. Distinct from {@link #inspectContainer(String)}
   * (which returns only the id): callers that need the config/env — e.g.
   * {@code CoreDbIsolationRule} reading a container's environment — use
   * this. Never throws; a passive security rule must degrade to "no
   * evidence" rather than propagate.
   */
  public com.github.dockerjava.api.command.InspectContainerResponse rawInspect(String idOrName) {
    if (idOrName == null || idOrName.isBlank()) return null;
    try {
      return docker.inspectContainerCmd(idOrName).exec();
    } catch (Exception e) {
      log.debug("rawInspect {} failed: {}", idOrName, e.getMessage());
      return null;
    }
  }

  /**
   * B3 (v0.3): fetch the last {@code tail} lines from a container's log
   * stream. Snapshot only — no live follow; that's a v0.4 promotion.
   *
   * <p>Frames arrive from docker-java as an unbounded stream of
   * {@link Frame}s tagged with stdout/stderr. We collect frames until
   * the callback completes (docker closes the connection once tail
   * frames have been sent) or {@code timeout} elapses. Payload text is
   * decoded UTF-8 and split on newlines; empty trailing entries dropped.
   *
   * <p>Bounded to protect against pathological line lengths + rogue
   * containers spewing MBs of ANSI garbage: aborts at
   * {@link #LOG_BYTES_CAP} bytes and marks the result truncated.
   *
   * @param containerId  the container id or name (docker resolves both).
   * @param tail         number of trailing lines to request from docker.
   * @param timeout      max wall-clock wait for the tail collection.
   * @return a {@link LogTail} snapshot; {@link LogTail#lines} is empty when
   *         docker has no matching container (rather than throwing) so the
   *         caller can distinguish "no logs" from "no such container" via
   *         a prior {@link #inspectContainer}.
   */
  public LogTail tailLogs(String containerId, int tail, Duration timeout) {
    List<LogLine> collected = new ArrayList<>();
    java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong(0);
    java.util.concurrent.atomic.AtomicBoolean truncated = new java.util.concurrent.atomic.AtomicBoolean(false);
    var cb = new ResultCallback.Adapter<Frame>() {
      @Override public void onNext(Frame f) {
        if (truncated.get()) return;
        byte[] payload = f.getPayload();
        if (payload == null || payload.length == 0) return;
        long total = bytes.addAndGet(payload.length);
        if (total > LOG_BYTES_CAP) {
          truncated.set(true);
          return;
        }
        String stream = f.getStreamType() == StreamType.STDERR ? "stderr" : "stdout";
        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        // Docker prefixes each line with an RFC3339 timestamp when
        // withTimestamps(true). Split on the first space; if no space
        // is present treat the whole payload as text with no ts.
        for (String rawLine : text.split("\n")) {
          if (rawLine.isEmpty()) continue;
          int sp = rawLine.indexOf(' ');
          String ts = null;
          String line = rawLine;
          if (sp > 0 && sp < 40) {
            String maybeTs = rawLine.substring(0, sp);
            // Cheap RFC3339 shape check: starts with 4 digits + dash.
            if (maybeTs.length() >= 5
                && Character.isDigit(maybeTs.charAt(0))
                && Character.isDigit(maybeTs.charAt(1))
                && Character.isDigit(maybeTs.charAt(2))
                && Character.isDigit(maybeTs.charAt(3))
                && maybeTs.charAt(4) == '-') {
              ts = maybeTs;
              line = rawLine.substring(sp + 1);
            }
          }
          collected.add(new LogLine(ts, stream, line));
        }
      }
    };
    try {
      docker.logContainerCmd(containerId)
          .withTail(tail)
          .withStdOut(true)
          .withStdErr(true)
          .withTimestamps(true)
          .withFollowStream(false)
          .exec(cb);
      cb.awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (com.github.dockerjava.api.exception.NotFoundException nfe) {
      // No such container. Return empty rather than propagating so the
      // controller can emit a 404 without try/catch gymnastics; the
      // controller pre-checks existence anyway.
      return new LogTail(List.of(), false);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("tailLogs {} failed: {}", containerId, e.getMessage());
    } finally {
      try { cb.close(); } catch (Exception ignore) { /* best-effort */ }
    }
    // Client-side belt-and-braces: cap the final list at 'tail'. Docker's
    // tail semantics are usually correct but a chatty stderr can slip a
    // few extra frames in during the closing window.
    List<LogLine> capped = collected.size() > tail
        ? new ArrayList<>(collected.subList(collected.size() - tail, collected.size()))
        : collected;
    return new LogTail(capped, truncated.get());
  }

  /** Payload cap for {@link #tailLogs}. 2 MiB — comfortable for 200 lines
   *  and a hard stop against a runaway log line. */
  public static final long LOG_BYTES_CAP = 2L * 1024L * 1024L;

  public record LogLine(String ts, String stream, String line) {}
  public record LogTail(List<LogLine> lines, boolean truncated) {}

  /**
   * Start streaming docker events. Caller is responsible for closing the
   * returned {@link Closeable} to stop the stream.
   */
  public Closeable streamEvents(Consumer<Event> sink, Consumer<Throwable> onError) {
    var cmd = docker.eventsCmd();
    var cb = new ResultCallback.Adapter<Event>() {
      @Override public void onNext(Event event) { sink.accept(event); }
      @Override public void onError(Throwable throwable) { onError.accept(throwable); }
    };
    cmd.exec(cb);
    return cb;
  }

  /** For controllers that want the compose service name for a container. */
  public static String composeService(Container c) {
    if (c.getLabels() == null) return null;
    return c.getLabels().get("com.docker.compose.service");
  }

  /**
   * Read a file out of a running container by exec'ing {@code cat} inside it.
   * Used to fetch the Caddy root CA (v0.1 stores it under
   * {@code /data/caddy/pki/authorities/local/root.crt} which is root-owned).
   *
   * <p>Returns empty if the container isn't running or {@code cat} exits
   * non-zero. Bounded by a short timeout so a stuck exec can't wedge the
   * request thread.
   */
  public Optional<byte[]> readFileFromContainer(String containerName, String path) {
    try {
      String execId = docker.execCreateCmd(containerName)
          .withAttachStdout(true)
          .withAttachStderr(true)
          .withCmd("cat", path)
          .exec()
          .getId();

      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      var cb = new ResultCallback.Adapter<Frame>() {
        @Override public void onNext(Frame f) {
          try {
            if (f.getStreamType() == StreamType.STDERR) {
              stderr.write(f.getPayload());
            } else {
              stdout.write(f.getPayload());
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      };
      docker.execStartCmd(execId).exec(cb).awaitCompletion(10, TimeUnit.SECONDS);
      cb.close();

      Long exit = docker.inspectExecCmd(execId).exec().getExitCodeLong();
      if (exit == null || exit != 0L) {
        log.warn("exec cat {} in {} exited {}: {}", path, containerName, exit,
            stderr.toString().trim());
        return Optional.empty();
      }
      return Optional.of(stdout.toByteArray());
    } catch (Exception e) {
      log.warn("exec cat {} in {} failed: {}", path, containerName, e.getMessage());
      return Optional.empty();
    }
  }
}
