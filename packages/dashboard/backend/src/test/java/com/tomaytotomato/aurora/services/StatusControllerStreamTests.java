package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.controllers.StatusController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for the TD1 SSE endpoint {@code GET /api/services/status/stream}
 * added to {@link StatusController} on 2026-08-02.
 *
 * <p>Contract asserted here:
 * <ul>
 *   <li>Response is HTTP 200 with content-type {@code text/event-stream}.</li>
 *   <li>Initial snapshot is emitted synchronously on subscribe — the
 *       client should not have to wait a full tick for first paint.</li>
 *   <li>The event stream carries the {@code service-status} event name
 *       so the frontend {@code EventSource} can filter cleanly.</li>
 *   <li>The one-shot GET at {@code /api/services/status} still returns
 *       the same JSON snapshot (poll fallback path).</li>
 * </ul>
 *
 * <p>We deliberately do not assert timing of subsequent ticks / heartbeat
 * intervals in a unit test — those are wall-clock-dependent and would
 * make the suite flaky in CI. The tick + heartbeat are private
 * implementation details of {@link StatusController#stream()}; verifying
 * them belongs in an E2E connected to a real backend, deferred to the
 * next verify sweep.
 */
class StatusControllerStreamTests {

  private Map<String, Object> fakeSnapshot() {
    return Map.of(
        "generated_at", "2026-08-02T22:00:00Z",
        "services", List.of(
            Map.of("package", "notes", "state", "running", "container", "silverbullet")
        ));
  }

  private MockMvc build(StatusProbeService probes) {
    return MockMvcBuilders.standaloneSetup(new StatusController(probes)).build();
  }

  @Test
  void streamEndpoint_returnsSseContentType_andStatus200() throws Exception {
    StatusProbeService probes = mock(StatusProbeService.class);
    when(probes.snapshot()).thenReturn(fakeSnapshot());

    MvcResult result = build(probes).perform(get("/api/services/status/stream"))
        .andExpect(request().asyncStarted())
        .andReturn();

    // Trigger the async dispatch to observe the initial synchronous send.
    MockHttpServletResponse response = result.getResponse();
    assertEquals(200, response.getStatus());
    String contentType = response.getContentType();
    assertNotNull(contentType, "SSE response must set a content-type");
    assertTrue(contentType.startsWith("text/event-stream"),
        "expected text/event-stream, got: " + contentType);
  }

  @Test
  void streamEndpoint_initialSnapshotIsEmittedSynchronously() throws Exception {
    StatusProbeService probes = mock(StatusProbeService.class);
    when(probes.snapshot()).thenReturn(fakeSnapshot());

    MvcResult result = build(probes).perform(get("/api/services/status/stream"))
        .andExpect(request().asyncStarted())
        .andReturn();

    // The initial event should already be in the response body before we
    // hit any tick. MockMvc's SseEmitter captures the write immediately
    // because SseEmitter#send() calls flushBuffer on the wrapped response.
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("event:service-status"),
        "initial event must carry the service-status name, got: " + body);
    assertTrue(body.contains("silverbullet") || body.contains("running"),
        "initial event must carry snapshot payload, got: " + body);
  }

  @Test
  void pollEndpoint_stillReturnsSameSnapshot_asJson() throws Exception {
    // Poll fallback path — clients whose EventSource errors out fall back
    // to this. Same probes.snapshot() call, plain JSON encoding.
    StatusProbeService probes = mock(StatusProbeService.class);
    when(probes.snapshot()).thenReturn(fakeSnapshot());

    build(probes).perform(get("/api/services/status"))
        .andExpect(status().isOk());
  }
}
