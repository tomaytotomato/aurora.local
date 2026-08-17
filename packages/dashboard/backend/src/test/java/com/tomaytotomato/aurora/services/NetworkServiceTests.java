package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.PackageNetwork;
import com.tomaytotomato.aurora.domain.SsoBlock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NetworkService} — what {@code GET /packages/{name}/network}
 * reports today, versus what docs/SPLIT_TUNNEL.md still lists as planned.
 *
 * <p>The regression this whole endpoint exists to close: before it was
 * wired up, every call 404'd (no handler, not "no data for this package"),
 * and the frontend's generic copy read as "this app's networking is not
 * on this box any more" for a package that was plainly running.
 */
class NetworkServiceTests {

  private static Package pkg(String name, boolean enabled, boolean running, List<Map<String, Object>> ports) {
    return new Package(name, name, "fixture", "productivity", List.of("core"), List.of(),
        Map.of(), ports, Map.of(), List.of(), null, enabled, running, SsoBlock.DISABLED);
  }

  private static Container container(String name) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    return c;
  }

  @Test
  void unknown_package_is_empty() {
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.find("nope")).thenReturn(Optional.empty());
    NetworkService svc = new NetworkService(packages, Mockito.mock(ComposeScanner.class), Mockito.mock(DockerService.class));

    assertThat(svc.get("nope")).isEmpty();
  }

  @Test
  void a_package_with_no_gateway_reports_direct_mode() {
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.find("notes")).thenReturn(Optional.of(pkg("notes", true, true,
        List.of(Map.of("port", 3030, "proto", "tcp")))));
    Mockito.when(packages.readProbe("notes")).thenReturn(Map.of("container", "silverbullet"));

    ComposeScanner compose = Mockito.mock(ComposeScanner.class);
    Mockito.when(compose.gatewayFor("notes")).thenReturn(Optional.empty());

    DockerService docker = Mockito.mock(DockerService.class);
    Container silverbullet = container("silverbullet");
    Mockito.when(docker.containersForPackage("notes", "silverbullet"))
        .thenReturn(List.of(silverbullet));

    NetworkService svc = new NetworkService(packages, compose, docker);
    PackageNetwork out = svc.get("notes").orElseThrow();

    assertThat(out.pkg()).isEqualTo("notes");
    assertThat(out.mode()).isEqualTo("direct");
    assertThat(out.gateway()).isNull();
    assertThat(out.containers()).containsExactly("silverbullet");
    assertThat(out.publishedPorts()).containsExactly(3030);
    // The one thing that must never regress: a genuinely running package
    // reported as though its networking had disappeared.
    assertThat(out.locked()).isTrue();
    assertThat(out.lockedReason()).isNotBlank();
  }

  @Test
  void a_package_sharing_a_running_gateways_namespace_reports_vpn_mode_and_a_healthy_gateway() {
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.find("media")).thenReturn(Optional.of(pkg("media", true, true, List.of())));
    Mockito.when(packages.readProbe("media")).thenReturn(Map.of());

    ComposeScanner compose = Mockito.mock(ComposeScanner.class);
    Mockito.when(compose.gatewayFor("media")).thenReturn(Optional.of("gluetun"));

    DockerService docker = Mockito.mock(DockerService.class);
    Container qbittorrent = container("qbittorrent");
    Mockito.when(docker.containersForPackage("media", "media"))
        .thenReturn(List.of(qbittorrent));
    Mockito.when(docker.findByName("gluetun"))
        .thenReturn(Optional.of(new DockerService.ContainerInfo("gluetun", "running", "Up 1h")));

    NetworkService svc = new NetworkService(packages, compose, docker);
    PackageNetwork out = svc.get("media").orElseThrow();

    assertThat(out.mode()).isEqualTo("vpn");
    assertThat(out.gateway()).isEqualTo("gluetun");
    assertThat(out.gatewayHealthy()).isTrue();
  }

  @Test
  void a_down_gateway_is_reported_as_unhealthy_not_hidden() {
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.find("media")).thenReturn(Optional.of(pkg("media", true, true, List.of())));
    Mockito.when(packages.readProbe("media")).thenReturn(Map.of());

    ComposeScanner compose = Mockito.mock(ComposeScanner.class);
    Mockito.when(compose.gatewayFor("media")).thenReturn(Optional.of("gluetun"));

    DockerService docker = Mockito.mock(DockerService.class);
    Mockito.when(docker.containersForPackage("media", "media")).thenReturn(List.of());
    Mockito.when(docker.findByName("gluetun")).thenReturn(Optional.empty());

    NetworkService svc = new NetworkService(packages, compose, docker);
    PackageNetwork out = svc.get("media").orElseThrow();

    assertThat(out.gatewayHealthy()).isFalse();
  }
}
