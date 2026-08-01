package com.tomaytotomato.aurora.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.tomaytotomato.aurora.domain.RepoState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Unit tests for {@link StatusProbeService}.
 *
 * <p>Uses the JDK's {@link HttpServer} for real HTTP so timeouts and body
 * parsing are honest. No new dependencies added to the pom.
 */
class StatusProbeServiceTests {

  private HttpServer server;
  private int port;
  private final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();

  private PackagesService packages;
  private StateFileService stateFiles;
  private SystemService system;
  private DockerService docker;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String path = ex.getRequestURI().getPath();
      HttpHandler h = routes.get(path);
      if (h == null) {
        ex.sendResponseHeaders(404, -1);
        ex.close();
        return;
      }
      h.handle(ex);
    });
    server.start();
    port = server.getAddress().getPort();

    packages = Mockito.mock(PackagesService.class);
    stateFiles = Mockito.mock(StateFileService.class);
    system = Mockito.mock(SystemService.class);
    docker = Mockito.mock(DockerService.class);
    Mockito.when(system.lanIp()).thenReturn("192.168.0.110");
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", null, List.of("privacy"), List.of()));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    routes.clear();
  }

  private StatusProbeService svc() {
    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build();
    return new StatusProbeService(packages, stateFiles, system, docker, http);
  }

  private void route(String path, int code, String body) {
    routes.put(path, ex -> {
      byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
      if (b.length > 0) {
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
      }
      ex.close();
    });
  }

  private DockerService.ContainerInfo containerRunning(String name) {
    return new DockerService.ContainerInfo(name, "running", "Up 5 minutes");
  }

  private DockerService.ContainerInfo containerExited(String name) {
    return new DockerService.ContainerInfo(name, "exited", "Exited (137) 2 minutes ago");
  }

  private void adguardProbeCfg() {
    Mockito.when(packages.readProbe("privacy")).thenReturn(Map.of(
        "kind", "adguard",
        "container", "adguard",
        "in_cluster_url", "http://127.0.0.1:" + port,
        "external_url", "http://{lan_ip}:3000/"));
  }

  // --- AdGuard --------------------------------------------------------

  @Test
  void adguardFirstRun_returnsNeedsConfig() {
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    route("/control/install/get_addresses", 200, "{\"interfaces\":{}}");

    var r = svc().probe("privacy");
    assertEquals("needs-config", r.state);
    assertEquals("AdGuard admin password not set", r.reason);
    assertEquals("http://192.168.0.110:3000/", r.openUrl);
  }

  @Test
  void adguardConfigured_401OnStatus_returnsRunning() {
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    route("/control/install/get_addresses", 404, null);
    route("/control/status", 401, null);

    var r = svc().probe("privacy");
    assertEquals("running", r.state);
    assertNull(r.reason);
  }

  @Test
  void adguardConfigured_200RunningTrue_returnsRunning() {
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    route("/control/install/get_addresses", 404, null);
    route("/control/status", 200, "{\"protection_enabled\":true,\"running\":true}");

    assertEquals("running", svc().probe("privacy").state);
  }

  @Test
  void adguardFirstRunEndpointGone_fallsBackToStatusShape() {
    // Risk-1 case from the plan: get_addresses returns 404 (upstream renamed),
    // but /control/status returns 200 with running:false — still needs-config.
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    route("/control/install/get_addresses", 404, null);
    route("/control/status", 200, "{\"running\":false}");

    assertEquals("needs-config", svc().probe("privacy").state);
  }

  @Test
  void adguard500_returnsFailed() {
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    route("/control/install/get_addresses", 404, null);
    route("/control/status", 500, null);

    var r = svc().probe("privacy");
    assertEquals("failed", r.state);
    assertNotNull(r.reason);
  }

  @Test
  void adguardContainerMissing_returnsNotStarted() {
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.empty());

    assertEquals("not-started", svc().probe("privacy").state);
  }

  @Test
  void adguardContainerExited_returnsFailed() {
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerExited("adguard")));

    assertEquals("failed", svc().probe("privacy").state);
  }

  // --- Sonarr (http_json) --------------------------------------------

  @Test
  void sonarr401_treatedAsUp_returnsRunning() {
    Mockito.when(packages.readProbe("media")).thenReturn(Map.of(
        "kind", "http_json",
        "container", "sonarr",
        "in_cluster_url", "http://127.0.0.1:" + port + "/api/v3/system/status",
        "external_url", "http://sonarr.{domain}/",
        "auth_treats_401_as_up", true));
    Mockito.when(docker.findByName("sonarr")).thenReturn(Optional.of(containerRunning("sonarr")));
    route("/api/v3/system/status", 401, null);

    var r = svc().probe("media");
    assertEquals("running", r.state);
    assertEquals("http://sonarr.aurora.local/", r.openUrl);
  }

  @Test
  void sonarrConnectRefused_returnsFailed_becauseContainerUp() {
    Mockito.when(packages.readProbe("media")).thenReturn(Map.of(
        "kind", "http_json",
        "container", "sonarr",
        "in_cluster_url", "http://127.0.0.1:1/dead", // guaranteed refused
        "external_url", "http://sonarr.aurora.local/",
        "auth_treats_401_as_up", true));
    Mockito.when(docker.findByName("sonarr")).thenReturn(Optional.of(containerRunning("sonarr")));

    var r = svc().probe("media");
    assertEquals("failed", r.state);
  }

  @Test
  void sonarrContainerMissing_returnsNotStarted() {
    Mockito.when(packages.readProbe("media")).thenReturn(Map.of(
        "kind", "http_json",
        "container", "sonarr",
        "in_cluster_url", "http://127.0.0.1:1/dead",
        "external_url", "http://sonarr.aurora.local/"));
    Mockito.when(docker.findByName("sonarr")).thenReturn(Optional.empty());

    assertEquals("not-started", svc().probe("media").state);
  }

  // --- Timeout --------------------------------------------------------

  @Test
  void probeTimeout_returnsFailedWithinWallclock() {
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    // Deliberately-slow endpoint: sleeps ~3s before responding.
    routes.put("/control/install/get_addresses", ex -> {
      try { Thread.sleep(3_000); } catch (InterruptedException ignored) {}
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    // Also slow the fallback so we see the total is capped by the two probes.
    routes.put("/control/status", ex -> {
      try { Thread.sleep(3_000); } catch (InterruptedException ignored) {}
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });

    long start = System.nanoTime();
    var r = svc().probe("privacy");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertEquals("failed", r.state, "slow probe must classify failed, not hang");
    // Two probes × 2s ceiling each = 4.5s wall-clock ceiling.
    assertTrue(elapsedMs < 4_500,
        "probe wall-clock " + elapsedMs + "ms exceeded 4.5s ceiling");
  }

  // --- Cache TTL ------------------------------------------------------

  @Test
  void respectsTtl_secondCallHitsCache() {
    AtomicInteger hits = new AtomicInteger();
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    routes.put("/control/install/get_addresses", ex -> {
      hits.incrementAndGet();
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });

    StatusProbeService s = svc();
    s.probeCached("privacy");
    s.probeCached("privacy");
    assertEquals(1, hits.get(), "second call within TTL must not re-probe");
  }

  // --- Priority sort --------------------------------------------------

  @Test
  void snapshot_sortsBlockerFirst_thenAlphabetical() {
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", null,
            List.of("media", "privacy", "storage", "core"), List.of()));
    // core: self → running
    Mockito.when(packages.readProbe("core")).thenReturn(Map.of(
        "kind", "self", "container", "aurora", "external_url", "http://{domain}/"));
    // media: http_json, container missing → not-started
    Mockito.when(packages.readProbe("media")).thenReturn(Map.of(
        "kind", "http_json", "container", "sonarr",
        "in_cluster_url", "http://127.0.0.1:1/x",
        "external_url", "http://sonarr.{domain}/"));
    Mockito.when(docker.findByName("sonarr")).thenReturn(Optional.empty());
    // privacy: adguard first-run → needs-config
    adguardProbeCfg();
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    route("/control/install/get_addresses", 200, "{}");
    // storage: docker, running
    Mockito.when(packages.readProbe("storage")).thenReturn(Map.of(
        "kind", "docker", "container", "samba", "external_url", "smb://{lan_ip}/"));
    Mockito.when(docker.findByName("samba")).thenReturn(Optional.of(containerRunning("samba")));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> services = (List<Map<String, Object>>) svc().snapshot().get("services");
    assertEquals(4, services.size());
    // Expected order: needs-config (privacy) → not-started (media) → running:core,storage alphabetical
    assertEquals("privacy", services.get(0).get("package"));
    assertEquals("media", services.get(1).get("package"));
    assertEquals("core", services.get(2).get("package"));
    assertEquals("storage", services.get(3).get("package"));
  }

  // --- Copy safety ----------------------------------------------------

  @Test
  void reasonAndDetail_neverContainShellCopy() {
    // Every canned reason/detail must be safe copy — no sudo/docker/bash/./scripts/.
    String[] banned = new String[] {"sudo ", "docker ", "bash ", "./scripts/", "ssh "};
    // Force each state through a probe and assert.
    adguardProbeCfg();
    // failed
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerExited("adguard")));
    assertSafe(svc().probe("privacy"), banned);
    // not-started
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.empty());
    assertSafe(svc().probe("privacy"), banned);
    // needs-config
    Mockito.when(docker.findByName("adguard")).thenReturn(Optional.of(containerRunning("adguard")));
    route("/control/install/get_addresses", 200, "{}");
    assertSafe(svc().probe("privacy"), banned);
  }

  private void assertSafe(StatusProbeService.ProbeResult r, String[] banned) {
    String haystack = (r.reason == null ? "" : r.reason) + " | " + (r.detail == null ? "" : r.detail);
    for (String b : banned) {
      assertFalse(haystack.toLowerCase().contains(b),
          "reason/detail contained banned shell copy '" + b + "': " + haystack);
    }
  }
}
