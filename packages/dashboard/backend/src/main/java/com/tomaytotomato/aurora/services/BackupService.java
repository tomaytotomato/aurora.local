package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.domain.BackupAction;
import com.tomaytotomato.aurora.domain.BackupSource;
import com.tomaytotomato.aurora.domain.BackupStatus;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.PackageBackupSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Answers one question Kopia's own UI answers badly: is my data safe, and
 * how do I get it back?
 *
 * <p>Aurora has shipped Kopia since early on and said nothing about it in
 * the dashboard, so checking meant logging into a second web UI on port
 * 51515. Nobody does, and a backup nobody looks at is one that quietly
 * stopped working in March. See {@code docs/BACKUP_PAGE_DESIGN.md}.
 *
 * <p><b>How it talks to Kopia.</b> Via the {@code kopia} CLI inside the
 * running container ({@code docker exec kopia kopia … --json}), not
 * Kopia's HTTP server API. That is now the standing rule for every
 * packaged service that ships both — see {@code docs/ARCHITECTURE.md} §4
 * for the reasoning (credentials stay inside their own package, one
 * failure vocabulary, one thing to fake in tests). The backup design doc's
 * aside about "the server API" predates the decision and is a description
 * of intent, not a constraint.
 *
 * <p><b>What it will not do.</b> Invent numbers. A repository Aurora
 * cannot reach has null sizes rather than zeroes, and a declared path
 * Kopia has never snapshotted has a null timestamp rather than an
 * encouraging one.
 */
@Service
public class BackupService {

  private static final Logger log = LoggerFactory.getLogger(BackupService.class);

  /** Kopia's container name, from packages/backup/compose.yml. */
  static final String KOPIA_CONTAINER = "kopia";

  /**
   * Kopia is read-only over these calls but still walks a repository, so
   * it is given longer than a status probe would get.
   */
  private static final Duration KOPIA_TIMEOUT = Duration.ofSeconds(30);

  /**
   * The prefix Kopia sees for Aurora's data, from the read-only mount in
   * packages/backup/compose.yml. Snapshot source paths come back
   * container-absolute and have to be mapped home to compare against what
   * a manifest declares.
   */
  private static final String PROTECTED_PREFIX = "/protected/";

  /**
   * Images whose data directory cannot be copied while running and be
   * worth having afterwards. Deliberately not exhaustive and deliberately
   * excludes Redis: a Redis RDB is a point-in-time file by construction,
   * whereas a Postgres or MySQL data directory copied live is a corrupted
   * file with a timestamp on it.
   */
  private static final List<String> DATABASE_IMAGE_HINTS =
      List.of("postgres", "pgvecto", "mysql", "mariadb", "mongo");

  /** The dump kinds that mitigate {@link #DATABASE_IMAGE_HINTS}. */
  private static final List<String> DUMP_KINDS =
      List.of("postgres-dump", "mysql-dump", "sqlite-backup");

  private final CommandRunner commands;
  private final PackagesService packages;
  private final ComposeScanner compose;
  private final ObjectMapper mapper = new ObjectMapper();

  public BackupService(CommandRunner commands, PackagesService packages, ComposeScanner compose) {
    this.commands = commands;
    this.packages = packages;
    this.compose = compose;
  }

  // ------------------------------------------------------------------
  // Status
  // ------------------------------------------------------------------

  public BackupStatus status() {
    String now = Instant.now().toString();
    Optional<JsonNode> repo = repositoryStatus();

    if (repo.isEmpty()) {
      // Which of the two "no repository" states this is matters: a
      // repository that was never created is a first-run explanation and a
      // link into Kopia, while one that exists and cannot be opened is an
      // alarm. Collapsing them would send an operator hunting for a setup
      // screen when the real answer is "your USB disk fell out".
      String state = lastRepositoryError.contains("not connected")
          || lastRepositoryError.contains("not initialized")
          || lastRepositoryError.contains("repository is not")
          ? BackupStatus.NOT_CONFIGURED
          : BackupStatus.UNREACHABLE;
      return new BackupStatus(state, null, null, false,
          null, null, 0, null, null, null, null, now);
    }

    JsonNode r = repo.get();
    List<JsonNode> snapshots = snapshotList();
    JsonNode latest = snapshots.stream()
        .max((a, b) -> compareInstants(text(a, "endTime"), text(b, "endTime")))
        .orElse(null);

    return new BackupStatus(
        BackupStatus.CONNECTED,
        text(r, "storage"),
        r.path("storageConfig").path("path").isMissingNode()
            ? null : r.path("storageConfig").path("path").asText(null),
        isEncrypted(r),
        longOrNull(r, "totalSize"),
        longOrNull(r, "uniqueSize"),
        snapshots.size(),
        latest == null ? null : text(latest, "endTime"),
        latest == null ? null : snapshotState(latest),
        latest == null ? null : durationMs(text(latest, "startTime"), text(latest, "endTime")),
        // Not implemented: Aurora does not own the schedule yet, so there
        // is no honest answer to "when is the next run". Null says that.
        null,
        now);
  }

  // ------------------------------------------------------------------
  // Sources
  // ------------------------------------------------------------------

  public List<BackupSource> sources() {
    Map<String, JsonNode> byPath = new LinkedHashMap<>();
    for (JsonNode snap : snapshotList()) {
      String path = repoRelative(snap.path("source").path("path").asText(""));
      if (path.isBlank()) continue;
      JsonNode existing = byPath.get(path);
      if (existing == null
          || compareInstants(text(snap, "endTime"), text(existing, "endTime")) > 0) {
        byPath.put(path, snap);
      }
    }

    List<BackupSource> out = new ArrayList<>();
    for (Package pkg : packages.list()) {
      if (!pkg.enabled()) continue;
      PackageBackupSpec spec = PackageBackupSpec.fromManifest(readBackupBlock(pkg.name()));
      if (spec == null) continue;

      boolean hasDatabase = declaresDatabase(pkg.name());
      boolean hasDump = spec.before().stream()
          .anyMatch(a -> a.kind() != null && DUMP_KINDS.contains(a.kind()));

      for (String path : spec.paths()) {
        JsonNode snap = byPath.get(path);
        out.add(new BackupSource(
            pkg.name() + ":" + path,
            path,
            pkg.name(),
            true,
            snap == null ? null : text(snap, "endTime"),
            snap == null ? null : snapshotState(snap),
            snap == null ? null : longOrNull(snap.path("stats"), "totalSize"),
            snap == null ? null : intOrNull(snap.path("stats"), "fileCount"),
            spec.before(),
            hasDatabase && !hasDump));
      }
    }
    return out;
  }

  /**
   * Whether this package runs a database whose files cannot be copied
   * live. Read from the images its compose file declares, which is the
   * only place that fact is written down.
   */
  private boolean declaresDatabase(String pkg) {
    return compose.imagesFor(pkg).stream()
        .map(i -> i.toLowerCase(Locale.ROOT))
        .anyMatch(i -> DATABASE_IMAGE_HINTS.stream().anyMatch(i::contains));
  }

  private Object readBackupBlock(String pkg) {
    return packages.readManifestBlockFor(pkg, "backup");
  }

  // ------------------------------------------------------------------
  // Kopia
  // ------------------------------------------------------------------

  /** Message from the last failed repository call, for state classification. */
  private volatile String lastRepositoryError = "";

  private Optional<JsonNode> repositoryStatus() {
    var result = commands.run(null, KOPIA_TIMEOUT, Map.of(),
        List.of("docker", "exec", KOPIA_CONTAINER, "kopia", "repository", "status", "--json"));
    if (!result.ok()) {
      lastRepositoryError = String.join(" ", result.lines()).toLowerCase(Locale.ROOT);
      log.debug("kopia repository status failed (exit={}): {}", result.exitCode(), lastRepositoryError);
      return Optional.empty();
    }
    lastRepositoryError = "";
    return parse(String.join("\n", result.lines()));
  }

  private List<JsonNode> snapshotList() {
    var result = commands.run(null, KOPIA_TIMEOUT, Map.of(),
        List.of("docker", "exec", KOPIA_CONTAINER, "kopia", "snapshot", "list", "--json", "--all"));
    if (!result.ok()) {
      log.debug("kopia snapshot list failed (exit={})", result.exitCode());
      return List.of();
    }
    return parse(String.join("\n", result.lines()))
        .filter(JsonNode::isArray)
        .map(a -> {
          List<JsonNode> list = new ArrayList<>();
          a.forEach(list::add);
          return list;
        })
        .orElse(List.of());
  }

  private Optional<JsonNode> parse(String body) {
    if (body == null || body.isBlank()) return Optional.empty();
    try {
      return Optional.of(mapper.readTree(body));
    } catch (Exception e) {
      log.warn("could not parse kopia output: {}", e.getMessage());
      return Optional.empty();
    }
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  /**
   * Kopia's {@code incomplete} field is the only thing that distinguishes
   * a snapshot that finished from one that gave up part-way. Empty means
   * complete; anything else is a partial. Kopia does not keep a failed
   * snapshot at all — a run that fails outright leaves nothing behind —
   * so {@code failed} is reserved for the job log, not inferred here.
   */
  private static String snapshotState(JsonNode snap) {
    String incomplete = snap.path("incomplete").asText("");
    return incomplete.isBlank() ? "ok" : "partial";
  }

  private static boolean isEncrypted(JsonNode repo) {
    String algorithm = repo.path("encryption").path("algorithm").asText("");
    return !algorithm.isBlank() && !"NONE".equalsIgnoreCase(algorithm);
  }

  /** Strips the container-side mount prefix so paths match the manifests. */
  private static String repoRelative(String containerPath) {
    if (containerPath == null) return "";
    return containerPath.startsWith(PROTECTED_PREFIX)
        ? containerPath.substring(PROTECTED_PREFIX.length())
        : containerPath;
  }

  private static int compareInstants(String a, String b) {
    Instant ia = instantOrNull(a);
    Instant ib = instantOrNull(b);
    if (ia == null && ib == null) return 0;
    if (ia == null) return -1;
    if (ib == null) return 1;
    return ia.compareTo(ib);
  }

  private static Instant instantOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Instant.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Long durationMs(String start, String end) {
    Instant s = instantOrNull(start);
    Instant e = instantOrNull(end);
    if (s == null || e == null) return null;
    return Duration.between(s, e).toMillis();
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
  }

  private static Long longOrNull(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asLong();
  }

  private static Integer intOrNull(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asInt();
  }
}
