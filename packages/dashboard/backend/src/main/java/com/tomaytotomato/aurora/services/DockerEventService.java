package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * B1 (v0.3 groundwork): subscribes to {@code dockerClient.eventsCmd()} via
 * {@link DockerService#streamEvents}, filters to container-lifecycle
 * events, records them in a rolling in-memory buffer (cap {@link #BUFFER_MAX}),
 * and fans them out to any attached {@link SseEmitter} subscribers.
 *
 * <p>Drives the "Recent changes" surface on the dashboard home so the card
 * stops fabricating an integer and shows real container transitions.
 *
 * <p>Event vocabulary (filtered set — everything else is dropped so the
 * ring buffer isn't drowned in {@code exec_*} noise from
 * {@link LaunchService} and health probes):
 * <pre>
 *   create, start, stop, restart, pause, unpause, die, kill, destroy,
 *   health_status: healthy, health_status: unhealthy, oom
 * </pre>
 *
 * <p>Reconnect: docker daemon restarts, socket blips, or bind-mount
 * unavailability at boot are the normal failure modes. On error we
 * schedule a reconnect with a modest fixed backoff — no exponential
 * ceiling needed on a single-node homelab.
 */
@Service
public class DockerEventService {

  private static final Logger log = LoggerFactory.getLogger(DockerEventService.class);

  /** Rolling buffer cap. Old entries drop off the front once we hit it. */
  static final int BUFFER_MAX = 200;

  /** Delay before we retry the docker events subscription on failure. */
  static final long RECONNECT_DELAY_MS = 5_000L;

  /**
   * Lifecycle actions we surface. Health probes, exec_*, attach, mount,
   * top, etc., are dropped so the "Recent changes" card stays meaningful.
   * "health_status:" is a prefix — docker emits "health_status: healthy"
   * / "health_status: unhealthy" and we surface both.
   */
  private static final Set<String> LIFECYCLE_ACTIONS = Set.of(
      "create", "start", "stop", "restart",
      "pause", "unpause",
      "die", "kill", "destroy", "oom"
  );

  private final DockerService docker;

  /** Ring buffer of accepted events. Guarded by its own monitor. */
  private final Deque<ContainerEvent> buffer = new ArrayDeque<>(BUFFER_MAX);

  /** Attached SSE subscribers. CoW list because iterate-vs-modify race. */
  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  private final ScheduledExecutorService reconnector = Executors.newSingleThreadScheduledExecutor(
      r -> {
        Thread t = new Thread(r, "docker-events-reconnect");
        t.setDaemon(true);
        return t;
      });

  private volatile Closeable subscription;
  private volatile boolean shutdown;

  public DockerEventService(DockerService docker) {
    this.docker = docker;
  }

  @PostConstruct
  void start() {
    connect();
  }

  @PreDestroy
  void stop() {
    shutdown = true;
    reconnector.shutdownNow();
    Closeable sub = subscription;
    if (sub != null) {
      try { sub.close(); } catch (Exception ignore) { /* best-effort */ }
    }
    for (SseEmitter e : emitters) {
      try { e.complete(); } catch (Exception ignore) { /* best-effort */ }
    }
    emitters.clear();
  }

  private void connect() {
    if (shutdown) return;
    try {
      subscription = docker.streamEvents(this::onDockerEvent, this::onDockerError);
      log.info("subscribed to docker container events");
    } catch (Exception e) {
      log.warn("docker event subscription failed, will retry in {}s: {}",
          RECONNECT_DELAY_MS / 1000, e.getMessage());
      scheduleReconnect();
    }
  }

  private void onDockerError(Throwable t) {
    log.warn("docker event stream error: {}", t.getMessage());
    Closeable sub = subscription;
    if (sub != null) {
      try { sub.close(); } catch (Exception ignore) { /* best-effort */ }
    }
    subscription = null;
    scheduleReconnect();
  }

  private void scheduleReconnect() {
    if (shutdown || reconnector.isShutdown()) return;
    try {
      reconnector.schedule(this::connect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      // Executor already shut down (@PreDestroy race). Nothing to do.
      log.debug("reconnect scheduling skipped: {}", e.getMessage());
    }
  }

  private void onDockerEvent(Event event) {
    recordEvent(event);
  }

  /**
   * Package-private hook so tests can inject synthetic events without
   * standing up a real docker daemon. Applies the same filter + buffer
   * + fanout pipeline as the live subscription.
   *
   * @return true if the event was accepted (recorded + fanned out), false
   *         if it was dropped by the filter.
   */
  boolean recordEvent(Event event) {
    ContainerEvent ce = toContainerEvent(event);
    if (ce == null) return false;
    synchronized (buffer) {
      if (buffer.size() >= BUFFER_MAX) buffer.removeFirst();
      buffer.addLast(ce);
    }
    fanout(ce);
    return true;
  }

  /**
   * Best-effort translation of a docker-java {@link Event} into the
   * trimmed {@link ContainerEvent} the UI consumes. Returns null (drop)
   * when the event isn't a container-lifecycle transition we care about.
   */
  static ContainerEvent toContainerEvent(Event event) {
    if (event == null) return null;
    // We only surface container-type events; network/image/volume events
    // aren't useful in a "Recent changes" card and would drown the buffer.
    if (event.getType() != EventType.CONTAINER) return null;

    String action = event.getAction();
    if (action == null) return null;
    String actionLc = action.toLowerCase();

    // Health-status events arrive as "health_status: healthy" or
    // "health_status: unhealthy". Normalise both to a single action name
    // so the frontend can render a stable icon per health verdict.
    String normalised;
    if (actionLc.startsWith("health_status")) {
      normalised = actionLc.contains("unhealthy") ? "health:unhealthy" : "health:healthy";
    } else if (LIFECYCLE_ACTIONS.contains(actionLc)) {
      normalised = actionLc;
    } else {
      return null;
    }

    String container = null;
    if (event.getActor() != null && event.getActor().getAttributes() != null) {
      container = event.getActor().getAttributes().get("name");
    }
    // Fall back to the deprecated getId() slot if the actor is missing —
    // older docker daemons don't populate Actor. Better a hash than "".
    if (container == null || container.isBlank()) {
      container = event.getId() == null ? "unknown" : event.getId();
    }

    String image = event.getFrom();
    // Docker emits time in seconds since epoch. Some events also expose
    // nanos; we don't need sub-second precision for a UI card.
    long tsMs = event.getTime() == null ? System.currentTimeMillis()
        : event.getTime() * 1000L;

    return new ContainerEvent(tsMs, container, normalised, image);
  }

  private void fanout(ContainerEvent ce) {
    Map<String, Object> payload = ce.toMap();
    for (SseEmitter e : emitters) {
      try {
        e.send(SseEmitter.event().name("container-event").data(payload));
      } catch (IOException io) {
        // Client gone — cleanup registered via onCompletion.
      } catch (Exception ex) {
        log.debug("container-event fanout failed: {}", ex.toString());
      }
    }
  }

  /**
   * Attach an emitter. Immediately replays the current buffer so a
   * fresh subscriber sees prior context, then registers for live
   * updates. Cleanup on completion/timeout/error removes from the list.
   */
  public void subscribe(SseEmitter emitter) {
    List<ContainerEvent> replay;
    synchronized (buffer) {
      replay = new ArrayList<>(buffer);
    }
    for (ContainerEvent ce : replay) {
      try {
        emitter.send(SseEmitter.event().name("container-event").data(ce.toMap()));
      } catch (IOException io) {
        try { emitter.completeWithError(io); } catch (Exception ignore) {}
        return;
      } catch (Exception ex) {
        try { emitter.completeWithError(ex); } catch (Exception ignore) {}
        return;
      }
    }
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(t -> emitters.remove(emitter));
  }

  /**
   * Snapshot copy of the buffer for the poll fallback endpoint and tests.
   * Oldest first.
   */
  public List<ContainerEvent> recent() {
    synchronized (buffer) {
      return new ArrayList<>(buffer);
    }
  }

  int emitterCount() {
    return emitters.size();
  }

  /**
   * Trimmed record. Kept intentionally UI-shaped (millis, camelCase
   * fields via {@link #toMap()}) so callers don't need to translate.
   */
  public record ContainerEvent(long tsMs, String container, String action, String image) {
    public Map<String, Object> toMap() {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("ts", tsMs);
      m.put("container", container);
      m.put("action", action);
      if (image != null) m.put("image", image);
      return m;
    }
  }
}
