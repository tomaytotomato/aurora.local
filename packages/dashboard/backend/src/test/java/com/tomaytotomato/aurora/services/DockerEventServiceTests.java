package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import com.github.dockerjava.api.model.EventType;
import com.tomaytotomato.aurora.services.DockerEventService.ContainerEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * B1 (v0.3): filter, ring-buffer, and replay contract for
 * {@link DockerEventService}. Bypasses the live docker subscription by
 * calling the package-private {@code recordEvent(Event)} hook directly.
 */
class DockerEventServiceTests {

  private static DockerEventService svc() {
    DockerService docker = Mockito.mock(DockerService.class);
    // Prevent the constructor / @PostConstruct start() path from actually
    // subscribing when tests instantiate the service.
    return new DockerEventService(docker);
  }

  private static Event event(EventType type, String action, String name, String image, Long timeSec) {
    Event e = new Event();
    e.withType(type);
    e.withAction(action);
    e.withFrom(image);
    e.withTime(timeSec);
    if (name != null) {
      EventActor actor = new EventActor();
      Map<String, String> attrs = new HashMap<>();
      attrs.put("name", name);
      actor.withAttributes(attrs);
      e.withEventActor(actor);
    }
    return e;
  }

  // -- filter -----------------------------------------------------------

  @Test
  void drops_non_container_events() {
    var s = svc();
    assertEquals(false, s.recordEvent(event(EventType.NETWORK, "connect", "aurora_net", null, 1L)));
    assertEquals(false, s.recordEvent(event(EventType.IMAGE, "pull", null, "postgres:16", 1L)));
    assertEquals(false, s.recordEvent(event(EventType.VOLUME, "create", "vol", null, 1L)));
    assertEquals(0, s.recent().size());
  }

  @Test
  void drops_non_lifecycle_container_events() {
    var s = svc();
    // exec_* dominate the event stream while LaunchService drives caddy /
    // status probes; if we didn't filter, the ring buffer would flush
    // real state changes off the front within seconds.
    assertEquals(false, s.recordEvent(event(EventType.CONTAINER, "exec_create", "aurora", null, 1L)));
    assertEquals(false, s.recordEvent(event(EventType.CONTAINER, "exec_start", "aurora", null, 1L)));
    assertEquals(false, s.recordEvent(event(EventType.CONTAINER, "attach", "aurora", null, 1L)));
    assertEquals(0, s.recent().size());
  }

  @Test
  void keeps_lifecycle_events() {
    var s = svc();
    assertTrue(s.recordEvent(event(EventType.CONTAINER, "create", "sonarr", "img", 1L)));
    assertTrue(s.recordEvent(event(EventType.CONTAINER, "start", "sonarr", "img", 2L)));
    assertTrue(s.recordEvent(event(EventType.CONTAINER, "die", "sonarr", "img", 3L)));
    assertTrue(s.recordEvent(event(EventType.CONTAINER, "destroy", "sonarr", "img", 4L)));
    assertEquals(4, s.recent().size());
  }

  @Test
  void health_status_events_are_normalised() {
    var s = svc();
    assertTrue(s.recordEvent(event(EventType.CONTAINER, "health_status: healthy", "sonarr", "img", 10L)));
    assertTrue(s.recordEvent(event(EventType.CONTAINER, "health_status: unhealthy", "gluetun", "img", 11L)));
    List<ContainerEvent> got = s.recent();
    assertEquals(2, got.size());
    assertEquals("health:healthy", got.get(0).action());
    assertEquals("health:unhealthy", got.get(1).action());
  }

  // -- ring buffer ------------------------------------------------------

  @Test
  void ring_buffer_evicts_oldest_beyond_cap() {
    var s = svc();
    for (int i = 0; i < DockerEventService.BUFFER_MAX + 25; i++) {
      s.recordEvent(event(EventType.CONTAINER, "start", "c" + i, null, (long) i));
    }
    List<ContainerEvent> got = s.recent();
    assertEquals(DockerEventService.BUFFER_MAX, got.size());
    // Oldest 25 dropped: buffer should now start at c25.
    assertEquals("c25", got.get(0).container());
    assertEquals("c" + (DockerEventService.BUFFER_MAX + 24),
        got.get(got.size() - 1).container());
  }

  // -- payload shape ----------------------------------------------------

  @Test
  void container_event_carries_millis_and_actor_name() {
    Event e = event(EventType.CONTAINER, "start", "sonarr", "linuxserver/sonarr:latest", 1_700_000_000L);
    ContainerEvent ce = DockerEventService.toContainerEvent(e);
    assertEquals(1_700_000_000_000L, ce.tsMs());
    assertEquals("sonarr", ce.container());
    assertEquals("start", ce.action());
    assertEquals("linuxserver/sonarr:latest", ce.image());
  }

  @Test
  void container_falls_back_to_id_when_actor_missing() {
    Event e = new Event();
    e.withType(EventType.CONTAINER);
    e.withAction("start");
    e.withTime(1L);
    e.withId("abc123");
    // No actor set — older daemons or malformed events.
    ContainerEvent ce = DockerEventService.toContainerEvent(e);
    assertEquals("abc123", ce.container());
  }

  @Test
  void unknown_container_when_no_actor_and_no_id() {
    Event e = new Event();
    e.withType(EventType.CONTAINER);
    e.withAction("start");
    e.withTime(1L);
    ContainerEvent ce = DockerEventService.toContainerEvent(e);
    assertEquals("unknown", ce.container());
  }

  @Test
  void toContainerEvent_returns_null_on_drop() {
    assertNull(DockerEventService.toContainerEvent(
        event(EventType.NETWORK, "connect", "aurora_net", null, 1L)));
    assertNull(DockerEventService.toContainerEvent(
        event(EventType.CONTAINER, "exec_create", "aurora", null, 1L)));
    // Null action → drop rather than NPE.
    Event bad = new Event();
    bad.withType(EventType.CONTAINER);
    assertNull(DockerEventService.toContainerEvent(bad));
    // Null event → drop.
    assertNull(DockerEventService.toContainerEvent(null));
  }

  @Test
  void container_event_toMap_shape() {
    ContainerEvent ce = new ContainerEvent(1_700_000_000_000L, "sonarr", "start", "img");
    Map<String, Object> m = ce.toMap();
    assertEquals(1_700_000_000_000L, m.get("ts"));
    assertEquals("sonarr", m.get("container"));
    assertEquals("start", m.get("action"));
    assertEquals("img", m.get("image"));

    // Null image is omitted (frontend can Object.hasOwn() check).
    ContainerEvent ce2 = new ContainerEvent(1L, "c", "die", null);
    assertEquals(false, ce2.toMap().containsKey("image"));
  }

  // -- fanout / subscription --------------------------------------------

  @Test
  void subscribe_replays_buffer_then_receives_live_events() throws Exception {
    var s = svc();
    s.recordEvent(event(EventType.CONTAINER, "start", "a", null, 1L));
    s.recordEvent(event(EventType.CONTAINER, "start", "b", null, 2L));

    SseEmitter emitter = Mockito.mock(SseEmitter.class);
    s.subscribe(emitter);

    // 2 replay sends on subscribe.
    Mockito.verify(emitter, Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
    assertEquals(1, s.emitterCount());

    // Live event fans out to the attached emitter.
    s.recordEvent(event(EventType.CONTAINER, "start", "c", null, 3L));
    Mockito.verify(emitter, Mockito.times(3)).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void emitter_send_failure_during_replay_does_not_register() throws Exception {
    var s = svc();
    s.recordEvent(event(EventType.CONTAINER, "start", "a", null, 1L));
    SseEmitter bad = Mockito.mock(SseEmitter.class);
    Mockito.doThrow(new java.io.IOException("client gone"))
        .when(bad).send(any(SseEmitter.SseEventBuilder.class));

    s.subscribe(bad);
    // Emitter must NOT have been added — it errored during replay.
    assertEquals(0, s.emitterCount());
  }
}
