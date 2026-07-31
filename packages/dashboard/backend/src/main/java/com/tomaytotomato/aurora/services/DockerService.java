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

  private final DockerClient docker;

  public DockerService(DockerClient docker) {
    this.docker = docker;
  }

  public List<Container> listProjectContainers() {
    return docker.listContainersCmd()
        .withShowAll(true)
        .withLabelFilter(Map.of(PROJECT_LABEL, PROJECT_NAME))
        .exec();
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
