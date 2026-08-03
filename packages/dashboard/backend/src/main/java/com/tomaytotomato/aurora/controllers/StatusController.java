package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.StatusProbeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Iter-2 service status surface. Returns per-package live probes for the
 * Done page checklist (and, in iter-3, the dashboard home).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/services/status} — one-shot JSON snapshot.
 *       Historical name, kept alive as the poll fallback for clients
 *       whose {@code EventSource} 501s or fails 3× in 30s.</li>
 *   <li>{@code GET /api/services/status/stream} — SSE stream. Emits an
 *       initial {@code service-status} event on subscribe, then re-emits
 *       every {@link #TICK} (currently 2s) so a checklist row's
 *       {@code not-started → running} transition surfaces within one tick
 *       instead of one 5s poll window. Heartbeat comment every
 *       {@link #HEARTBEAT} keeps intermediaries (Caddy, corp proxies)
 *       from garbage-collecting an idle stream. (TD1, 2026-08-02.)</li>
 * </ul>
 *
 * <p>Backing service caches per-package results for 3s so a 2s tick
 * doesn't amplify the docker fan-out — one client observes ≤ ~1.5 real
 * probes per 3s window, N clients still observe the same because the
 * cache is process-scoped.
 */
@RestController
@RequestMapping("/api/services")
public class StatusController {

  private static final Logger log = LoggerFactory.getLogger(StatusController.class);
  static final Duration TICK = Duration.ofSeconds(2);
  static final Duration HEARTBEAT = Duration.ofSeconds(15);

  private final StatusProbeService probes;
  private final ScheduledExecutorService scheduler;

  public StatusController(StatusProbeService probes) {
    this.probes = probes;
    // Two threads: one for the tick loop across all emitters, one for
    // heartbeat pings. A homelab box will rarely have more than a
    // handful of tabs open on the dashboard.
    ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(2, r -> {
      Thread t = new Thread(r, "status-sse");
      t.setDaemon(true);
      return t;
    });
    exec.setRemoveOnCancelPolicy(true);
    this.scheduler = exec;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return probes.snapshot();
  }

  /**
   * Long-lived SSE stream of the same snapshot the poll endpoint returns.
   * One initial event fires immediately on subscribe so the client can
   * render without waiting a full tick.
   */
  @GetMapping(value = "/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    // 0L = never time out on the emitter side. The scheduled tick is the
    // only way this stream closes short of a client disconnect.
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean alive = new AtomicBoolean(true);

    // Initial send: don't gate on the tick — the client needs first paint.
    if (!sendSnapshot(emitter, alive)) {
      return emitter; // send failed, emitter already errored out
    }

    var tickTask = scheduler.scheduleWithFixedDelay(
        () -> sendSnapshot(emitter, alive),
        TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);

    var heartbeatTask = scheduler.scheduleWithFixedDelay(
        () -> sendHeartbeat(emitter, alive),
        HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);

    Runnable cleanup = () -> {
      alive.set(false);
      tickTask.cancel(false);
      heartbeatTask.cancel(false);
    };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(err -> {
      log.debug("services/status/stream error: {}", err.toString());
      cleanup.run();
    });

    return emitter;
  }

  private boolean sendSnapshot(SseEmitter emitter, AtomicBoolean alive) {
    if (!alive.get()) return false;
    try {
      emitter.send(SseEmitter.event().name("service-status").data(probes.snapshot()));
      return true;
    } catch (IOException e) {
      // Client disconnected — that's the normal path, not worth logging noise.
      alive.set(false);
      emitter.complete();
      return false;
    } catch (Exception e) {
      log.warn("services/status/stream unexpected: {}", e.toString());
      alive.set(false);
      emitter.completeWithError(e);
      return false;
    }
  }

  private void sendHeartbeat(SseEmitter emitter, AtomicBoolean alive) {
    if (!alive.get()) return;
    try {
      // Bare comment lines are ignored by browsers but keep the stream
      // "active" for any intermediary that would otherwise 60s-idle it.
      emitter.send(SseEmitter.event().comment("hb"));
    } catch (IOException e) {
      alive.set(false);
      emitter.complete();
    } catch (Exception e) {
      log.debug("services/status/stream heartbeat: {}", e.toString());
      alive.set(false);
      emitter.completeWithError(e);
    }
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }
}
