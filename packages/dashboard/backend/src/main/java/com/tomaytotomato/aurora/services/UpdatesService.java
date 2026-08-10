package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.security.UnpinnedImageTagsRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether each package has an update waiting.
 *
 * <p>Aurora has known how to update since {@code scripts/update.sh}
 * existed and never said whether there was anything to update, so the
 * only way to find out was to run it and watch.
 *
 * <p>Two decisions shape this:
 *
 * <p><b>Reads are cached; checking is a job.</b> Answering "is there an
 * update" honestly means asking a registry, which is slow and rate
 * limited. Doing that on every page load would be rude to Docker Hub and
 * would make the Apps page crawl. So {@code GET /updates} serves the last
 * known answer and {@code POST /updates/check} refreshes it, which is
 * also why the schema has {@code lastCheckedAt}.
 *
 * <p><b>Unknown is a first-class answer.</b> Before any check has run,
 * and whenever a registry cannot be reached, the state is {@code unknown}
 * rather than {@code current}. A green tick nobody earned is worse than
 * an admission, because it is the one that gets believed.
 */
@Service
public class UpdatesService {

  private static final Logger log = LoggerFactory.getLogger(UpdatesService.class);

  /** Long enough for a slow registry, short enough not to wedge a check. */
  private static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(20);


  private final ComposeScanner compose;
  private final CommandRunner commands;

  /** Last known answer per package. Empty until a check has run. */
  private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

  public UpdatesService(ComposeScanner compose, CommandRunner commands) {
    this.compose = compose;
    this.commands = commands;
  }

  // ------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------

  /** One row per package with a compose file, in catalogue order. */
  public List<Map<String, Object>> list() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (String pkg : compose.packageNames()) {
      out.add(forPackage(pkg));
    }
    return out;
  }

  public Optional<Map<String, Object>> find(String pkg) {
    if (!compose.packageNames().contains(pkg)) return Optional.empty();
    return Optional.of(forPackage(pkg));
  }

  /**
   * The cached answer, or an honest unchecked one built from the compose
   * file. The unchecked shape still carries the image list and whether
   * each is pinned, because those are facts about the repository that need
   * no registry at all.
   */
  private Map<String, Object> forPackage(String pkg) {
    Map<String, Object> cached = cache.get(pkg);
    if (cached != null) return cached;
    return unchecked(pkg);
  }

  private Map<String, Object> unchecked(String pkg) {
    List<Map<String, Object>> images = new ArrayList<>();
    for (String image : compose.imagesFor(pkg)) {
      images.add(imageRow(image, null, null, "unknown"));
    }
    return row(pkg, "unknown", images, null, null, null, false);
  }

  // ------------------------------------------------------------------
  // Checking
  // ------------------------------------------------------------------

  /**
   * Re-query every package against its registry, writing progress into the
   * job's log. Runs in whatever thread the caller gives it; the controller
   * hands it to {@link JobService}.
   */
  public void checkAll(JobService jobs, JobService.Job job) {
    List<String> packages = compose.packageNames();
    jobs.append(job, "Checking registries for newer images…");

    int behind = 0;
    for (String pkg : packages) {
      Map<String, Object> result = check(pkg);
      cache.put(pkg, result);
      String state = String.valueOf(result.get("state"));
      if ("available".equals(state)) behind++;
      jobs.append(job, " %-14s %s".formatted(pkg, describe(result)));
    }

    jobs.append(job, "Checked %d package%s, %d with updates waiting"
        .formatted(packages.size(), packages.size() == 1 ? "" : "s", behind));
  }

  /** Check one package now and cache the answer. */
  public Map<String, Object> check(String pkg) {
    List<Map<String, Object>> images = new ArrayList<>();
    boolean anyAvailable = false;
    boolean anyUnknown = false;

    for (String image : compose.imagesFor(pkg)) {
      String local = localDigest(image);
      String remote = remoteDigest(image);

      String state;
      if (remote == null || local == null) {
        // Could not ask, or could not tell what is installed. Either way we
        // do not know, and saying "current" would be a guess.
        state = "unknown";
        anyUnknown = true;
      } else if (remote.equals(local)) {
        state = "current";
      } else {
        state = "available";
        anyAvailable = true;
      }
      images.add(imageRow(image, local, remote, state));
    }

    String state = anyAvailable ? "available" : (anyUnknown || images.isEmpty() ? "unknown" : "current");
    Map<String, Object> previous = cache.get(pkg);
    return row(pkg, state, images, Instant.now().toString(),
        previous == null ? null : (String) previous.get("lastUpdatedAt"),
        previous == null ? null : (String) previous.get("lastUpdateJobId"),
        previous != null && Boolean.TRUE.equals(previous.get("lastUpdateFailed")));
  }

  /**
   * Record the outcome of an update so the card can say "the last attempt
   * failed" rather than silently offering the button again.
   */
  public void recordUpdateOutcome(String pkg, String jobId, boolean failed) {
    Map<String, Object> current = new LinkedHashMap<>(forPackage(pkg));
    current.put("lastUpdateJobId", jobId);
    current.put("lastUpdateFailed", failed);
    if (!failed) {
      current.put("lastUpdatedAt", Instant.now().toString());
      // A successful update means whatever was waiting has landed. The next
      // check will confirm; until then, claiming an update is still
      // available would be stale rather than cautious.
      current.put("state", "unknown");
    }
    cache.put(pkg, current);
  }

  // ------------------------------------------------------------------
  // Registry and daemon
  // ------------------------------------------------------------------

  /**
   * The digest of the image as it exists on this box. Null when the image
   * has never been pulled, or docker cannot be reached.
   */
  private String localDigest(String image) {
    var result = commands.run(null, REGISTRY_TIMEOUT, Map.of(),
        List.of("docker", "image", "inspect", image, "--format", "{{index .RepoDigests 0}}"));
    if (!result.ok()) return null;
    return extractDigest(result.firstLine());
  }

  /**
   * The digest the registry currently serves for this reference. Asks
   * without pulling: a check that downloaded gigabytes would be a rather
   * antisocial way to answer "is there an update".
   *
   * <p>Uses {@code buildx imagetools inspect} rather than the more obvious
   * {@code docker manifest inspect --verbose}, and the reason matters.
   * Nearly every image in the catalogue is multi-arch, so its tag points
   * at a manifest <em>list</em>. {@code manifest inspect --verbose} on a
   * list returns an array of the per-platform manifests, each with its own
   * {@code digest} — none of which is the list digest. Meanwhile
   * {@code RepoDigests} on a pulled image holds the list digest. Comparing
   * the two would never match, and every multi-arch image would report an
   * update forever.
   *
   * <p>{@code imagetools inspect --format '{{.Manifest.Digest}}'} returns
   * the top-level descriptor digest, which is the one that is comparable.
   *
   * <p>Still unverified against a live registry — there was no network on
   * the machine where this was written. {@code UpdatesRegistryContractTest}
   * is the tagged test that closes it, and until that has run somewhere
   * with a registry this method should be treated as informed but
   * unproven.
   */
  private String remoteDigest(String image) {
    var result = commands.run(null, REGISTRY_TIMEOUT, Map.of(),
        List.of("docker", "buildx", "imagetools", "inspect", "--format",
            "{{.Manifest.Digest}}", image));
    if (!result.ok()) {
      log.debug("imagetools inspect failed for {}: exit {}", image, result.exitCode());
      return null;
    }
    // Anything that is not a bare digest means the command answered in a
    // shape we did not expect, and "unknown" is the honest response to
    // that rather than a comparison against a guess.
    return extractDigest(result.firstLine());
  }

  /** Pull a bare digest out of either a digest string or a full reference. */
  public static String extractDigest(String raw) {
    if (raw == null || raw.isBlank()) return null;
    Matcher m = Pattern.compile("(sha256:[0-9a-f]{64})").matcher(raw);
    return m.find() ? m.group(1) : null;
  }

  // ------------------------------------------------------------------
  // Shapes
  // ------------------------------------------------------------------

  private static Map<String, Object> imageRow(String image, String localDigest,
                                              String remoteDigest, String state) {
    String repository = image;
    String tag = "";
    // Strip any digest suffix first so a pinned reference does not have its
    // tag confused with part of the digest.
    int at = image.indexOf('@');
    String withoutDigest = at >= 0 ? image.substring(0, at) : image;
    int colon = withoutDigest.lastIndexOf(':');
    int slash = withoutDigest.lastIndexOf('/');
    if (colon > slash) {
      repository = withoutDigest.substring(0, colon);
      tag = withoutDigest.substring(colon + 1);
    } else {
      repository = withoutDigest;
      tag = "latest";
    }

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("image", repository);
    m.put("currentTag", tag);
    m.put("currentDigest", localDigest);
    // Aurora does not resolve what tag a newer digest would carry — that
    // needs a tag listing per registry — so the tag is reported unchanged
    // and the digest carries the difference. The frontend renders that as
    // "new build", which is exactly what happened.
    m.put("latestTag", remoteDigest == null ? null : tag);
    m.put("latestDigest", remoteDigest);
    m.put("pinned",
        UnpinnedImageTagsRule.classify(image) == UnpinnedImageTagsRule.Verdict.PINNED);
    m.put("state", state);
    return m;
  }

  private static Map<String, Object> row(String pkg, String state,
                                         List<Map<String, Object>> images,
                                         String lastCheckedAt, String lastUpdatedAt,
                                         String lastUpdateJobId, boolean lastUpdateFailed) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("package", pkg);
    m.put("state", state);
    m.put("images", images);
    m.put("lastCheckedAt", lastCheckedAt);
    m.put("lastUpdatedAt", lastUpdatedAt);
    m.put("lastUpdateJobId", lastUpdateJobId);
    m.put("lastUpdateFailed", lastUpdateFailed);
    return m;
  }

  private static String describe(Map<String, Object> row) {
    String state = String.valueOf(row.get("state"));
    return switch (state) {
      case "available" -> "update available";
      case "current" -> "up to date";
      default -> "could not check";
    };
  }
}
