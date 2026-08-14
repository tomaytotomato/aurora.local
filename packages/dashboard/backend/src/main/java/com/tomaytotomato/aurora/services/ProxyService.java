package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.persistence.ProxyRouteRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /api/proxy} — exposing a container at a friendly address without
 * hand-editing {@code caddy.snippet}.
 *
 * <p>Two kinds of route, both surfaced by {@link #routes()}:
 *
 * <ul>
 *   <li><b>Managed.</b> Derived live from every enabled package's {@code
 *       caddy.snippet} — never persisted, because the snippet is already
 *       the source of truth and a cached copy would drift the moment a
 *       manifest changed. Read-only here; removing one means disabling
 *       the package instead.</li>
 *   <li><b>Hand-added.</b> What an operator created through this API.
 *       Kept in {@link ProxyRouteRepo} for the id/createdAt bookkeeping a
 *       file can't give cheaply, and rendered into one snippet — {@link
 *       CaddySnippetService#CUSTOM_ROUTES_FILENAME} — that Caddy's
 *       existing {@code --watch} picks up the same way it picks up every
 *       package snippet. The file, not the database row, is what Caddy
 *       actually serves.</li>
 * </ul>
 */
@Service
public class ProxyService {

  private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

  /** Labels Aurora refuses to hand out — losing 'admin' or 'auth' to a typo locks the operator out. */
  static final List<String> RESERVED_SUBDOMAINS = List.of("admin", "auth", "www", "localhost");

  /** RFC-1123 label rules, same shape the frontend validates client-side. */
  private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$");

  /**
   * Matches a whole vhost block — header line through its closing brace —
   * tolerating up to one level of nested braces (e.g. a {@code handle}
   * block), which is enough for every snippet style in the repo. Applied
   * with {@link Matcher#find()} so it locates every block in a file
   * regardless of what else surrounds it; a commented-out header (leading
   * {@code #}) never matches because {@code [ \t]*} does not consume it.
   */
  private static final Pattern VHOST_BLOCK = Pattern.compile(
      "(?m)^[ \\t]*https?://([A-Za-z0-9][A-Za-z0-9-]*)\\.\\{\\$DOMAIN}[^\\n{]*\\{(?:[^{}]|\\{[^{}]*\\})*\\}"
  );
  private static final Pattern REVERSE_PROXY = Pattern.compile("reverse_proxy\\s+(\\S+)");

  private final AuroraProperties props;
  private final PackagesService packages;
  private final StateFileService stateFiles;
  private final DockerService docker;
  private final MdnsAliasService mdns;
  private final ProxyRouteRepo customRoutes;
  private final CaddySnippetService caddySnippets;

  public ProxyService(AuroraProperties props, PackagesService packages, StateFileService stateFiles,
                       DockerService docker, MdnsAliasService mdns, ProxyRouteRepo customRoutes,
                       CaddySnippetService caddySnippets) {
    this.props = props;
    this.packages = packages;
    this.stateFiles = stateFiles;
    this.docker = docker;
    this.mdns = mdns;
    this.customRoutes = customRoutes;
    this.caddySnippets = caddySnippets;
  }

  // ─── reads ──────────────────────────────────────────────────────────────

  /** Every vhost Caddy answers to: managed first (package order), then hand-added (creation order). */
  public List<Map<String, Object>> routes() {
    String domain = domain();
    List<Map<String, Object>> out = new ArrayList<>();
    for (var entry : managedRoutes().entrySet()) {
      String label = entry.getKey();
      out.add(routeJson("route-" + label, label, domain, entry.getValue().target(), true, entry.getValue().pkg(), null));
    }
    for (ProxyRouteRepo.Row row : customRoutes.findAll()) {
      out.add(routeJson(row.id(), row.subdomain(), domain, row.target(), false, null, row.createdAt()));
    }
    return out;
  }

  /** Containers worth pointing an address at, with the ports they listen on. */
  public List<Map<String, Object>> targets() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var c : docker.listContainerSummaries()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("container", c.name());
      m.put("ports", c.ports());
      m.put("package", c.pkg());
      out.add(m);
    }
    out.sort(Comparator.comparing(m -> (String) m.get("container")));
    return out;
  }

  /** Dry run: the fragment that would be written, and what it clashes with. */
  public Map<String, Object> preview(String subdomain, String target) {
    String label = normalise(subdomain);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("vhost", label + "." + domain());
    out.put("snippet", snippetFragment(label, target));
    out.put("conflicts", conflictsJson(label, target));
    return out;
  }

  // ─── writes ─────────────────────────────────────────────────────────────

  public Map<String, Object> create(String subdomain, String target) {
    String label = requireValidLabel(subdomain);
    requireNonBlank(target, "target");

    conflictsJson(label, target).stream()
        .filter(c -> Boolean.FALSE.equals(c.get("advisory")))
        .findFirst()
        .ifPresent(c -> {
          throw new ResponseStatusException(HttpStatus.CONFLICT, (String) c.get("message"));
        });

    ProxyRouteRepo.Row row = customRoutes.insert("route-" + UUID.randomUUID(), label, target);
    writeCustomSnippet();
    return routeJson(row.id(), row.subdomain(), domain(), row.target(), false, null, row.createdAt());
  }

  /** No-op (still a success) for an id that names neither a custom nor a managed route — delete is idempotent. */
  public void delete(String id) {
    if (customRoutes.findById(id).isPresent()) {
      customRoutes.delete(id);
      writeCustomSnippet();
      return;
    }
    boolean isManaged = managedRoutes().keySet().stream().anyMatch(label -> ("route-" + label).equals(id));
    if (isManaged) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "That route comes from a package manifest. Remove the app instead.");
    }
  }

  // ─── conflicts ────────────────────────────────────────────────────────────

  private List<Map<String, Object>> conflictsJson(String label, String target) {
    List<Map<String, Object>> out = new ArrayList<>();
    String vhost = label + "." + domain();

    if (RESERVED_SUBDOMAINS.contains(label)) {
      out.add(conflict("reserved", "\"" + label + "\" is reserved by Aurora itself.", false));
    }

    Map<String, ManagedTarget> managed = managedRoutes();
    if (managed.containsKey(label)) {
      out.add(conflict("vhost-taken",
          vhost + " already belongs to the " + managed.get(label).pkg() + " package.", false));
    } else {
      customRoutes.findAll().stream()
          .filter(r -> r.subdomain().equals(label))
          .findFirst()
          .ifPresent(r -> out.add(conflict("vhost-taken",
              vhost + " is already pointing at " + r.target() + ".", false)));
    }

    // An mDNS alias with the same name is a warning rather than a blocker
    // (it will resolve, it just may not resolve to what you expect) —
    // and not worth mentioning at all once a real vhost clash already
    // explains why the name is unavailable.
    boolean vhostTaken = out.stream().anyMatch(c -> "vhost-taken".equals(c.get("kind")));
    if (!vhostTaken && mdns.aliases().stream().anyMatch(a -> vhost.equals(a.alias()))) {
      out.add(conflict("mdns-alias",
          vhost + " is already published on the LAN by mDNS. It will still work, "
              + "but two things now answer to that name.", true));
    }

    String container = target == null ? "" : target.split(":", 2)[0];
    if (!container.isBlank()
        && docker.listContainerSummaries().stream().noneMatch(c -> c.name().equals(container))) {
      out.add(conflict("target-unreachable",
          "Aurora can't see a container called " + container + " on the network right now. "
              + "The route will be written anyway and will start working when it appears.", true));
    }

    return out;
  }

  private static Map<String, Object> conflict(String kind, String message, boolean advisory) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", kind);
    m.put("message", message);
    m.put("advisory", advisory);
    return m;
  }

  // ─── managed-route discovery ──────────────────────────────────────────────

  private record ManagedTarget(String target, String pkg) {}

  /** subdomain label → (target, owning package), for every enabled package's caddy.snippet. First declaration wins. */
  private Map<String, ManagedTarget> managedRoutes() {
    Map<String, ManagedTarget> out = new LinkedHashMap<>();
    for (Package pkg : packages.list()) {
      if (!pkg.enabled()) continue;
      Path snippet = Path.of(props.repoPath(), "packages", pkg.name(), "caddy.snippet");
      if (!Files.isRegularFile(snippet)) continue;
      String body;
      try {
        body = Files.readString(snippet, StandardCharsets.UTF_8);
      } catch (IOException e) {
        log.debug("could not read {}: {}", snippet, e.getMessage());
        continue;
      }
      Matcher blocks = VHOST_BLOCK.matcher(body);
      while (blocks.find()) {
        String label = blocks.group(1).toLowerCase();
        Matcher rp = REVERSE_PROXY.matcher(blocks.group());
        if (rp.find()) {
          out.putIfAbsent(label, new ManagedTarget(rp.group(1), pkg.name()));
        }
      }
    }
    return out;
  }

  // ─── snippet rendering ────────────────────────────────────────────────────

  /**
   * Full render of every hand-added route into one snippet, atomically
   * written under {@link CaddySnippetService#snippetDir()} — the same
   * directory Caddy already watches for every package snippet. Deletes
   * the file entirely once there is nothing left to route, rather than
   * leaving an empty husk behind.
   */
  private void writeCustomSnippet() {
    List<ProxyRouteRepo.Row> rows = customRoutes.findAll();
    Path target = caddySnippets.snippetDir().resolve(CaddySnippetService.CUSTOM_ROUTES_FILENAME);
    try {
      if (rows.isEmpty()) {
        Files.deleteIfExists(target);
        return;
      }
      StringBuilder body = new StringBuilder();
      body.append("# regenerated by Aurora ProxyService — do not hand-edit.\n");
      body.append("# hand-added routes; manage them via the dashboard's Addresses card.\n\n");
      for (ProxyRouteRepo.Row row : rows) {
        body.append(snippetFragment(row.subdomain(), row.target())).append("\n\n");
      }
      CaddySnippetService.atomicWrite(target, body.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("failed to render " + target, e);
    }
  }

  /**
   * Exactly what gets appended for one route — both http and https vhosts,
   * matching the style every package's own {@code caddy.snippet} already
   * uses. Uses the literal {@code {$DOMAIN}} placeholder rather than a
   * resolved domain so the fragment keeps working if the domain is ever
   * changed, the same as every other snippet in the repo.
   */
  private static String snippetFragment(String label, String target) {
    return String.join("\n",
        "http://" + label + ".{$DOMAIN} {",
        "\treverse_proxy " + target,
        "}",
        "https://" + label + ".{$DOMAIN} {",
        "\ttls internal",
        "\treverse_proxy " + target,
        "}"
    );
  }

  // ─── small helpers ────────────────────────────────────────────────────────

  private static Map<String, Object> routeJson(String id, String subdomain, String domain, String target,
                                                boolean managed, String pkg, String createdAt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("subdomain", subdomain);
    m.put("vhost", subdomain + "." + domain);
    m.put("target", target);
    m.put("managed", managed);
    m.put("package", pkg);
    m.put("createdAt", createdAt);
    return m;
  }

  private String domain() {
    var state = stateFiles.readState();
    String d = state.domain();
    return (d == null || d.isBlank()) ? "aurora.local" : d;
  }

  private static String normalise(String s) {
    return s == null ? "" : s.trim().toLowerCase();
  }

  private static String requireValidLabel(String subdomain) {
    String label = normalise(subdomain);
    if (label.isEmpty() || label.length() > 63 || !SUBDOMAIN.matcher(label).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "That is not a usable subdomain — letters, numbers and hyphens only, "
              + "starting and ending with a letter or number.");
    }
    return label;
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required.");
    }
  }
}
