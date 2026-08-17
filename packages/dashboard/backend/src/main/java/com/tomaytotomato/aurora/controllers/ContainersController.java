package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.services.DockerEventService;
import com.tomaytotomato.aurora.services.DockerEventService.ContainerEvent;
import com.tomaytotomato.aurora.services.DockerService;
import com.tomaytotomato.aurora.services.DockerService.LogLine;
import com.tomaytotomato.aurora.services.DockerService.LogTail;
import com.tomaytotomato.aurora.services.PackagesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/containers")
public class ContainersController {

  private final DockerService docker;
  private final DockerEventService events;
  private final PackagesService packages;

  public ContainersController(DockerService docker, DockerEventService events, PackagesService packages) {
    this.docker = docker;
    this.events = events;
    this.packages = packages;
  }

  private static final Pattern PACKAGE_NAME_SHAPE =
      Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

  @GetMapping
  public List<Map<String, Object>> list(
      @RequestParam(name = "package", required = false) String pkg
  ) {
    if (pkg != null && !PACKAGE_NAME_SHAPE.matcher(pkg).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "package name is malformed");
    }
    // B3-followup (iter-16): filter to this package's containers via
    // DockerService.containersForPackage — same matching /services/status
    // already relies on, so the two never disagree.
    Iterable<Container> matched = pkg == null
        ? docker.listProjectContainers()
        : docker.containersForPackage(pkg, expectedContainerFor(pkg));

    List<Map<String, Object>> out = new ArrayList<>();
    for (Container c : matched) {
      out.add(Map.of(
          "id", c.getId(),
          "names", c.getNames() == null ? new String[0] : c.getNames(),
          "image", c.getImage(),
          "state", c.getState(),
          "status", c.getStatus(),
          "service", DockerService.composeService(c) == null ? "" : DockerService.composeService(c),
          "labels", c.getLabels() == null ? Map.of() : c.getLabels()
      ));
    }
    return out;
  }

  /**
   * The container name a package's manifest declares it probes (see
   * {@code probe.container} in manifest.yml, same field
   * {@link com.tomaytotomato.aurora.services.StatusProbeService} reads),
   * falling back to the package name itself when the manifest has no
   * {@code probe:} block — identical default to
   * {@code StatusProbeService.probe()}.
   */
  private String expectedContainerFor(String pkg) {
    Map<String, Object> probe = packages.readProbe(pkg);
    Object container = probe == null ? null : probe.get("container");
    return container instanceof String s && !s.isBlank() ? s : pkg;
  }

  /**
   * B1 poll fallback for the "Recent changes" card. Returns the current
   * ring-buffer snapshot (oldest first). Clients that can't hold an
   * {@code EventSource} open (or whose stream errored) fall back to this
   * every ~5s. Mirrors the SSE surface's payload shape.
   */
  @GetMapping("/events")
  public List<Map<String, Object>> recentEvents() {
    List<ContainerEvent> snap = events.recent();
    List<Map<String, Object>> out = new ArrayList<>(snap.size());
    for (ContainerEvent ce : snap) out.add(ce.toMap());
    return out;
  }

  /**
   * B1 (v0.3): SSE stream of container-lifecycle events sourced from
   * {@code dockerClient.eventsCmd()}. On subscribe, replays the ring
   * buffer so a fresh page-load sees the last N transitions immediately,
   * then streams live events named {@code container-event}. Delegates
   * emitter lifecycle to {@link DockerEventService#subscribe}.
   */
  @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L); // never time out
    events.subscribe(emitter);
    return emitter;
  }

  // ------------------------------------------------------------------
  // B3 (v0.3): container logs tail. Snapshot only; no live follow.
  // ------------------------------------------------------------------

  /**
   * B3 accepts container id (12 or 64 hex chars) OR a container name in
   * the same shape docker enforces: letters, digits, {@code -} and
   * {@code _}, with an alnum first char. Anything else is 400. Docker
   * itself would reject bad shapes with 404 but pre-validation keeps
   * the error clean and avoids sending user-controlled strings into the
   * docker daemon on the noise path.
   */
  private static final Pattern LOGS_ID_SHAPE =
      Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$");

  /** Cap {@code tail} to something sane. 2000 lines covers debug flows;
   *  beyond that the request should live in ssh territory. */
  private static final int LOGS_TAIL_MIN = 1;
  private static final int LOGS_TAIL_MAX = 2000;

  /** Max wall-clock wait for the docker log tail to complete. */
  private static final Duration LOGS_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Snapshot tail of a container's log stream. Auth-only per
   * {@code SecurityConfig.anyRequest().authenticated()}.
   *
   * <p>Response shape:
   * <pre>
   *   {
   *     "container_id": "aurora-media-sonarr",
   *     "tail": 200,
   *     "truncated": false,
   *     "lines": [ {"ts": "...", "stream": "stdout", "line": "..."}, ... ]
   *   }
   * </pre>
   * {@code truncated} flips true only when the docker payload hit
   * {@link DockerService#LOG_BYTES_CAP} — the {@code tail} count itself is
   * always honoured because the service caps the array size after
   * collection.
   *
   * <ul>
   *   <li>400 — id shape / tail range invalid.</li>
   *   <li>404 — no such container on this box.</li>
   *   <li>200 — tail snapshot, possibly empty for a fresh container.</li>
   * </ul>
   */
  @GetMapping("/{id}/logs")
  public Map<String, Object> logs(
      @PathVariable("id") String id,
      @RequestParam(name = "tail", defaultValue = "200") int tail
  ) {
    if (id == null || !LOGS_ID_SHAPE.matcher(id).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "container id/name is malformed");
    }
    if (tail < LOGS_TAIL_MIN || tail > LOGS_TAIL_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "tail must be in [" + LOGS_TAIL_MIN + ", " + LOGS_TAIL_MAX + "]");
    }

    // Pre-check existence so 'no such container' surfaces as 404 rather
    // than an empty 200. inspectContainer is O(1) on the daemon.
    if (docker.inspectContainer(id).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "no such container on this box");
    }

    LogTail snap = docker.tailLogs(id, tail, LOGS_TIMEOUT);

    List<Map<String, Object>> lines = new ArrayList<>(snap.lines().size());
    for (LogLine l : snap.lines()) {
      Map<String, Object> row = new LinkedHashMap<>();
      if (l.ts() != null) row.put("ts", l.ts());
      row.put("stream", l.stream());
      row.put("line", l.line());
      lines.add(row);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("container_id", id);
    body.put("tail", tail);
    body.put("truncated", snap.truncated());
    body.put("lines", lines);
    return body;
  }
}
