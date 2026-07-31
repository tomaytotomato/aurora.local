package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.Closeable;
import java.io.IOException;

/**
 * SSE bridge that streams docker events to the SPA. Only auth'd sessions can
 * subscribe (SecurityConfig).
 *
 * <p>v0.1 forwards raw docker events verbatim. Later versions will multiplex
 * app-generated events (job progress, backup runs, …) onto the same stream
 * under different {@code name:} fields.
 */
@RestController
@RequestMapping("/api/events")
public class EventsController {

  private static final Logger log = LoggerFactory.getLogger(EventsController.class);

  private final DockerService docker;

  public EventsController(DockerService docker) {
    this.docker = docker;
  }

  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L); // never time out
    Closeable subscription;
    try {
      subscription = docker.streamEvents(
          event -> {
            try {
              emitter.send(SseEmitter.event()
                  .name("docker")
                  .data(event));
            } catch (IOException e) {
              emitter.completeWithError(e);
            }
          },
          err -> {
            log.warn("docker event stream error: {}", err.getMessage());
            emitter.completeWithError(err);
          }
      );
    } catch (Exception e) {
      emitter.completeWithError(e);
      return emitter;
    }

    Runnable cleanup = () -> {
      try { subscription.close(); } catch (Exception ignore) { /* best-effort */ }
    };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(t -> cleanup.run());
    return emitter;
  }
}
