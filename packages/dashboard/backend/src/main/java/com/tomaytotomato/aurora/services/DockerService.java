package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.async.ResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
}
