package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.PackageNetwork;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET /packages/{name}/network} — how one app's traffic leaves the
 * box. See docs/SPLIT_TUNNEL.md for the mechanism.
 *
 * <p>The per-app toggle ("move this app onto the VPN gateway from the
 * dashboard") is still that document's "Planned" section — no compose
 * rewrite, port move, or Caddy vhost update exists yet, so every package
 * reports {@code locked=true} regardless of whether it currently has a
 * gateway. What this service reports honestly today is: does this
 * package's compose.yml already share a gateway's network namespace
 * (qBittorrent under gluetun does; nothing else does yet), and is that
 * gateway actually up.
 */
@Service
public class NetworkService {

  private final PackagesService packages;
  private final ComposeScanner compose;
  private final DockerService docker;

  public NetworkService(PackagesService packages, ComposeScanner compose, DockerService docker) {
    this.packages = packages;
    this.compose = compose;
    this.docker = docker;
  }

  /** Empty when the package doesn't exist — caller renders 404. */
  public Optional<PackageNetwork> get(String pkg) {
    Optional<Package> found = packages.find(pkg);
    if (found.isEmpty()) return Optional.empty();
    Package p = found.get();

    Optional<String> gateway = compose.gatewayFor(pkg);
    String mode = gateway.isPresent() ? "vpn" : "direct";

    String expectedContainer = expectedContainerFor(pkg);
    List<String> containers = new ArrayList<>();
    for (Container c : docker.containersForPackage(pkg, expectedContainer)) {
      String name = primaryName(c);
      if (name != null) containers.add(name);
    }

    boolean gatewayHealthy = gateway.map(g -> docker.findByName(g)
        .map(DockerService.ContainerInfo::isRunning)
        .orElse(false))
        .orElse(true); // no gateway to be unhealthy — direct mode is always "fine"

    List<Integer> publishedPorts = portsFor(p);

    return Optional.of(new PackageNetwork(
        pkg,
        mode,
        gateway.orElse(null),
        true, // locked — see class javadoc; the toggle itself isn't built yet
        PackageNetwork.NOT_WIRED_UP_YET,
        containers,
        publishedPorts,
        null, // egressIp — no external lookup mechanism exists yet
        null, // egressCountry — ditto
        gatewayHealthy
    ));
  }

  private String expectedContainerFor(String pkg) {
    Map<String, Object> probe = packages.readProbe(pkg);
    Object container = probe == null ? null : probe.get("container");
    return container instanceof String s && !s.isBlank() ? s : pkg;
  }

  private static List<Integer> portsFor(Package p) {
    List<Integer> out = new ArrayList<>();
    if (p.ports() == null) return out;
    for (Map<String, Object> entry : p.ports()) {
      Object raw = entry.get("port");
      if (raw instanceof Number n && !out.contains(n.intValue())) out.add(n.intValue());
    }
    return out;
  }

  private static String primaryName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return null;
    String n = names[0];
    return n.startsWith("/") ? n.substring(1) : n;
  }
}
