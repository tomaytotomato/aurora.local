package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * Unit tests for {@link DockerService}.
 *
 * <p>Covers the A1 fix (2026-08-02): the compose-project label filter
 * accepts both the historical shared {@code aurora} project and any
 * per-package {@code aurora-*} project, so containers launched with
 * their compose top-level {@code name:} (e.g. {@code aurora-notes})
 * are no longer invisible to {@code listProjectContainers()} and the
 * System-card container count.
 */
class DockerServiceTests {

  private DockerClient docker;
  private ListContainersCmd cmd;

  @BeforeEach
  void setUp() {
    docker = Mockito.mock(DockerClient.class);
    cmd = Mockito.mock(ListContainersCmd.class);
    Mockito.when(docker.listContainersCmd()).thenReturn(cmd);
    Mockito.when(cmd.withShowAll(anyBoolean())).thenReturn(cmd);
  }

  private Container container(String name, Map<String, String> labels) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] {"/" + name});
    Mockito.when(c.getLabels()).thenReturn(labels);
    Mockito.when(c.getState()).thenReturn("running");
    Mockito.when(c.getStatus()).thenReturn("Up 3 hours");
    return c;
  }

  private Map<String, String> labels(String project) {
    Map<String, String> l = new HashMap<>();
    l.put("com.docker.compose.project", project);
    return l;
  }

  @Test
  void listProjectContainers_includesSharedAuroraProject() {
    Container silverbullet = container("silverbullet", labels("aurora"));
    Mockito.when(cmd.exec()).thenReturn(List.of(silverbullet));

    List<Container> out = new DockerService(docker).listProjectContainers();
    assertEquals(1, out.size());
    assertEquals("/silverbullet", out.get(0).getNames()[0]);
  }

  @Test
  void listProjectContainers_includesAuroraPrefixedProjects() {
    // aurora, aurora-notes, aurora-core, aurora-dashboard, aurora-media
    // are all aurora-managed. Historically only project=aurora was matched.
    Container silverbullet = container("silverbullet", labels("aurora"));
    Container auroraApp   = container("aurora",       labels("aurora-dashboard"));
    Container caddy       = container("caddy",        labels("aurora-core"));
    Container sonarr      = container("sonarr",       labels("aurora-media"));
    Mockito.when(cmd.exec()).thenReturn(List.of(silverbullet, auroraApp, caddy, sonarr));

    List<Container> out = new DockerService(docker).listProjectContainers();
    assertEquals(4, out.size());
  }

  @Test
  void listProjectContainers_excludesUnrelatedProjects() {
    // Some other stack the user runs on the same box must not be counted.
    Container silverbullet = container("silverbullet", labels("aurora"));
    Container nextcloud    = container("nextcloud",    labels("nextcloud"));
    Container random       = container("random",       labels("someone-elses-stack"));
    Container caddy        = container("caddy",        labels("aurora-core"));
    Mockito.when(cmd.exec()).thenReturn(List.of(silverbullet, nextcloud, random, caddy));

    List<Container> out = new DockerService(docker).listProjectContainers();
    assertEquals(2, out.size());
    for (Container c : out) {
      String project = c.getLabels().get("com.docker.compose.project");
      assertTrue(
          project.equals("aurora") || project.startsWith("aurora-"),
          "project label leaked into filter: " + project);
    }
  }

  @Test
  void listProjectContainers_toleratesContainersWithoutLabels() {
    // Bare `docker run` containers have null labels or no project label;
    // must be silently skipped, not NPE.
    Container bare = Mockito.mock(Container.class);
    Mockito.when(bare.getNames()).thenReturn(new String[] {"/bare"});
    Mockito.when(bare.getLabels()).thenReturn(null);

    Container labelled = container("silverbullet", labels("aurora"));

    Mockito.when(cmd.exec()).thenReturn(List.of(bare, labelled));

    List<Container> out = new DockerService(docker).listProjectContainers();
    assertEquals(1, out.size());
  }

  @Test
  void listProjectContainers_toleratesLabelsWithoutProject() {
    // Container has labels but no compose.project key (e.g. a plain
    // image with OCI labels only).
    Map<String, String> onlyOci = new HashMap<>();
    onlyOci.put("org.opencontainers.image.title", "not-a-compose-container");
    Container noProject = container("bare-run", onlyOci);
    Container aurora = container("silverbullet", labels("aurora"));

    Mockito.when(cmd.exec()).thenReturn(List.of(noProject, aurora));

    List<Container> out = new DockerService(docker).listProjectContainers();
    assertEquals(1, out.size());
    assertEquals("/silverbullet", out.get(0).getNames()[0]);
  }

  // ─── containersForPackage() — feeds the per-package Logs tab and the
  // per-package network endpoint ───────────────────────────────────────

  @Test
  void containersForPackage_matchesTheCurrentPerPackageProject() {
    Container sonarr = container("aurora-media-sonarr", labels("aurora-media"));
    Container radarr = container("aurora-media-radarr", labels("aurora-media"));
    Container silverbullet = container("silverbullet", labels("aurora-notes"));
    Mockito.when(cmd.exec()).thenReturn(List.of(sonarr, radarr, silverbullet));

    List<Container> out = new DockerService(docker).containersForPackage("media", "media");
    assertEquals(2, out.size());
  }

  @Test
  void containersForPackage_fallsBackToContainerNameUnderTheLegacySharedProject() {
    Container silverbullet = container("silverbullet", labels("aurora"));
    Container caddy = container("caddy", labels("aurora"));
    Mockito.when(cmd.exec()).thenReturn(List.of(silverbullet, caddy));

    List<Container> out = new DockerService(docker).containersForPackage("notes", "silverbullet");

    assertEquals(1, out.size());
    assertEquals("/silverbullet", out.get(0).getNames()[0]);
  }

  @Test
  void containersForPackage_legacyFallbackDoesNotMatchAnotherContainerUnderTheSameLegacyProject() {
    Container silverbullet = container("silverbullet", labels("aurora"));
    Container caddy = container("caddy", labels("aurora"));
    Mockito.when(cmd.exec()).thenReturn(List.of(silverbullet, caddy));

    List<Container> out = new DockerService(docker).containersForPackage("notes", "silverbullet");

    assertEquals(1, out.size());
  }

  @Test
  void containersForPackage_coreMatchesBothTheLegacyAndCurrentProject() {
    Container legacyCaddy = container("caddy", labels("aurora"));
    List<Container> viaLegacy = new DockerService(mockClientReturning(legacyCaddy))
        .containersForPackage("core", "core");
    assertEquals(1, viaLegacy.size());

    Container currentCaddy = container("caddy", labels("aurora-core"));
    List<Container> viaCurrent = new DockerService(mockClientReturning(currentCaddy))
        .containersForPackage("core", "core");
    assertEquals(1, viaCurrent.size());
  }

  @Test
  void containersForPackage_returnsEmptyWhenNothingMatches() {
    Container sonarr = container("aurora-media-sonarr", labels("aurora-media"));
    Mockito.when(cmd.exec()).thenReturn(List.of(sonarr));

    List<Container> out = new DockerService(docker).containersForPackage("photos", "photos");
    assertEquals(0, out.size());
  }

  /** A fresh mocked {@link DockerClient} pre-wired to return one container. */
  private DockerClient mockClientReturning(Container c) {
    DockerClient client = Mockito.mock(DockerClient.class);
    ListContainersCmd freshCmd = Mockito.mock(ListContainersCmd.class);
    Mockito.when(client.listContainersCmd()).thenReturn(freshCmd);
    Mockito.when(freshCmd.withShowAll(anyBoolean())).thenReturn(freshCmd);
    Mockito.when(freshCmd.exec()).thenReturn(List.of(c));
    return client;
  }

  // ─── listContainerSummaries() — feeds /api/proxy/targets ────────────────

  private Container containerWithPorts(String name, List<Integer> privatePorts, Map<String, String> labels) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] {"/" + name});
    Mockito.when(c.getLabels()).thenReturn(labels);
    ContainerPort[] ports = privatePorts.stream().map(p -> {
      ContainerPort cp = Mockito.mock(ContainerPort.class);
      Mockito.when(cp.getPrivatePort()).thenReturn(p);
      return cp;
    }).toArray(ContainerPort[]::new);
    Mockito.when(c.getPorts()).thenReturn(ports);
    return c;
  }

  @Test
  void listContainerSummaries_reportsPortsAndOwningPackage() {
    Map<String, String> labels = labels("aurora-notes");
    labels.put("com.docker.compose.project.config_files", "/repo/packages/notes/compose.yml");
    Container silverbullet = containerWithPorts("silverbullet", List.of(3000), labels);
    Mockito.when(cmd.exec()).thenReturn(List.of(silverbullet));

    List<DockerService.ContainerSummary> out = new DockerService(docker).listContainerSummaries();

    assertThat(out).hasSize(1);
    assertThat(out.get(0).name()).isEqualTo("silverbullet");
    assertThat(out.get(0).ports()).containsExactly(3000);
    assertThat(out.get(0).pkg()).isEqualTo("notes");
  }

  @Test
  void listContainerSummaries_reportsNullPackageWhenLabelIsMissing() {
    Container bareRun = containerWithPorts("calibre-web", List.of(8083), labels("aurora"));
    Mockito.when(cmd.exec()).thenReturn(List.of(bareRun));

    List<DockerService.ContainerSummary> out = new DockerService(docker).listContainerSummaries();

    assertThat(out).hasSize(1);
    assertThat(out.get(0).pkg()).isNull();
  }

  @Test
  void listContainerSummaries_dedupesAndSortsPorts() {
    Map<String, String> labels = labels("aurora-jellyfin");
    labels.put("com.docker.compose.project.config_files", "/repo/packages/jellyfin/compose.yml");
    Container jellyfin = containerWithPorts("jellyfin", List.of(8920, 8096, 8096), labels);
    Mockito.when(cmd.exec()).thenReturn(List.of(jellyfin));

    List<DockerService.ContainerSummary> out = new DockerService(docker).listContainerSummaries();

    assertThat(out.get(0).ports()).containsExactly(8096, 8920);
  }
}
