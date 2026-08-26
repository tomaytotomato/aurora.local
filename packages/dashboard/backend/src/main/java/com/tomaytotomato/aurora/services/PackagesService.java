package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.EnvVarSpec;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.PackageBackupSpec;
import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.domain.SsoBlock;
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
  private final MdnsAliasService mdns;

  public PackagesService(AuroraProperties props, StateFileService stateFiles, DockerService docker,
                         MdnsAliasService mdns) {
    this.props = props;
    this.stateFiles = stateFiles;
    this.docker = docker;
    this.mdns = mdns;
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

  /**
   * The summary plus everything the app detail page renders: the README,
   * the vhosts the package serves, what each of its environment variables
   * is for, and its {@code backup:} block.
   *
   * <p>All of it derived, none of it duplicated into the manifest. The
   * README is the README; the vhosts come from the same discovery
   * {@link MdnsAliasService} advertises from, so a package cannot serve a
   * hostname the page fails to list; the env specs come from
   * {@code .env.example}, the only file that records what a variable is
   * for. A manifest copy of any of it would be a second truth to drift.
   */
  public Optional<Package> detail(String name) {
    return find(name).map(pkg -> pkg.withDetail(
        readReadme(name).orElse(null),
        vhostsFor(name),
        envSpecs(name, pkg.requiredEnv()),
        PackageBackupSpec.fromManifest(readManifestBlock(name, "backup"))
    ));
  }

  /** Contents of {@code packages/<name>/README.md}, if it has one. */
  Optional<String> readReadme(String name) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(name).resolve("README.md");
    if (!Files.isRegularFile(p)) return Optional.empty();
    try {
      return Optional.of(Files.readString(p));
    } catch (IOException e) {
      log.warn("failed to read {}: {}", p, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Every hostname this package serves, fully qualified.
   *
   * <p>Reuses {@link MdnsAliasService#discoverLabels} rather than parsing
   * caddy.snippet again: those aliases are what avahi actually advertises
   * on the LAN, so deriving the page's list from anywhere else would let
   * the two disagree. The labels are bare ({@code photos}); the page needs
   * something a browser can open, so the domain is appended here.
   */
  List<String> vhostsFor(String name) {
    RepoState state = stateFiles.readState();
    String domain = state.domain() == null || state.domain().isBlank() ? "aurora.local" : state.domain();
    return mdns.discoverLabels(name).keySet().stream()
        .map(label -> label + "." + domain)
        .collect(Collectors.toList());
  }

  /**
   * What each of the package's environment variables is for, parsed from
   * {@code .env.example}: the key, the example value beside it, and the
   * comment block immediately above it.
   *
   * <p>{@code required} comes from the manifest's {@code required_env},
   * because a flat env file has no way to say "this one has no usable
   * default". {@code secret} comes from the key's name — see
   * {@link #looksLikeSecret}.
   */
  List<EnvVarSpec> envSpecs(String name, List<String> requiredEnv) {
    String body = readEnvExample(name).orElse(null);
    if (body == null) return List.of();
    Set<String> required = new HashSet<>(requiredEnv == null ? List.of() : requiredEnv);

    List<EnvVarSpec> out = new ArrayList<>();
    List<String> pendingComment = new ArrayList<>();
    for (String raw : body.split("\n", -1)) {
      String line = raw.strip();
      if (line.isEmpty()) {
        // A blank line ends a comment block. Without this the file's
        // header banner would be attributed to whichever key came first.
        pendingComment.clear();
        continue;
      }
      if (line.startsWith("#")) {
        String text = line.substring(1).strip();
        if (!text.isEmpty()) pendingComment.add(text);
        continue;
      }
      int eq = line.indexOf('=');
      if (eq <= 0) continue;
      String key = line.substring(0, eq).strip();
      String example = line.substring(eq + 1).strip();
      out.add(new EnvVarSpec(
          key,
          example.isEmpty() ? null : example,
          pendingComment.isEmpty() ? null : String.join(" ", pendingComment),
          looksLikeSecret(key),
          required.contains(key)
      ));
      pendingComment.clear();
    }
    return out;
  }

  /**
   * Whether a variable name denotes a secret.
   *
   * <p>These two patterns are a port of {@code NON_SECRET_PATTERN} and
   * {@code SECRET_HINT_PATTERN} in {@code scripts/rotate-secrets.sh},
   * which is the established definition of the same question — that
   * script decides what to generate on first boot and what to leave
   * alone. Two copies of a rule is one more than anybody wants; keeping
   * them in step is a manual obligation, and the alternative (shelling
   * out to bash to classify a string) is worse. If you change one, change
   * the other.
   */
  static boolean looksLikeSecret(String key) {
    if (key == null || key.isBlank()) return false;
    if (NOT_A_SECRET.matcher(key).matches()) return false;
    return SECRET_HINT.matcher(key).find();
  }

  private static final java.util.regex.Pattern NOT_A_SECRET = java.util.regex.Pattern.compile(
      "^(TZ|DOMAIN|LAN_IP|VPN_SERVICE_PROVIDER|VPN_TYPE|SERVER_COUNTRIES|SERVER_CITIES"
          + "|OPENVPN_USER|HOMEPAGE_VAR_[A-Z]+_USER|.*_USER|FIREWALL.*"
          + "|VPN_PORT_FORWARDING(_PROVIDER)?|PORT_FORWARD_ONLY|WIREGUARD_ADDRESSES)$");

  private static final java.util.regex.Pattern SECRET_HINT =
      java.util.regex.Pattern.compile("(SECRET|KEY|PASSWORD|TOKEN|PASS|PSK)");

  /**
   * One top-level block from a package manifest, for callers outside this
   * class — {@link BackupService} needs the {@code backup:} block and
   * should not re-implement manifest reading to get it.
   */
  Object readManifestBlockFor(String name, String block) {
    return readManifestBlock(name, block);
  }

  /** One top-level block from a package manifest, or null when absent. */
  private Object readManifestBlock(String name, String block) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(name).resolve("manifest.yml");
    if (!Files.isRegularFile(p)) return null;
    try (var in = Files.newInputStream(p)) {
      Map<String, Object> m = new Yaml().load(in);
      return m == null ? null : m.get(block);
    } catch (IOException e) {
      log.warn("failed to read {} from {}: {}", block, p, e.getMessage());
      return null;
    }
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

  /**
   * Read the {@code warnings:} block from a package manifest, if any.
   * Each entry is a raw map: {@code {id, if: {…}, message}}. Returns an
   * empty list if the manifest declares no warnings or is unreadable.
   *
   * <p>Kept off {@link com.tomaytotomato.aurora.domain.Package} on purpose
   * so the {@code Package} record stays a stable v0.1 contract; the
   * {@code warnings:} schema is v0.2 and evaluated by {@link OnboardingService}.
   */
  /**
   * Read the {@code probe:} block from a package manifest, if any.
   * Returns an empty map when the manifest has no probe declaration.
   * Used by {@link StatusProbeService} to decide how to health-check each
   * enabled package on the Done page.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> readProbe(String name) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(name).resolve("manifest.yml");
    if (!Files.isRegularFile(p)) return Map.of();
    try (var in = Files.newInputStream(p)) {
      Map<String, Object> m = new Yaml().load(in);
      if (m == null) return Map.of();
      Object raw = m.get("probe");
      if (!(raw instanceof Map<?, ?> mm)) return Map.of();
      return (Map<String, Object>) mm;
    } catch (IOException e) {
      log.warn("readProbe({}) failed: {}", name, e.getMessage());
      return Map.of();
    }
  }

  /**
   * iter-3 BL1: return the manifest's declared subpackages (child services
   * that share the parent package's compose file but get their own status
   * probe). Empty when the manifest has no {@code subpackages:} block.
   *
   * <p>Each returned entry has {@code name}, optional {@code title},
   * optional {@code container}, and a {@code probe} sub-map with the same
   * shape as top-level {@code probe}.
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> readSubpackages(String name) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(name).resolve("manifest.yml");
    if (!Files.isRegularFile(p)) return List.of();
    try (var in = Files.newInputStream(p)) {
      Map<String, Object> m = new Yaml().load(in);
      if (m == null) return List.of();
      Object raw = m.get("subpackages");
      if (!(raw instanceof List<?> list)) return List.of();
      List<Map<String, Object>> out = new ArrayList<>();
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> em) out.add((Map<String, Object>) em);
      }
      return out;
    } catch (IOException e) {
      log.warn("readSubpackages({}) failed: {}", name, e.getMessage());
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> readWarnings(String name) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(name).resolve("manifest.yml");
    if (!Files.isRegularFile(p)) return List.of();
    try (var in = Files.newInputStream(p)) {
      Map<String, Object> m = new Yaml().load(in);
      if (m == null) return List.of();
      Object raw = m.get("warnings");
      if (!(raw instanceof List<?> list)) return List.of();
      var out = new ArrayList<Map<String, Object>>();
      for (Object o : list) {
        if (o instanceof Map<?, ?> mm) out.add((Map<String, Object>) mm);
      }
      return out;
    } catch (IOException e) {
      log.warn("readWarnings({}) failed: {}", name, e.getMessage());
      return List.of();
    }
  }

  /**
   * Read the {@code requires:} block from a package manifest (e.g.
   * {@code min_ram_mb}, {@code min_disk_gb}). Returns an empty map when
   * the manifest is missing, unreadable, or declares no requires block.
   * Used by the running resource-budget check in {@link OnboardingService}.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> readRequires(String name) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(name).resolve("manifest.yml");
    if (!Files.isRegularFile(p)) return Map.of();
    try (var in = Files.newInputStream(p)) {
      Map<String, Object> m = new Yaml().load(in);
      if (m == null) return Map.of();
      Object raw = m.get("requires");
      if (!(raw instanceof Map<?, ?> mm)) return Map.of();
      return (Map<String, Object>) mm;
    } catch (IOException e) {
      log.warn("readRequires({}) failed: {}", name, e.getMessage());
      return Map.of();
    }
  }

  // Infrastructure packages that are always installed and never appear in
  // the user-facing enabled[] set. Excluded from install-diff surfaces so
  // the Done screen doesn't tell the user to "stop the dashboard" (i.e.
  // itself) because they never explicitly opted into it.
  private static final java.util.Set<String> INFRASTRUCTURE_PACKAGES =
      java.util.Set.of("dashboard");

  /** Packages the wizard enabled that don't have any containers up yet. */
  public List<String> enabledNotRunning() {
    var out = new ArrayList<String>();
    for (var p : list()) {
      if (INFRASTRUCTURE_PACKAGES.contains(p.name())) continue;
      if (p.enabled() && !p.running()) out.add(p.name());
    }
    return out;
  }

  /** Packages with containers running that the wizard doesn't have enabled. */
  public List<String> runningNotEnabled() {
    var out = new ArrayList<String>();
    for (var p : list()) {
      if (INFRASTRUCTURE_PACKAGES.contains(p.name())) continue;
      if (p.running() && !p.enabled()) out.add(p.name());
    }
    return out;
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
      // B1: a package with probe.kind == 'self' means "the dashboard itself".
      // If we are responding to this request, we are running — regardless of
      // whether a docker compose project label points back at /packages/<name>/.
      // Without this, Aurora (started at bootstrap, not from packages/core/) is
      // never marked running by runningPackageNames() and the Packages card
      // shows a Start button for Core.
      boolean effectiveRunning = running;
      if (!effectiveRunning && m.get("probe") instanceof Map probeMap) {
        Object kind = probeMap.get("kind");
        if ("self".equals(kind)) effectiveRunning = true;
      }
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
          effectiveRunning,
          SsoBlock.fromManifest(m.get("sso")),
          str(m, "source_url"),
          str(m, "homepage_url"),
          str(m, "icon"),
          null, null, null, null
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
