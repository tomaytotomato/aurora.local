package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.services.DockerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerSocketExposureRuleTests {

  private static Container container(String name, List<ContainerMount> mounts) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    Mockito.when(c.getMounts()).thenReturn(mounts);
    return c;
  }

  private static ContainerMount mount(String source, String dest) {
    ContainerMount m = Mockito.mock(ContainerMount.class);
    Mockito.when(m.getSource()).thenReturn(source);
    Mockito.when(m.getDestination()).thenReturn(dest);
    return m;
  }

  private static DockerService dockerWith(List<Container> containers) {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenReturn(containers);
    return d;
  }

  @Test
  void no_containers_yields_no_findings() {
    assertEquals(List.of(),
        new DockerSocketExposureRule(dockerWith(List.of())).evaluate());
  }

  @Test
  void aurora_container_with_socket_is_ignored() {
    Container aurora = container("aurora",
        List.of(mount("/var/run/docker.sock", "/var/run/docker.sock")));
    assertEquals(List.of(),
        new DockerSocketExposureRule(dockerWith(List.of(aurora))).evaluate());
  }

  @Test
  void aurora_dashboard_variant_is_ignored() {
    Container dash = container("aurora-dashboard",
        List.of(mount("/var/run/docker.sock", "/var/run/docker.sock")));
    assertEquals(List.of(),
        new DockerSocketExposureRule(dockerWith(List.of(dash))).evaluate());
  }

  @Test
  void non_aurora_container_with_socket_flagged_MEDIUM() {
    Container homepage = container("aurora-homepage",
        List.of(mount("/var/run/docker.sock", "/var/run/docker.sock")));
    var got = new DockerSocketExposureRule(dockerWith(List.of(homepage))).evaluate();
    // Compose-project prefix 'aurora-' does NOT auto-exempt —
    // exact-match owner set, so aurora-homepage is fair game.
    assertEquals(1, got.size());
    assertEquals(SecurityFinding.MEDIUM, got.get(0).severity());
    assertTrue(got.get(0).title().contains("aurora-homepage"));
    assertEquals("docker_socket_exposure:aurora-homepage", got.get(0).id());
  }

  @Test
  void container_without_socket_mount_yields_no_finding() {
    Container media = container("aurora-media-sonarr",
        List.of(mount("/data/media", "/media"),
                mount("/etc/localtime", "/etc/localtime")));
    assertEquals(List.of(),
        new DockerSocketExposureRule(dockerWith(List.of(media))).evaluate());
  }

  @Test
  void copy_avoids_shell_substrings() {
    Container bad = container("aurora-portainer",
        List.of(mount("/var/run/docker.sock", "/var/run/docker.sock")));
    var got = new DockerSocketExposureRule(dockerWith(List.of(bad))).evaluate();
    String all = (got.get(0).title() + " " + got.get(0).description()).toLowerCase();
    // The description does mention /var/run/docker.sock and 'docker'
    // — those are legitimate technical terms in this rule. Just make
    // sure we're not telling users to run sudo or ./scripts/foo.
    assertFalse(all.contains("sudo "));
    assertFalse(all.contains("./scripts/"));
    assertFalse(all.contains("bash "));
  }

  @Test
  void rule_swallows_docker_exceptions() {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenThrow(new RuntimeException("socket down"));
    assertEquals(List.of(), new DockerSocketExposureRule(d).evaluate());
  }

  @Test
  void one_finding_per_container_even_with_multiple_socket_mounts() {
    // Pathological compose: two socket bind-mounts on the same container.
    // Should still surface as one finding.
    Container bad = container("aurora-explorer", List.of(
        mount("/var/run/docker.sock", "/var/run/docker.sock"),
        mount("/var/run/docker.sock", "/host/docker.sock")));
    var got = new DockerSocketExposureRule(dockerWith(List.of(bad))).evaluate();
    assertEquals(1, got.size());
  }

  @Test
  void isAuroraOwner_covers_exact_names_only() {
    assertTrue(DockerSocketExposureRule.isAuroraOwner("aurora"));
    assertTrue(DockerSocketExposureRule.isAuroraOwner("aurora-dashboard"));
    // Compose-project prefix alone is NOT enough — exact match only,
    // so ordinary aurora-* stack containers stay in scope.
    assertFalse(DockerSocketExposureRule.isAuroraOwner("aurora-dashboard-preview"));
    assertFalse(DockerSocketExposureRule.isAuroraOwner("aurora-media-sonarr"));
    assertFalse(DockerSocketExposureRule.isAuroraOwner("aurora-portainer"));
    assertFalse(DockerSocketExposureRule.isAuroraOwner(null));
    assertFalse(DockerSocketExposureRule.isAuroraOwner(""));
  }
}
