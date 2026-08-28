package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.config.MarketplaceProperties;
import com.tomaytotomato.aurora.domain.MarketplaceApp;
import com.tomaytotomato.aurora.domain.MarketplaceImage;
import com.tomaytotomato.aurora.domain.MarketplaceIndex;
import com.tomaytotomato.aurora.domain.MarketplaceStatus;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fetches, verifies, caches and serves the marketplace catalogue index.
 *
 * <p>See {@code docs/MARKETPLACE_HOSTING_PLAN.md}. The invariants this
 * service enforces, in order of how load-bearing they are:
 *
 * <ol>
 *   <li><b>Nothing unverified is ever active.</b> Every index — the one
 *       shipped in the build, the one cached on disk, the one fetched from
 *       GitHub — is checked against the pinned Ed25519 public key before
 *       it is parsed into a {@link MarketplaceIndex} the rest of the app
 *       can see. A blob whose signature does not verify is dropped, and
 *       the box keeps rendering the last good catalogue.</li>
 *   <li><b>A new catalogue is never auto-applied (plan point 6).</b> A
 *       fetch that finds a newer verified index stages it as
 *       {@code available}; it becomes {@code active} only when the
 *       operator accepts it via {@link #accept}.</li>
 *   <li><b>Updating the catalogue never touches running apps (plan point
 *       7).</b> This service writes exactly one file —
 *       {@code data/marketplace/index.json} — and one settings row. It
 *       does not call the lifecycle service, rewrite any {@code .env}, or
 *       pull any image.</li>
 *   <li><b>Offline is not an outage.</b> The build ships a seed index;
 *       the cache survives restarts; a failed fetch is logged and
 *       surfaced, never fatal.</li>
 * </ol>
 */
@Service
public class MarketplaceCatalogService {

  private static final Logger log = LoggerFactory.getLogger(MarketplaceCatalogService.class);

  /** Settings key holding the operator's currently-accepted index version. */
  static final String ACCEPTED_VERSION_KEY = "marketplace.accepted_version";

  private static final String SEED_INDEX = "marketplace/index.seed.json";
  private static final String SEED_SIG = "marketplace/index.seed.json.sig";
  private static final String PINNED_KEY = "marketplace/marketplace-pub.ed25519.spki.b64";

  private final AuroraProperties props;
  private final MarketplaceProperties config;
  private final SettingsRepo settings;
  private final AuditEventRepo audit;
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final PublicKey pinnedKey;

  // In-memory state. Guarded by `this`.
  private MarketplaceIndex active;
  private boolean activeSignatureValid;
  private String activeSource;          // "seed" | "cache" | "fetch"
  private MarketplaceIndex available;   // verified, newer, not yet accepted
  private String lastFetchedAt;
  private String lastFetchError;

  public MarketplaceCatalogService(AuroraProperties props, MarketplaceProperties config,
                                   SettingsRepo settings, AuditEventRepo audit) {
    this.props = props;
    this.config = config;
    this.settings = settings;
    this.audit = audit;
    this.mapper = new ObjectMapper();
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    this.pinnedKey = loadPinnedKey();
  }

  // ─── boot ──────────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    try {
      loadActiveFromDisk();
    } catch (Exception e) {
      log.warn("marketplace: failed to load catalogue at startup: {}", e.getMessage());
    }
    if (config.enabled() && config.fetchOnStartup()) {
      // Fire-and-forget: never block boot on a network call.
      Thread.ofVirtual().name("marketplace-startup-fetch").start(() -> {
        try {
          refresh();
        } catch (Exception e) {
          log.debug("marketplace: startup fetch failed: {}", e.getMessage());
        }
      });
    }
  }

  /**
   * Load the active catalogue: prefer the on-disk cache, fall back to the
   * seed shipped in the build. Both are signature-verified before use.
   */
  synchronized void loadActiveFromDisk() {
    Optional<Verified> cached = readCache();
    if (cached.isPresent()) {
      active = cached.get().index();
      activeSignatureValid = true;
      activeSource = "cache";
      log.info("marketplace: loaded cached catalogue {} ({} apps)",
          active.indexVersion(), active.apps().size());
      return;
    }
    Optional<Verified> seed = readSeed();
    if (seed.isPresent()) {
      active = seed.get().index();
      activeSignatureValid = true;
      activeSource = "seed";
      log.info("marketplace: loaded seed catalogue {} ({} apps)",
          active.indexVersion(), active.apps().size());
      return;
    }
    log.warn("marketplace: no verifiable catalogue available (seed missing or invalid)");
    active = null;
    activeSignatureValid = false;
    activeSource = null;
  }

  // ─── fetch / refresh ───────────────────────────────────────────────

  /**
   * Fetch the remote index + signature, verify, and stage it as
   * {@code available} when it is newer than the active one. Returns the
   * resulting status. No-op (returns current status) when the feature is
   * disabled or no {@code indexUrl} is configured.
   */
  public synchronized MarketplaceStatus refresh() {
    if (!config.enabled() || config.indexUrl() == null || config.indexUrl().isBlank()) {
      return status();
    }
    String base = config.indexUrl();
    try {
      byte[] blob = getBytes(base);
      byte[] sig = getBytes(base + ".sig");
      if (!verify(blob, sig)) {
        lastFetchError = "signature verification failed";
        lastFetchedAt = now();
        log.warn("marketplace: fetched index failed signature verification; keeping {}",
            active == null ? "nothing" : active.indexVersion());
        return status();
      }
      MarketplaceIndex fetched = parse(blob);
      lastFetchedAt = now();
      lastFetchError = null;

      String activeVer = active == null ? null : active.indexVersion();
      if (fetched.indexVersion().equals(activeVer)) {
        available = null; // already on it
        log.debug("marketplace: fetched catalogue matches active {}", activeVer);
      } else if (isAccepted(fetched.indexVersion())) {
        // Operator previously accepted this exact version; promote silently.
        promote(fetched, blob, sig, false);
      } else {
        available = fetched;
        log.info("marketplace: newer catalogue {} available (active {})",
            fetched.indexVersion(), activeVer);
      }
      return status();
    } catch (Exception e) {
      lastFetchError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      lastFetchedAt = now();
      log.warn("marketplace: fetch failed: {}", lastFetchError);
      return status();
    }
  }

  @Scheduled(cron = "${aurora.marketplace.fetch-cron:0 14 3 * * *}")
  public void scheduledRefresh() {
    if (config.enabled()) {
      log.debug("marketplace: scheduled refresh");
      refresh();
    }
  }

  /**
   * Accept the pending {@code available} catalogue, making it active and
   * persisting it to the cache. Records the accepted version so a future
   * fetch of the same version promotes silently. Throws
   * {@link IllegalStateException} when there is nothing to accept.
   */
  public synchronized MarketplaceStatus accept(Long userId) {
    if (available == null) {
      throw new IllegalStateException("no marketplace update available to accept");
    }
    // Re-fetch the exact bytes so the cached file is byte-identical to what
    // we verified — we never persist a re-serialisation of a parsed index.
    try {
      String base = config.indexUrl();
      byte[] blob = getBytes(base);
      byte[] sig = getBytes(base + ".sig");
      if (!verify(blob, sig)) {
        throw new IllegalStateException("signature verification failed on accept");
      }
      MarketplaceIndex reparsed = parse(blob);
      if (!reparsed.indexVersion().equals(available.indexVersion())) {
        // The remote moved under us; re-stage and make the operator look again.
        available = reparsed;
        throw new IllegalStateException("catalogue changed during accept; review the new version");
      }
      promote(reparsed, blob, sig, true);
      audit.record(userId, "marketplace.accept", "data/marketplace/index.json",
          "{\"version\":\"" + reparsed.indexVersion() + "\"}");
      return status();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("could not fetch catalogue to accept: " + e.getMessage(), e);
    }
  }

  private void promote(MarketplaceIndex index, byte[] blob, byte[] sig, boolean recordAccept) {
    writeCache(blob, sig);
    active = index;
    activeSignatureValid = true;
    activeSource = "fetch";
    available = null;
    if (recordAccept) {
      settings.put(ACCEPTED_VERSION_KEY, index.indexVersion());
    }
    log.info("marketplace: catalogue now active at {} ({} apps)",
        index.indexVersion(), index.apps().size());
  }

  // ─── read surface ──────────────────────────────────────────────────

  /** Summary projection of the active catalogue (no embedded bodies). */
  public synchronized List<MarketplaceApp> apps() {
    if (active == null) return List.of();
    return active.apps().stream().map(MarketplaceApp::toSummary).collect(Collectors.toList());
  }

  /** One app with its embedded compose / env / readme, or empty. */
  public synchronized Optional<MarketplaceApp> app(String slug) {
    if (active == null) return Optional.empty();
    return active.apps().stream().filter(a -> a.slug().equals(slug)).findFirst();
  }

  public synchronized MarketplaceStatus status() {
    String activeVer = active == null ? null : active.indexVersion();
    String activeGen = active == null ? null : active.generatedAt();
    int count = active == null ? 0 : active.apps().size();

    boolean updateAvailable = available != null;
    Integer availCount = available == null ? null : available.apps().size();
    Integer newApps = null;
    if (available != null && active != null) {
      Set<String> have = active.apps().stream().map(MarketplaceApp::slug).collect(Collectors.toSet());
      newApps = (int) available.apps().stream().map(MarketplaceApp::slug)
          .filter(s -> !have.contains(s)).count();
    } else if (available != null) {
      newApps = available.apps().size();
    }

    return new MarketplaceStatus(
        config.enabled(),
        activeVer,
        activeGen,
        count,
        activeSignatureValid,
        activeSource,
        lastFetchedAt,
        lastFetchError,
        updateAvailable,
        available == null ? null : available.indexVersion(),
        available == null ? null : available.generatedAt(),
        availCount,
        newApps
    );
  }

  // ─── crypto ────────────────────────────────────────────────────────

  /**
   * Verify a detached base64 Ed25519 signature over the exact bytes of
   * the index blob. Returns false on any failure (bad key, bad base64,
   * bad signature) rather than throwing — the caller treats "did not
   * verify" and "could not verify" identically: neither becomes active.
   */
  boolean verify(byte[] blob, byte[] sigB64) {
    if (pinnedKey == null) return false;
    try {
      byte[] sig = Base64.getDecoder().decode(new String(sigB64, StandardCharsets.UTF_8).trim());
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(pinnedKey);
      verifier.update(blob);
      return verifier.verify(sig);
    } catch (Exception e) {
      log.debug("marketplace: signature verify error: {}", e.getMessage());
      return false;
    }
  }

  private PublicKey loadPinnedKey() {
    try (InputStream in = new ClassPathResource(PINNED_KEY).getInputStream()) {
      String b64 = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      byte[] der = Base64.getDecoder().decode(b64);
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception e) {
      log.error("marketplace: could not load pinned public key ({}); "
          + "catalogue verification will fail closed", e.getMessage());
      return null;
    }
  }

  // ─── parse ─────────────────────────────────────────────────────────

  MarketplaceIndex parse(byte[] blob) throws IOException {
    JsonNode root = mapper.readTree(blob);
    int schemaVersion = root.path("schema_version").asInt(0);
    if (schemaVersion != 1) {
      throw new IOException("unsupported marketplace schema_version: " + schemaVersion);
    }
    List<MarketplaceApp> apps = new ArrayList<>();
    for (JsonNode a : root.path("apps")) {
      apps.add(parseApp(a));
    }
    return new MarketplaceIndex(
        schemaVersion,
        text(root, "index_version"),
        text(root, "generated_at"),
        text(root, "min_dashboard_version"),
        apps
    );
  }

  @SuppressWarnings("unchecked")
  private MarketplaceApp parseApp(JsonNode a) {
    List<MarketplaceImage> images = new ArrayList<>();
    for (JsonNode img : a.path("images")) {
      images.add(new MarketplaceImage(
          text(img, "ref"),
          img.hasNonNull("digest") ? img.get("digest").asText() : null));
    }
    java.util.Map<String, Object> requires = null;
    if (a.has("requires") && a.get("requires").isObject()) {
      requires = mapper.convertValue(a.get("requires"), java.util.Map.class);
    }
    return new MarketplaceApp(
        text(a, "slug"),
        text(a, "title"),
        text(a, "description"),
        text(a, "category"),
        text(a, "icon"),
        strList(a, "depends_on"),
        strList(a, "recommends"),
        text(a, "variant_group"),
        a.has("variant_default") ? a.get("variant_default").asBoolean() : null,
        text(a, "source_url"),
        text(a, "homepage_url"),
        requires,
        images,
        a.path("unpinned").asBoolean(false),
        text(a, "compose"),
        text(a, "env_example"),
        text(a, "caddy_snippet"),
        text(a, "readme")
    );
  }

  private static String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asText() : null;
  }

  private static List<String> strList(JsonNode n, String field) {
    if (!n.has(field) || !n.get(field).isArray()) return null;
    List<String> out = new ArrayList<>();
    n.get(field).forEach(e -> out.add(e.asText()));
    return out.isEmpty() ? null : out;
  }

  // ─── disk I/O ──────────────────────────────────────────────────────

  Path cacheDir() {
    return Path.of(props.repoPath(), "data", "marketplace");
  }

  Path cacheIndexPath() {
    return cacheDir().resolve("index.json");
  }

  Path cacheSigPath() {
    return cacheDir().resolve("index.json.sig");
  }

  private Optional<Verified> readCache() {
    Path idx = cacheIndexPath();
    Path sig = cacheSigPath();
    if (!Files.isRegularFile(idx) || !Files.isRegularFile(sig)) return Optional.empty();
    try {
      byte[] blob = Files.readAllBytes(idx);
      byte[] s = Files.readAllBytes(sig);
      if (!verify(blob, s)) {
        log.warn("marketplace: cached index failed signature verification; ignoring it");
        return Optional.empty();
      }
      return Optional.of(new Verified(parse(blob), blob, s));
    } catch (Exception e) {
      log.warn("marketplace: could not read cache: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private Optional<Verified> readSeed() {
    try (InputStream bin = new ClassPathResource(SEED_INDEX).getInputStream();
         InputStream sin = new ClassPathResource(SEED_SIG).getInputStream()) {
      byte[] blob = bin.readAllBytes();
      byte[] sig = sin.readAllBytes();
      if (!verify(blob, sig)) {
        log.warn("marketplace: seed index failed signature verification");
        return Optional.empty();
      }
      return Optional.of(new Verified(parse(blob), blob, sig));
    } catch (Exception e) {
      log.warn("marketplace: could not read seed index: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private void writeCache(byte[] blob, byte[] sig) {
    try {
      Files.createDirectories(cacheDir());
      Files.write(cacheIndexPath(), blob,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      Files.write(cacheSigPath(), sig,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new RuntimeException("failed to write marketplace cache: " + e.getMessage(), e);
    }
  }

  // ─── http ──────────────────────────────────────────────────────────

  private byte[] getBytes(String url) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(20))
        .header("User-Agent", "aurora-dashboard")
        .GET()
        .build();
    HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() / 100 != 2) {
      throw new IOException("GET " + url + " -> HTTP " + resp.statusCode());
    }
    return resp.body();
  }

  // ─── misc ──────────────────────────────────────────────────────────

  private boolean isAccepted(String version) {
    return settings.get(ACCEPTED_VERSION_KEY).map(v -> v.equals(version)).orElse(false);
  }

  private static String now() {
    return Instant.now().toString();
  }

  private record Verified(MarketplaceIndex index, byte[] blob, byte[] sig) {}
}
