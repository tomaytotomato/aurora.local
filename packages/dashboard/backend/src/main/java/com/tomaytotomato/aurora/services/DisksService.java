package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.Disk;
import com.tomaytotomato.aurora.domain.DiskSmart;
import com.tomaytotomato.aurora.domain.Parity;
import com.tomaytotomato.aurora.domain.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Backs {@code /api/disks/*}. Reads the two JSON files
 * {@code host/roles/smartd} and {@code host/roles/snapraid} write, and
 * hands them to {@link DisksStateParser}.
 *
 * <p>Neither file existing is a normal, first-class state — a freshly
 * bootstrapped box with the roles not yet enabled, or one where the timer
 * simply hasn't fired yet — not an error. Every read here degrades to the
 * same honest defaults {@link DisksStateParser} produces for a missing
 * file, which is why the smartctl/snapraid binaries themselves never need
 * to be present for this class, or the container, to work.
 *
 * <p>The two SnapRAID actions are the one place this domain writes
 * anything, and they never touch the filesystem or a shell directly: both
 * go through {@link CommandRunner} against a single allow-listed host
 * helper with a fixed argv per action, exactly as {@code LaunchService}
 * and {@code UpdatesService} already do for {@code up.sh} and package
 * upgrades.
 */
@Service
public class DisksService {

  private static final Logger log = LoggerFactory.getLogger(DisksService.class);

  /** Matches the "3-day threshold" default called out in the design mocks. */
  private static final int DEFAULT_STALENESS_WARN_DAYS = 3;

  /**
   * The one binary the dashboard is allowed to invoke for parity actions.
   * Deployed by {@code host/roles/snapraid}; fixed argv per action, no
   * request data ever reaches it.
   */
  private static final String PARITY_HELPER = "/usr/local/bin/aurora-parity-action";

  private static final String DISKS_STATE_RELATIVE_PATH = "packages/dashboard/state/disks.json";
  private static final String PARITY_STATE_RELATIVE_PATH = "packages/dashboard/state/parity.json";

  private final AuroraProperties props;
  // Not Spring-managed: this codebase has no shared ObjectMapper bean
  // (nothing else in it needs one), and standing one up as a bean purely
  // for this one class would be a bigger change than reading two files.
  private final ObjectMapper mapper = new ObjectMapper();

  public DisksService(AuroraProperties props) {
    this.props = props;
  }

  public List<Disk> list() {
    return DisksStateParser.parseDisks(readDisksState());
  }

  public Pool pool() {
    JsonNode root = readDisksState();
    return DisksStateParser.parsePool(root, DisksStateParser.parseDisks(root));
  }

  public Parity parity() {
    JsonNode disksRoot = readDisksState();
    List<Disk> disks = DisksStateParser.parseDisks(disksRoot);
    JsonNode parityRoot = readParityState();
    return DisksStateParser.parseParity(parityRoot, disks, DEFAULT_STALENESS_WARN_DAYS);
  }

  /**
   * Empty when {@code id} names no known disk (404, per the mock
   * handlers). A known disk with no SMART support still returns a value —
   * {@code supported: false}, empty attributes — because "no data" and
   * "doesn't exist" are different honest answers.
   */
  public Optional<DiskSmart> smart(String id) {
    JsonNode root = readDisksState();
    if (root == null) return Optional.empty();
    String collectedAt = textOrNull(root.path("collectedAt"));
    for (JsonNode d : root.path("disks")) {
      if (id.equals(textOrNull(d.path("id")))) {
        return Optional.of(DisksStateParser.parseSmart(d, id, collectedAt));
      }
    }
    return Optional.empty();
  }

  private static String textOrNull(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
  }

  public List<String> syncArgv() {
    return List.of(PARITY_HELPER, "sync");
  }

  public List<String> scrubArgv() {
    return List.of(PARITY_HELPER, "scrub");
  }

  // ------------------------------------------------------------------
  // File IO
  // ------------------------------------------------------------------

  private JsonNode readDisksState() {
    return readJson(DISKS_STATE_RELATIVE_PATH);
  }

  private JsonNode readParityState() {
    return readJson(PARITY_STATE_RELATIVE_PATH);
  }

  private JsonNode readJson(String relativePath) {
    Path p = Path.of(props.repoPath()).resolve(relativePath);
    if (!Files.isRegularFile(p)) {
      return null;
    }
    try {
      return mapper.readTree(p.toFile());
    } catch (IOException e) {
      // Malformed state file — an operator mid-edit, a truncated write
      // from a crashed collector — is not a reason to 500 the disks page.
      log.warn("could not parse {}: {}", p, e.getMessage());
      return null;
    }
  }
}
