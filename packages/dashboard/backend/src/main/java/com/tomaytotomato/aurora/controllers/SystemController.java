package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.DockerService;
import com.tomaytotomato.aurora.services.SystemService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/system")
public class SystemController {

  // Caddy 2 stores its local CA under this path inside the container.
  // The file is root:root 0600 in the caddy_data volume, so we have to read
  // it out via docker exec — a plain repo bind-mount can't see it.
  private static final String CADDY_ROOT_CA_PATH =
      "/data/caddy/pki/authorities/local/root.crt";
  private static final String CADDY_CONTAINER = "caddy";

  private final SystemService system;
  private final DockerService docker;

  public SystemController(SystemService system, DockerService docker) {
    this.system = system;
    this.docker = docker;
  }

  @GetMapping
  public Map<String, Object> get() {
    // Iter-dash-1: return the structured info shape the dashboard-home
    // header + System card consume. Old callers wanting the raw
    // hostname/uptime_ms/memory/disks map can hit /api/system/snapshot
    // (kept for backwards compat with the wizard's welcome screen).
    return system.info();
  }

  /**
   * Legacy raw snapshot — hostname, java_version, uptime_ms, docker_version,
   * cpu, memory, disks, gpu. Kept behind an explicit path so the new /api/system
   * response can be the structured DTO the dashboard expects.
   */
  @GetMapping("/snapshot")
  public Map<String, Object> snapshot() {
    return system.snapshot();
  }

  /**
   * Serve .state.yml as camelCase JSON so the dashboard-home can read the
   * enabled[] set + hostname/domain without hitting {@code /api/onboarding}
   * (which is public-during-bootstrap only and has richer semantics).
   */
  @GetMapping("/state")
  public Map<String, Object> state() {
    return system.stateSnapshot();
  }

  /**
   * Serve Caddy's local root CA so the operator can install it on their
   * client devices during the onboarding TLS step. Public by design — the
   * root certificate is not a secret, and this endpoint has to work before
   * the admin has logged in for the first time. Private key stays in the
   * caddy container and is never exposed.
   */
  @GetMapping("/caddy-root.crt")
  public ResponseEntity<ByteArrayResource> caddyRootCa() {
    Optional<byte[]> cert = docker.readFileFromContainer(CADDY_CONTAINER, CADDY_ROOT_CA_PATH);
    if (cert.isEmpty() || cert.get().length == 0) {
      return ResponseEntity.status(503).build();
    }
    ByteArrayResource body = new ByteArrayResource(cert.get());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/x-x509-ca-cert"))
        .contentLength(cert.get().length)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"caddy-root.crt\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(body);
  }
}
