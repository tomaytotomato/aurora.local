package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.RepoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scan {@code /repo/packages/*&#47;manifest.yml} and cross-reference against
 * {@code .state.yml} + docker to produce a live package catalogue.
 */
@Service
public class PackagesService {

  private static final Logger log = LoggerFactory.getLogger(PackagesService.class);

  private final AuroraProperties props;
  private final StateFileService stateFiles;
  private final DockerService docker;

  public PackagesService(AuroraProperties props, StateFileService stateFiles, DockerService docker) {
    this.props = props;
    this.stateFiles = stateFiles;
    this.docker = docker;
  }

  public List<Package> list() {
    RepoState state = stateFiles.readState();
    Set<String> enabled = new HashSet<>(state.enabled() == null ? List.of() : state.enabled());
    Set<String> running = runningPackageNames();

    Path pkgs = Path.of(props.repoPath()).resolve("packages");
    List<Package> out = new ArrayList<>();
    if (!Files.isDirectory(pkgs)) {
      log.warn("packages dir not found at {}", pkgs);
      return out;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(pkgs, Files::isDirectory)) {
      for (Path dir : ds) {
        String name = dir.getFileName().toString();
        if (name.startsWith("_") || name.startsWith(".")) continue;
        Path manifest = dir.resolve("manifest.yml");
        if (!Files.isRegularFile(manifest)) continue;
        parseManifest(manifest, enabled.contains(name), running.contains(name)).ifPresent(out::add);
      }
    } catch (IOException e) {
      throw new RuntimeException("failed to scan " + pkgs, e);
    }
    out.sort((a, b) -> a.name().compareTo(b.name()));
    return out;
  }

  public Optional<Package> find(String name) {
    return list().stream().filter(p -> p.name().equals(name)).findFirst();
  }

  public Optional<String> readEnvExample(String name) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(name).resolve(".env.example");
    if (!Files.isRegularFile(p)) return Optional.empty();
    try {
      return Optional.of(Files.readString(p));
    } catch (IOException e) {
      throw new RuntimeException("failed to read " + p, e);
    }
  }

  private Set<String> runningPackageNames() {
    // Compose service name doesn't strictly equal package name, but the
    // convention in aurora.local is that each package's compose sets
    // `name: aurora-<pkg>`. Use the label directly for v0.1: it's more
    // reliable than parsing service names.
    Set<String> out = new HashSet<>();
    List<Container> containers;
    try {
      containers = docker.listProjectContainers();
    } catch (Exception e) {
      log.warn("docker unavailable: {}", e.getMessage());
      return out;
    }
    for (Container c : containers) {
      if (c.getLabels() == null) continue;
      String cfg = c.getLabels().get("com.docker.compose.project.config_files");
      if (cfg == null) continue;
      // config_files is comma-separated absolute paths; last element wins
      // as the "owning" package (first is always core).
      for (String seg : cfg.split(",")) {
        int i = seg.indexOf("/packages/");
        if (i < 0) continue;
        String rest = seg.substring(i + "/packages/".length());
        int slash = rest.indexOf('/');
        if (slash > 0) out.add(rest.substring(0, slash));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Optional<Package> parseManifest(Path manifest, boolean enabled, boolean running) {
    try (var in = Files.newInputStream(manifest)) {
      Map<String, Object> m = new Yaml().load(in);
      if (m == null) return Optional.empty();
      return Optional.of(new Package(
          str(m, "name"),
          str(m, "title"),
          str(m, "description"),
          str(m, "category"),
          strList(m.get("depends_on")),
          strList(m.get("recommends")),
          m.get("profiles") instanceof Map map ? (Map<String, Object>) map : Map.of(),
          m.get("ports") instanceof List list ? (List<Map<String, Object>>) list : List.of(),
          m.get("requires") instanceof Map map ? (Map<String, Object>) map : Map.of(),
          strList(m.get("required_env")),
          str(m, "post_install_notes"),
          enabled,
          running
      ));
    } catch (IOException e) {
      log.warn("failed to parse {}: {}", manifest, e.getMessage());
      return Optional.empty();
    }
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? null : v.toString();
  }

  private static List<String> strList(Object o) {
    if (o instanceof List<?> list) {
      return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).collect(Collectors.toList());
    }
    return List.of();
  }
}
