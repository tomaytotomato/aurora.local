package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.RepoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Iter-2: probe each enabled package on the user's behalf so the Done page
 * (and, in iter-3, the dashboard home) can render a living checklist without
 * the user typing anything.
 *
 * <p>Design constraints (see logs/ux-iteration-2.md §2a):
 * <ul>
 *   <li>2-second hard timeout per probe. HttpClient connectTimeout(1s) +
 *       per-request timeout(2s) is the belt-and-braces.</li>
 *   <li>3-second in-memory TTL cache keyed by package name so two clients
 *       polling at 5s don't amplify.</li>
 *   <li>Bounded parallel fan-out via {@link ForkJoinPool#commonPool()};
 *       controller enforces a 4s wall-clock ceiling.</li>
 *   <li>Priority weights for stable sort: failed=0, needs-config=1,
 *       not-started=2, starting=3, running=4.</li>
 *   <li>Reason/detail strings are copy for humans and MUST NOT contain
 *       shell-command substrings (sudo/docker/bash/ssh/./scripts/).</li>
 * </ul>
 */
@Service
public class StatusProbeService {

  private static final Logger log = LoggerFactory.getLogger(StatusProbeService.class);

  static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  static final Duration CACHE_TTL = Duration.ofSeconds(3);

  /** Ordered so the stable comparator can look up in O(1). */
  static final Map<String, Integer> STATE_PRIORITY = Map.of(
      "failed", 0,
      "needs-config", 1,
      "not-started", 2,
      "starting", 3,
      "running", 4
  );

  private final PackagesService packages;
  private final StateFileService stateFiles;
  private final SystemService system;
  private final DockerService docker;
  private final HttpClient http;

  private final Map<String, CachedProbe> cache = new ConcurrentHashMap<>();

  @Autowired
  public StatusProbeService(PackagesService packages, StateFileService stateFiles,
                            SystemService system, DockerService docker) {
    this.packages = packages;
    this.stateFiles = stateFiles;
    this.system = system;
    this.docker = docker;
    this.http = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** Test-visible constructor allowing an injected HttpClient. */
  StatusProbeService(PackagesService packages, StateFileService stateFiles,
                     SystemService system, DockerService docker, HttpClient http) {
    this.packages = packages;
    this.stateFiles = stateFiles;
    this.system = system;
    this.docker = docker;
    this.http = http;
  }

  /**
   * Snapshot of every enabled package's live status, sorted blocker-first.
   * The {@code generated_at} timestamp is stable within {@link #CACHE_TTL}
   * so back-to-back polls return an identical payload.
   */
  public Map<String, Object> snapshot() {
    RepoState state = stateFiles.readState();
    List<String> enabled = state.enabled() == null ? List.of() : state.enabled();

    List<CompletableFuture<ProbeResult>> futures = new ArrayList<>();
    for (String pkg : enabled) {
      futures.add(CompletableFuture.supplyAsync(() -> probeCached(pkg), ForkJoinPool.commonPool()));
    }

    List<ProbeResult> results = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
    for (int i = 0; i < futures.size(); i++) {
      String pkg = enabled.get(i);
      long remainingNs = Math.max(0L, deadline - System.nanoTime());
      try {
        results.add(futures.get(i).get(remainingNs, TimeUnit.NANOSECONDS));
      } catch (TimeoutException e) {
        results.add(ProbeResult.starting(pkg, null, "Still checking…", -1));
      } catch (Exception e) {
        log.warn("probe({}) failed unexpectedly: {}", pkg, e.getMessage());
        results.add(ProbeResult.failed(pkg, null, "Probe error", "Aurora could not reach this service.", -1));
      }
    }

    // Sort blocker-first, then alphabetical for deterministic renders.
    results.sort(Comparator.<ProbeResult>comparingInt(r -> STATE_PRIORITY.getOrDefault(r.state, 5))
        .thenComparing(r -> r.pkg));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("generated_at", earliestCacheStamp(enabled).toString());
    List<Map<String, Object>> arr = new ArrayList<>();
    for (ProbeResult r : results) arr.add(r.toJson());
    out.put("services", arr);
    return out;
  }

  private Instant earliestCacheStamp(List<String> pkgs) {
    Instant earliest = null;
    for (String p : pkgs) {
      CachedProbe cp = cache.get(p);
      if (cp == null) continue;
      if (earliest == null || cp.stamp.isBefore(earliest)) earliest = cp.stamp;
    }
    return earliest == null ? Instant.now() : earliest;
  }

  ProbeResult probeCached(String pkg) {
    Instant now = Instant.now();
    CachedProbe cp = cache.get(pkg);
    if (cp != null && Duration.between(cp.stamp, now).compareTo(CACHE_TTL) < 0) {
      return cp.result;
    }
    ProbeResult fresh = probe(pkg);
    cache.put(pkg, new CachedProbe(now, fresh));
    return fresh;
  }

  ProbeResult probe(String pkg) {
    long started = System.nanoTime();
    Map<String, Object> probeCfg = packages.readProbe(pkg);
    String kind = probeCfg.get("kind") instanceof String s ? s : "docker";
    String container = probeCfg.get("container") instanceof String s ? s : pkg;
    String externalUrl = resolveTemplate(
        probeCfg.get("external_url") instanceof String s ? s : null);
    String inCluster = probeCfg.get("in_cluster_url") instanceof String s ? s : null;
    boolean auth401AsUp = Boolean.TRUE.equals(probeCfg.get("auth_treats_401_as_up"));

    try {
      switch (kind) {
        case "self":
          return ProbeResult.running(pkg, container, externalUrl, ms(started));
        case "adguard":
          return probeAdguard(pkg, container, inCluster, externalUrl, started);
        case "http_json":
          return probeHttpJson(pkg, container, inCluster, externalUrl, auth401AsUp, started);
        case "docker":
        default:
          return probeDocker(pkg, container, externalUrl, started);
      }
    } catch (Exception e) {
      log.debug("probe({}) exception: {}", pkg, e.toString());
      return ProbeResult.failed(pkg, container, "Aurora could not reach this service.",
          "The last probe hit an unexpected error.", ms(started));
    }
  }

  private ProbeResult probeAdguard(String pkg, String container, String inCluster,
                                   String externalUrl, long started) {
    // Precondition: container must be up before any HTTP probe is meaningful.
    Optional<DockerService.ContainerInfo> c = docker.findByName(container);
    if (c.isEmpty()) {
      return ProbeResult.notStarted(pkg, container, externalUrl, ms(started));
    }
    if (!c.get().isRunning()) {
      return ProbeResult.failed(pkg, container, "AdGuard is not responding",
          "The container exited. Aurora will try to bring it back up.", ms(started));
    }
    String base = inCluster == null ? "http://" + container + ":3000" : inCluster;

    // Step 1: first-run detector.
    HttpResponse<String> first = safeGet(base + "/control/install/get_addresses");
    if (first != null && first.statusCode() == 200) {
      return ProbeResult.needsConfig(pkg, container,
          "AdGuard admin password not set",
          "First-run setup incomplete. Open AdGuard to finish.",
          externalUrl, ms(started));
    }
    // Step 2 + 3: authenticated status.
    HttpResponse<String> status = safeGet(base + "/control/status");
    if (status == null) {
      return ProbeResult.failed(pkg, container, "AdGuard is not responding",
          "The container is up but its API did not answer.", ms(started));
    }
    int code = status.statusCode();
    if (code == 401 || code == 403) {
      return ProbeResult.running(pkg, container, externalUrl, ms(started));
    }
    if (code == 200) {
      String body = status.body() == null ? "" : status.body();
      if (body.contains("\"configured\":false") || body.contains("\"running\":false")) {
        return ProbeResult.needsConfig(pkg, container,
            "AdGuard admin password not set",
            "First-run setup incomplete. Open AdGuard to finish.",
            externalUrl, ms(started));
      }
      return ProbeResult.running(pkg, container, externalUrl, ms(started));
    }
    return ProbeResult.failed(pkg, container, "AdGuard is not responding",
        "Unexpected response from AdGuard (" + code + ").", ms(started));
  }

  private ProbeResult probeHttpJson(String pkg, String container, String inCluster,
                                    String externalUrl, boolean auth401AsUp, long started) {
    Optional<DockerService.ContainerInfo> c = docker.findByName(container);
    if (c.isEmpty()) {
      return ProbeResult.notStarted(pkg, container, externalUrl, ms(started));
    }
    if (!c.get().isRunning()) {
      return ProbeResult.failed(pkg, container, "Service is not responding",
          "The container exited. Aurora will try to bring it back up.", ms(started));
    }
    if (inCluster == null) {
      // No URL to probe — trust docker liveness.
      return ProbeResult.running(pkg, container, externalUrl, ms(started));
    }
    HttpResponse<String> res = safeGet(inCluster);
    if (res == null) {
      return ProbeResult.failed(pkg, container, "Service is not responding",
          "The container is up but its API did not answer.", ms(started));
    }
    int code = res.statusCode();
    if (auth401AsUp && (code == 401 || code == 403)) {
      return ProbeResult.running(pkg, container, externalUrl, ms(started));
    }
    if (code >= 200 && code < 300) {
      String body = res.body() == null ? "" : res.body();
      if (body.contains("\"authentication\":\"None\"") || body.contains("\"authentication\": \"None\"")) {
        return ProbeResult.needsConfig(pkg, container,
            "Authentication is not configured yet",
            "Open the service to finish first-run setup.",
            externalUrl, ms(started));
      }
      return ProbeResult.running(pkg, container, externalUrl, ms(started));
    }
    return ProbeResult.failed(pkg, container, "Service is not responding",
        "Unexpected response (" + code + ").", ms(started));
  }

  private ProbeResult probeDocker(String pkg, String container, String externalUrl, long started) {
    Optional<DockerService.ContainerInfo> c = docker.findByName(container);
    if (c.isEmpty()) {
      return ProbeResult.notStarted(pkg, container, externalUrl, ms(started));
    }
    DockerService.ContainerInfo ct = c.get();
    String state = ct.state();
    String status = ct.status();
    if (ct.isExited()) {
      return ProbeResult.failed(pkg, container, "Service exited",
          "Aurora will try to bring it back up.", ms(started));
    }
    if (ct.isRunning()) {
      // Distinguish "starting" from "running" using the (health: starting) marker.
      if (status.toLowerCase().contains("(health: starting)")) {
        return ProbeResult.starting(pkg, container, externalUrl, ms(started));
      }
      return ProbeResult.running(pkg, container, externalUrl, ms(started));
    }
    return ProbeResult.starting(pkg, container, externalUrl, ms(started));
  }

  private boolean isUp(DockerService.ContainerInfo c) {
    return c.isRunning();
  }

  private HttpResponse<String> safeGet(String url) {
    if (url == null) return null;
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(url))
          .timeout(PROBE_TIMEOUT)
          .GET()
          .build();
      // Belt-and-braces wall-clock cap: some JDK HttpClient versions honour
      // .timeout() only after headers arrive, so we also enforce the 2s
      // ceiling via CompletableFuture.
      return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
          .orTimeout(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
          .get();
    } catch (Exception e) {
      log.debug("safeGet({}) failed: {}", url, e.getMessage());
      return null;
    }
  }

  private String resolveTemplate(String tpl) {
    if (tpl == null) return null;
    String out = tpl;
    if (out.contains("{lan_ip}")) {
      String ip = system.lanIp();
      out = out.replace("{lan_ip}", ip == null ? "aurora.local" : ip);
    }
    if (out.contains("{domain}")) {
      RepoState state = stateFiles.readState();
      String domain = state.domain() == null ? "aurora.local" : state.domain();
      out = out.replace("{domain}", domain);
    }
    return out;
  }

  private static int ms(long startedNanos) {
    return (int) Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  /** In-memory cache entry. */
  static final class CachedProbe {
    final Instant stamp;
    final ProbeResult result;
    CachedProbe(Instant stamp, ProbeResult result) { this.stamp = stamp; this.result = result; }
  }

  /**
   * Result of one probe. Immutable value type. {@code reason}/{@code detail}
   * are human copy — never contain shell substrings.
   */
  public static final class ProbeResult {
    public final String pkg;
    public final String container;
    public final String state;
    public final String reason;
    public final String detail;
    public final String openUrl;
    public final int probedMs;

    private ProbeResult(String pkg, String container, String state, String reason,
                        String detail, String openUrl, int probedMs) {
      this.pkg = pkg;
      this.container = container;
      this.state = state;
      this.reason = reason;
      this.detail = detail;
      this.openUrl = openUrl;
      this.probedMs = probedMs;
    }

    static ProbeResult running(String pkg, String container, String openUrl, int ms) {
      return new ProbeResult(pkg, container, "running", null, null, openUrl, ms);
    }
    static ProbeResult needsConfig(String pkg, String container, String reason, String detail,
                                   String openUrl, int ms) {
      return new ProbeResult(pkg, container, "needs-config", reason, detail, openUrl, ms);
    }
    static ProbeResult notStarted(String pkg, String container, String openUrl, int ms) {
      return new ProbeResult(pkg, container, "not-started", "Not started yet",
          "Aurora hasn't brought this online yet.", openUrl, ms);
    }
    static ProbeResult starting(String pkg, String container, String openUrl, int ms) {
      return new ProbeResult(pkg, container, "starting", null, null, openUrl, ms);
    }
    static ProbeResult failed(String pkg, String container, String reason, String detail, int ms) {
      return new ProbeResult(pkg, container, "failed", reason, detail, null, ms);
    }

    Map<String, Object> toJson() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("package", pkg);
      m.put("container", container);
      m.put("state", state);
      m.put("reason", reason);
      m.put("detail", detail);
      m.put("open_url", openUrl);
      m.put("priority", STATE_PRIORITY.getOrDefault(state, 5));
      m.put("probed_ms", probedMs);
      return m;
    }
  }
}
