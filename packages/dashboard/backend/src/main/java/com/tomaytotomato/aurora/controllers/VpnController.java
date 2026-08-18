package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.VpnConfig;
import com.tomaytotomato.aurora.domain.VpnPeer;
import com.tomaytotomato.aurora.domain.VpnPeerSecret;
import com.tomaytotomato.aurora.domain.VpnStatus;
import com.tomaytotomato.aurora.services.VpnService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /api/vpn} — Aurora's own inbound WireGuard (+ optional OpenVPN)
 * server. NOT {@code packages/privacy}'s Gluetun sidecar; see {@code
 * docs/SPLIT_TUNNEL.md} and {@code packages/dashboard/docs/VPN_PAGE_DESIGN.md}.
 *
 * <p>Every method here returns a {@link VpnConfig}, {@link VpnPeer}, or
 * {@link VpnStatus} — none of which have a field for a private key — or
 * throws before touching one. The one deliberate exception is {@link
 * #addPeer}, whose {@link VpnPeerSecret} response is the spec's one-time
 * reveal.
 */
@RestController
@RequestMapping("/api/vpn")
public class VpnController {

  private static final Logger log = LoggerFactory.getLogger(VpnController.class);
  private static final long HEARTBEAT_MS = 15_000L;
  private static final long TICK_MS = 3_000L;

  private final VpnService vpn;
  private final ScheduledExecutorService scheduler;

  public VpnController(VpnService vpn) {
    this.vpn = vpn;
    ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(2, r -> {
      Thread t = new Thread(r, "vpn-status-sse");
      t.setDaemon(true);
      return t;
    });
    exec.setRemoveOnCancelPolicy(true);
    this.scheduler = exec;
  }

  // ------------------------------------------------------------------
  // Status
  // ------------------------------------------------------------------

  @GetMapping("/status")
  public VpnStatus status() {
    return vpn.status();
  }

  /** SSE, named event {@code vpn-status}, same pattern as StatusController. */
  @GetMapping(value = "/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter statusStream() {
    SseEmitter emitter = new SseEmitter(0L);
    AtomicBoolean alive = new AtomicBoolean(true);

    if (!sendStatus(emitter, alive)) {
      return emitter;
    }

    var tick = scheduler.scheduleWithFixedDelay(
        () -> sendStatus(emitter, alive), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    var heartbeat = scheduler.scheduleWithFixedDelay(
        () -> sendHeartbeat(emitter, alive), HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);

    Runnable cleanup = () -> {
      alive.set(false);
      tick.cancel(false);
      heartbeat.cancel(false);
    };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(err -> {
      log.debug("vpn/status/stream error: {}", err.toString());
      cleanup.run();
    });
    return emitter;
  }

  private boolean sendStatus(SseEmitter emitter, AtomicBoolean alive) {
    if (!alive.get()) return false;
    try {
      emitter.send(SseEmitter.event().name("vpn-status").data(vpn.status()));
      return true;
    } catch (IOException e) {
      alive.set(false);
      emitter.complete();
      return false;
    } catch (Exception e) {
      log.warn("vpn/status/stream unexpected: {}", e.toString());
      alive.set(false);
      emitter.completeWithError(e);
      return false;
    }
  }

  private void sendHeartbeat(SseEmitter emitter, AtomicBoolean alive) {
    if (!alive.get()) return;
    try {
      emitter.send(SseEmitter.event().comment("hb"));
    } catch (IOException e) {
      alive.set(false);
      emitter.complete();
    } catch (Exception e) {
      log.debug("vpn/status/stream heartbeat: {}", e.toString());
      alive.set(false);
      emitter.completeWithError(e);
    }
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  // ------------------------------------------------------------------
  // Config
  // ------------------------------------------------------------------

  @GetMapping("/config")
  public VpnConfig getConfig() {
    return vpn.getConfig().orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "not configured yet — call POST /vpn/config/init"));
  }

  @PutMapping("/config")
  public VpnConfig updateConfig(@RequestBody VpnConfig req) {
    return vpn.updateConfig(req);
  }

  /**
   * Undoes {@code POST /config/init}. Destructive by design — see
   * {@link com.tomaytotomato.aurora.services.VpnService#removeConfig()}
   * for why the peers go too.
   */
  @DeleteMapping("/config")
  public ResponseEntity<Void> removeConfig() {
    try {
      vpn.removeConfig();
      return ResponseEntity.noContent().build();
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @PostMapping("/config/init")
  public ResponseEntity<VpnConfig> initConfig() {
    try {
      return ResponseEntity.ok(vpn.initConfig());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @PostMapping("/server/rotate-key")
  public VpnConfig rotateKey() {
    try {
      return vpn.rotateKey();
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  // ------------------------------------------------------------------
  // Peers
  // ------------------------------------------------------------------

  @GetMapping("/peers")
  public List<VpnPeer> listPeers() {
    return vpn.listPeers();
  }

  @PostMapping("/peers")
  public ResponseEntity<VpnPeerSecret> addPeer(@RequestBody AddPeerReq req) {
    if (req.name() == null || req.name().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    String mode = req.allowedIpsMode();
    if (!"split".equals(mode) && !"full".equals(mode)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "allowedIpsMode must be 'split' or 'full', got: " + mode);
    }
    try {
      return ResponseEntity.status(HttpStatus.CREATED).body(vpn.addPeer(req.name(), mode));
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @DeleteMapping("/peers/{id}")
  public ResponseEntity<Void> deletePeer(@PathVariable String id) {
    try {
      vpn.deletePeer(id);
      return ResponseEntity.noContent().build();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @PostMapping("/peers/{id}/toggle")
  public VpnPeer togglePeer(@PathVariable String id) {
    try {
      return vpn.togglePeer(id);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  /**
   * The spec models this as an always-available download, "mirrors the
   * existing caddy-root.crt pattern". It cannot be, for one specific
   * peer field: the private key. Aurora does not store a peer's private
   * key past the one-time reveal in {@link #addPeer} (see {@code
   * VpnService} javadoc for why), so there is no way to rebuild a
   * working {@code .conf} after that point. 409 with a clear message —
   * not a 200 with a placeholder that looks like a working file and
   * silently isn't.
   */
  @GetMapping(value = "/peers/{id}/config", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> peerConfig(@PathVariable String id) {
    vpn.findPeer(id).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "no such peer"));
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "this peer's private key was shown once, at creation, and is not stored — remove and re-add the peer to get a new config");
  }

  /** Same constraint as {@link #peerConfig}: no stored private key, no QR to regenerate. */
  @GetMapping(value = "/peers/{id}/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> peerQrCode(@PathVariable String id) {
    vpn.findPeer(id).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "no such peer"));
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "this peer's private key was shown once, at creation, and is not stored — remove and re-add the peer to get a new QR code");
  }

  public record AddPeerReq(@NotBlank String name, String allowedIpsMode) {}
}
