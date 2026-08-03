package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.services.DockerEventService;
import com.tomaytotomato.aurora.services.DockerEventService.ContainerEvent;
import com.tomaytotomato.aurora.services.DockerService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/containers")
public class ContainersController {

  private final DockerService docker;
  private final DockerEventService events;

  public ContainersController(DockerService docker, DockerEventService events) {
    this.docker = docker;
    this.events = events;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Container c : docker.listProjectContainers()) {
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
}
