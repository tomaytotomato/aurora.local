package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.RepoState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iter-dash-1 tests for {@link SystemService#info()} and
 * {@link SystemService#stateSnapshot()}.
 *
 * <p>Bug 1 (be1523c08f0f.undefined) is closed by sourcing hostname + domain
 * from {@code .state.yml} via {@link StateFileService}. These tests pin
 * that contract so future regressions surface as a red unit test rather
 * than a red dashboard header.
 */
class SystemServiceInfoTests {

  private static AuroraProperties props() {
    return new AuroraProperties(
        "/repo",
        "/host/proc",
        List.of(),
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
  }

  private static DockerService dockerMock() {
    DockerService docker = Mockito.mock(DockerService.class);
    Mockito.when(docker.version()).thenReturn(java.util.Optional.of("29.6.2"));
    Mockito.when(docker.listProjectContainers()).thenReturn(List.of());
    return docker;
  }

  @Test
  void info_readsHostnameAndDomainFromState() {
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", null, List.of("core"), List.of()));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Map<String, Object> info = svc.info();

    assertEquals("aurora", info.get("hostname"), "hostname must come from .state.yml, never os.hostname()");
    assertEquals("aurora.local", info.get("domain"), "domain must come from .state.yml");
    // No InetAddress leakage — a container short-id (12 hex) would fail this.
    String h = String.valueOf(info.get("hostname"));
    assertTrue(!h.matches("^[0-9a-f]{12}$"),
        "hostname must not be a docker container short-id, got: " + h);
  }

  @Test
  void info_returnsNullForMissingStateRatherThanEmptyString() {
    // Contract: frontend renders "—" for missing values. It relies on the
    // backend emitting null, not "" or "undefined".
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(null, null, null, null, List.of(), List.of()));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Map<String, Object> info = svc.info();

    assertNull(info.get("hostname"), "missing hostname must be null, not undefined/empty");
    assertNull(info.get("domain"), "missing domain must be null, not undefined/empty");
    // Structural keys the frontend depends on must exist even when values null.
    assertTrue(info.containsKey("uptimeSeconds"));
    assertTrue(info.containsKey("memTotalBytes"));
    assertTrue(info.containsKey("memUsedBytes"));
    assertTrue(info.containsKey("diskTotalBytes"));
    assertTrue(info.containsKey("diskUsedBytes"));
    assertTrue(info.containsKey("cpuCount"));
    assertTrue(info.containsKey("containerCount"));
    assertTrue(info.containsKey("dockerVersion"));
    assertTrue(info.containsKey("capabilities"));
  }

  @Test
  void info_capabilitiesFlagsMetricsFalseInIter1() {
    // UX_SPEC_DASHBOARD.md §4.5 + §6 non-goal: no metrics backend in iter-1.
    // Frontend must gate the metrics fetch on this flag so /dashboard/home
    // never issues a 404 request. Guard the flag here so the toggle is one
    // line of Java when the real backend lands.
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", null, List.of("core"), List.of()));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Map<String, Object> info = svc.info();

    @SuppressWarnings("unchecked")
    Map<String, Object> caps = (Map<String, Object>) info.get("capabilities");
    assertNotNull(caps, "capabilities block must exist");
    assertEquals(false, caps.get("metrics"),
        "capabilities.metrics must be false until a real timeseries backend ships");
  }

  @Test
  void stateSnapshot_shapesForDashboardHome() {
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", "2026-08-01T22:10:20Z",
            List.of("core", "media", "storage", "privacy", "notes"),
            List.of("cpu")));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Map<String, Object> state = svc.stateSnapshot();

    assertEquals(1, state.get("bootstrapVersion"));
    assertEquals("aurora", state.get("hostname"));
    assertEquals("aurora.local", state.get("domain"));
    assertEquals("2026-08-01T22:10:20Z", state.get("installedAt"));
    @SuppressWarnings("unchecked")
    List<String> enabled = (List<String>) state.get("enabled");
    assertEquals(5, enabled.size());
    assertTrue(enabled.contains("core"));
  }

  @Test
  void stateSnapshot_neverReturnsNullForListFields() {
    // Prevents a null-list from turning into `null` in JSON, which the
    // frontend would render as "0 enabled" and fail the count semantics.
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(null, null, null, null, null, null));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Map<String, Object> state = svc.stateSnapshot();

    assertNotNull(state.get("enabled"), "enabled must never be null in the DTO");
    assertNotNull(state.get("profiles"), "profiles must never be null in the DTO");
  }

  // ---------------------------------------------------------------------
  // iter-3 TD2 — env() must not leak the container hostname.
  // ---------------------------------------------------------------------

  @Test
  void env_readsHostnameAndDomainFromStateFile() {
    // Same YAML parser as info() — no more grep-based duplicate.
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", null, List.of("core"), List.of()));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Map<String, Object> env = svc.env();

    assertEquals("aurora", env.get("hostname"));
    assertEquals("aurora.local", env.get("domain"));
  }

  @Test
  void env_returnsNullHostnameRatherThanContainerId() {
    // Contract: pre-onboarding welcome shows "unset" for hostname when
    // .state.yml has no value — never the container short-id like
    // `be1523c08f0f`. Regression fix for D4 / iter-3 TD2. The old
    // env() fell back to InetAddress.getLocalHost().getHostName() which
    // returned the container ID inside docker.
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(null, null, null, null, List.of(), List.of()));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Map<String, Object> env = svc.env();

    assertNull(env.get("hostname"),
        "missing state.hostname must be null so the wizard renders 'unset', "
            + "not a docker container short-id");
    assertNull(env.get("domain"), "missing state.domain must be null");
    // Resource facts still populate so the welcome screen renders.
    assertNotNull(env.get("cpu"));
    assertNotNull(env.get("memory"));
    assertNotNull(env.get("disks"));
  }

  @Test
  void env_neverReturnsAContainerShortIdShapedHostname() {
    // Belt-and-braces: even if a downstream mistake reintroduces the
    // hostname() fallback, this test rejects any 12-hex-char value.
    StateFileService stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", null, List.of(), List.of()));

    SystemService svc = new SystemService(props(), dockerMock(), stateFiles);
    Object h = svc.env().get("hostname");
    if (h != null) {
      assertTrue(!String.valueOf(h).matches("^[0-9a-f]{12}$"),
          "env() hostname must never be a docker container short-id, got: " + h);
    }
  }
}
