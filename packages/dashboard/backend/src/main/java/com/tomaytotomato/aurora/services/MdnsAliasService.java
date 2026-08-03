package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * mDNS alias publisher (2026-08-03 v0.3.x productionize).
 *
 * <p>Publishes {@code <label>.aurora.local} A-records to the host's
 * avahi-daemon for every vhost each enabled package advertises, so a
 * LAN device can hit e.g. {@code http://notes.aurora.local} without
 * hand-editing /etc/hosts on every device.
 *
 * <p><b>Why not just publish everything?</b> avahi's built-in
 * {@code aurora.local} record is a single A-record for the host — it
 * does not cover subdomains. Standard fix is one {@code avahi-publish
 * -a} process per alias; this service manages that fleet declaratively
 * from {@code .state.yml} + each package's manifest / caddy.snippet.
 *
 * <p><b>Discovery precedence</b>:
 * <ol>
 *   <li>Manifest's {@code vhosts: [notes, ...]} field, when present.
 *       Preferred — explicit, source of truth clear.</li>
 *   <li>Fallback: grep-parse each package's {@code caddy.snippet} for
 *       {@code http(s)?://<label>.{$DOMAIN}} lines. Works today for
 *       every already-shipped package.</li>
 * </ol>
 *
 * <p><b>Reconcile cadence</b>: on {@link ApplicationReadyEvent}, on a
 * 60-second scheduled loop (drift guard), and on an explicit
 * {@code POST /api/mdns/reconcile}. Aliases are ephemeral state
 * (derived from {@code .state.yml} + manifests); we do NOT persist to
 * the SQLite audit trail every reconcile, only when the desired set
 * actually changes.
 *
 * <p><b>Runtime contract</b>: requires
 * {@code /var/run/dbus/system_bus_socket} bind-mounted read-write into
 * the aurora container so {@code /usr/bin/avahi-publish} (already in
 * the Alpine {@code avahi-tools} package) can reach the host's
 * avahi-daemon over D-Bus. When the mount is missing, reconcile marks
 * every alias {@code failed} instead of retrying in a tight loop.
 */
@Service
public class MdnsAliasService {

  private static final Logger log = LoggerFactory.getLogger(MdnsAliasService.class);

  /** {@code http://notes.{$DOMAIN}} → captures {@code notes}. Case-insensitive. */
  private static final Pattern CADDY_VHOST = Pattern.compile(
      "^\\s*https?://([A-Za-z0-9][A-Za-z0-9-]*)\\.\\{\\$DOMAIN}",
      Pattern.CASE_INSENSITIVE
  );

  private final StateFileService state;
  private final SystemService system;
  private final AuditEventRepo audit;
  private final AuroraProperties props;

  /** Alias → running avahi-publish process. Keyed by full name (e.g. "notes.aurora.local"). */
  private final Map<String, Process> published = new ConcurrentHashMap<>();

  /** Last known error per alias (for the API surface). Cleared on successful publish. */
  private final Map<String, String> failureReasons = new ConcurrentHashMap<>();

  /** Timestamps of last successful publish per alias (for the API surface). */
  private final Map<String, Instant> publishedAt = new ConcurrentHashMap<>();

  public MdnsAliasService(
      StateFileService state,
      SystemService system,
      AuditEventRepo audit,
      AuroraProperties props
  ) {
    this.state = state;
    this.system = system;
    this.audit = audit;
    this.props = props;
  }

  /** Snapshot of one alias for the API surface. */
  public record AliasView(
      String alias,          // e.g. "notes.aurora.local"
      String label,          // e.g. "notes"
      String pkg,            // package that declared it
      String source,         // "manifest" | "caddy" | "unknown"
      String state,          // "up" | "failed" | "starting"
      String targetIp,       // resolved LAN IP the alias points at
      Instant publishedAt,   // null until first successful publish
      String error           // null when state=="up"
  ) {}

  // ─── lifecycle ────────────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    log.info("mdns alias publisher: reconciling on startup");
    reconcile();
  }

  /** Drift guard — a shell-out that dies (host avahi restart, D-Bus hiccup) is republished. */
  @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
  public void scheduledReconcile() {
    try {
      reconcile();
    } catch (Exception e) {
      log.warn("mdns alias reconcile threw: {}", e.getMessage());
    }
  }

  @PreDestroy
  public void shutdown() {
    log.info("mdns alias publisher: retracting {} aliases", published.size());
    for (var e : new ArrayList<>(published.entrySet())) {
      killProcess(e.getKey(), e.getValue());
    }
    published.clear();
  }

  // ─── public API ───────────────────────────────────────────────────────────

  /** Idempotent: computes desired set, spawns missing, kills orphaned, republishes crashed. */
  public synchronized List<AliasView> reconcile() {
    var repo = state.readState();
    String domain = repo.domain();
    if (domain == null || domain.isBlank()) {
      // Without a domain there's nothing meaningful to publish.
      retractAll("no domain in .state.yml");
      return List.of();
    }
    String targetIp = system.lanIp();
    if (targetIp == null || targetIp.isBlank()) {
      // Without an IP, avahi-publish -a is a non-starter.
      retractAll("no LAN IP detected");
      return snapshotWithError("no LAN IP detected");
    }

    List<String> enabled = repo.enabled() == null ? List.of() : repo.enabled();

    // desiredLabel → {pkg, source}
    var desired = new LinkedHashMap<String, LabelSource>();
    for (String pkgName : enabled) {
      for (Map.Entry<String, LabelSource> e : discoverLabels(pkgName).entrySet()) {
        desired.putIfAbsent(e.getKey(), e.getValue());
      }
    }

    // Retract orphans first — a package flipped off, or a vhost was removed.
    Set<String> keepFullNames = desired.keySet().stream()
        .map(label -> label + "." + domain)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    for (String full : new ArrayList<>(published.keySet())) {
      if (!keepFullNames.contains(full)) {
        killProcess(full, published.remove(full));
        failureReasons.remove(full);
        publishedAt.remove(full);
        audit.record(null, "mdns.alias.retract", full, null);
      }
    }

    // Publish missing / restart crashed.
    for (var entry : desired.entrySet()) {
      String label = entry.getKey();
      LabelSource src = entry.getValue();
      String full = label + "." + domain;

      Process existing = published.get(full);
      if (existing != null && existing.isAlive()) {
        continue; // already up
      }
      if (existing != null) {
        // Was up, now dead — clean the slot before respawning.
        published.remove(full);
      }

      Process p = spawnPublish(full, targetIp);
      if (p == null) {
        // spawn already logged; failureReasons populated
        continue;
      }
      published.put(full, p);
      publishedAt.put(full, Instant.now());
      failureReasons.remove(full);
      audit.record(null, "mdns.alias.publish", full,
          "{\"package\":\"" + src.pkg() + "\",\"source\":\"" + src.source() + "\",\"target_ip\":\"" + targetIp + "\"}");
    }

    return snapshot(desired, domain, targetIp);
  }

  /** Read-only view. Does NOT reconcile — call {@link #reconcile()} for that. */
  public List<AliasView> aliases() {
    var repo = state.readState();
    String domain = repo.domain();
    if (domain == null) return List.of();
    List<String> enabled = repo.enabled() == null ? List.of() : repo.enabled();
    var desired = new LinkedHashMap<String, LabelSource>();
    for (String pkg : enabled) {
      for (var e : discoverLabels(pkg).entrySet()) desired.putIfAbsent(e.getKey(), e.getValue());
    }
    return snapshot(desired, domain, system.lanIp());
  }

  // ─── discovery ────────────────────────────────────────────────────────────

  /**
   * Union of manifest {@code vhosts:} + caddy.snippet grep. Preserves
   * insertion order (manifest first) so an operator reading the API
   * output sees the declared list before the grep-derived one.
   */
  Map<String, LabelSource> discoverLabels(String pkgName) {
    var out = new LinkedHashMap<String, LabelSource>();
    Path pkgDir = repoRoot().resolve("packages").resolve(pkgName);
    if (!Files.isDirectory(pkgDir)) return out;

    // 1. Manifest-declared vhosts (preferred).
    Path manifest = pkgDir.resolve("manifest.yml");
    if (Files.isRegularFile(manifest)) {
      try (var in = Files.newInputStream(manifest)) {
        Map<String, Object> m = new Yaml().load(in);
        if (m != null && m.get("vhosts") instanceof List<?> list) {
          for (Object o : list) {
            if (o == null) continue;
            String label = o.toString().trim();
            if (isValidLabel(label)) {
              out.putIfAbsent(label, new LabelSource(pkgName, "manifest"));
            }
          }
        }
      } catch (IOException e) {
        log.debug("manifest read failed for {}: {}", pkgName, e.getMessage());
      }
    }

    // 2. caddy.snippet fallback.
    Path snippet = pkgDir.resolve("caddy.snippet");
    if (Files.isRegularFile(snippet)) {
      try (var lines = Files.lines(snippet, StandardCharsets.UTF_8)) {
        lines.forEach(line -> {
          if (line.trim().startsWith("#")) return; // commented-out vhost example
          Matcher m = CADDY_VHOST.matcher(line);
          if (m.find()) {
            String label = m.group(1).toLowerCase();
            if (isValidLabel(label)) {
              out.putIfAbsent(label, new LabelSource(pkgName, "caddy"));
            }
          }
        });
      } catch (IOException e) {
        log.debug("caddy.snippet read failed for {}: {}", pkgName, e.getMessage());
      }
    }
    return out;
  }

  static boolean isValidLabel(String s) {
    if (s == null || s.isBlank() || s.length() > 63) return false;
    return s.matches("[a-zA-Z0-9][a-zA-Z0-9-]*");
  }

  private Path repoRoot() {
    return Path.of(props.repoPath());
  }

  // ─── subprocess mgmt ──────────────────────────────────────────────────────

  private Process spawnPublish(String full, String targetIp) {
    ProcessBuilder pb = new ProcessBuilder(
        "/usr/bin/avahi-publish",
        "-a",            // publish an address record
        "-R",            // let daemon handle rDNS
        full,
        targetIp
    );
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      // Drain stdout on a daemon thread so it doesn't pin the pipe buffer.
      Thread drain = new Thread(() -> drainProcessOutput(full, p), "mdns-drain-" + full);
      drain.setDaemon(true);
      drain.start();
      // Give avahi a moment to fail-fast if D-Bus is missing.
      try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      if (!p.isAlive()) {
        int rc = p.exitValue();
        failureReasons.put(full,
            rc == 0 ? "process exited immediately" : "avahi-publish exit " + rc);
        log.warn("avahi-publish {} exited immediately (rc={})", full, rc);
        return null;
      }
      log.info("published mdns alias {} → {} (pid {})", full, targetIp, p.pid());
      return p;
    } catch (IOException e) {
      failureReasons.put(full, "spawn failed: " + e.getMessage());
      log.warn("failed to spawn avahi-publish for {}: {}", full, e.getMessage());
      return null;
    }
  }

  private void drainProcessOutput(String full, Process p) {
    try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        // avahi-publish is very quiet on success ("Established under name ...")
        // and slightly noisy on failure. Debug-log everything so it's grep-able
        // if something goes sideways, but don't spam INFO with success chatter.
        log.debug("avahi-publish[{}]: {}", full, line);
      }
    } catch (IOException e) {
      log.debug("drain failed for {}: {}", full, e.getMessage());
    }
  }

  private void killProcess(String full, Process p) {
    if (p == null) return;
    log.info("retracting mdns alias {} (pid {})", full, p.pid());
    p.destroy();
    try {
      if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private void retractAll(String reason) {
    for (var e : new ArrayList<>(published.entrySet())) {
      killProcess(e.getKey(), e.getValue());
      audit.record(null, "mdns.alias.retract", e.getKey(),
          "{\"reason\":\"" + reason.replace("\"", "\\\"") + "\"}");
    }
    published.clear();
    failureReasons.clear();
    publishedAt.clear();
  }

  // ─── snapshot for API ─────────────────────────────────────────────────────

  private List<AliasView> snapshot(Map<String, LabelSource> desired, String domain, String targetIp) {
    var out = new ArrayList<AliasView>(desired.size());
    for (var entry : desired.entrySet()) {
      String label = entry.getKey();
      LabelSource src = entry.getValue();
      String full = label + "." + domain;
      Process p = published.get(full);
      boolean up = p != null && p.isAlive();
      String state = up ? "up" : (failureReasons.containsKey(full) ? "failed" : "starting");
      out.add(new AliasView(full, label, src.pkg(), src.source(), state, targetIp,
          publishedAt.get(full), failureReasons.get(full)));
    }
    return out;
  }

  private List<AliasView> snapshotWithError(String reason) {
    // Used when reconcile early-exits due to a global missing prerequisite.
    return List.of();
  }

  record LabelSource(String pkg, String source) {}
}
