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
   * Aurora's LaunchService may launch package stacks with any of:
   * <ul>
   *   <li>{@code -p aurora} (shared project, historical) → label
   *       {@code com.docker.compose.project=aurora}</li>
   *   <li>The per-package name from compose.yml top-level {@code name:}
   *       (e.g. {@code aurora-notes}, {@code aurora-media}, {@code aurora-core},
   *       {@code aurora-dashboard}) → label
   *       {@code com.docker.compose.project=aurora-<pkg>}</li>
   * </ul>
   * Historically we filtered on the shared {@code aurora} project only,
   * which under-counted every stack launched with its declared
   * per-package name. That produced the System-card {@code Containers 1}
   * anomaly Bruce reported on 2026-08-02 (aurora + caddy + silverbullet
   * live, only silverbullet visible to the label filter). Broadening to
   * {@code aurora} OR {@code aurora-*} keeps the semantics honest without
   * conflating with unrelated projects.
   */
  private static final String PROJECT_PREFIX = "aurora-";

  private final DockerClient docker;

  public DockerService(DockerClient docker) {
    this.docker = docker;
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
