package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "start over" button.
 *
 * <p>The doctrine (see {@code ESSENCE.md}) says the terminal must not
 * appear anywhere in the operator's journey. {@code scripts/reset.sh}
 * (shipped for A6) takes the box back to a clean clone — but only from a
 * terminal, which is exactly the affordance the button exists to remove.
 * A8 closes that gap.
 *
 * <p><b>Why this needs a helper container instead of running in-process.</b>
 * The reset script does three things that Aurora itself cannot do to
 * itself: {@code docker rm -f} every container labelled with the
 * {@code aurora} compose project (which includes {@code aurora}, i.e. the
 * process reading this comment), delete {@code data/} (which usually
 * contains root-owned bind-mount subdirs left by other services), and
 * remove volumes. So we spawn a small helper container that:
 *
 * <ol>
 *   <li>Reuses the aurora image (already on the box, has {@code bash} +
 *       {@code docker-cli} + the repo mounted at its host path), so we do
 *       not have to pull anything or invent a second image.</li>
 *   <li>Runs as UID 0 so it can delete root-owned {@code data/} subtrees
 *       without needing sudo on the host.</li>
 *   <li>Runs detached ({@code -d}) and outside the {@code aurora} compose
 *       project (no matching label), so when the helper starts calling
 *       {@code docker rm -f} on aurora's containers, it is not deleting
 *       itself.</li>
 *   <li>Sleeps briefly before starting, so the HTTP response returns and
 *       the audit event flushes before this box's docker containers vanish
 *       out from under it.</li>
 * </ol>
 *
 * <p>The helper leaves the OS, docker itself, ufw, and the repo checkout
 * alone — same contract as {@code scripts/reset.sh}. The dashboard is
 * gone, so the operator has to re-run {@code bash bootstrap.sh install}
 * from a terminal to bring Aurora back. That last step is unavoidable
 * (Aurora does not run when Aurora is gone), and is spelled out in the UI
 * before the confirmation, not after.
 *
 * <p><b>Confirmation is compulsory.</b> The controller requires the caller
 * to send the literal string {@code "RESET"} in the request body — the
 * same word {@code scripts/reset.sh} asks for when it is run
 * interactively. A single-button reset would be a footgun.
 */
@Service
public class ResetService {

  private static final Logger log = LoggerFactory.getLogger(ResetService.class);

  /**
   * The word the operator must type to confirm. Matches the prompt in
   * {@code scripts/reset.sh} so anyone who has run it from a terminal
   * sees the same word here.
   */
  public static final String CONFIRM_TOKEN = "RESET";

  private final AuroraProperties props;
  private final CommandRunner commands;
  private final AuditEventRepo audit;
  private final AuroraImage image;

  @Autowired
  public ResetService(AuroraProperties props,
                      CommandRunner commands,
                      AuditEventRepo audit) {
    this(props, commands, audit, () -> defaultAuroraImage());
  }

  /** Constructor for tests: image lookup can be stubbed without env poking. */
  public ResetService(AuroraProperties props,
                      CommandRunner commands,
                      AuditEventRepo audit,
                      AuroraImage image) {
    this.props = props;
    this.commands = commands;
    this.audit = audit;
    this.image = image;
  }

  /**
   * Kick off the reset. Records an audit event, then spawns a detached
   * helper container that will do the destructive work after a short
   * delay. Returns as soon as {@code docker run -d} has printed the
   * container id.
   *
   * @param actingUserId who asked for it, for the audit row
   * @return the helper container id (short form is fine; useful for logs)
   * @throws ResetHelperFailedException if the helper container could not
   *     be started at all. Nothing has been destroyed at that point.
   */
  public String start(Long actingUserId) {
    String repoHostPath = props.repoPath();
    if (repoHostPath == null || repoHostPath.isBlank()) {
      throw new ResetHelperFailedException(
          "aurora does not know its own repo path; cannot reset from here");
    }

    audit.record(actingUserId, "reset.start", "box",
        "{\"confirm\":\"" + CONFIRM_TOKEN + "\"}");

    // The command the helper runs. Kept as a single -c string because it
    // is easier to reason about than a chain of docker args:
    //
    //   * sleep 5s so the HTTP response returns and the audit row hits
    //     the DB before aurora's own container is removed;
    //   * exec bash scripts/reset.sh --yes as UID 0 (which the helper is
    //     running as), so data/ deletion succeeds without sudo.
    //
    // The helper is `--rm`, so if the script exits cleanly the container
    // vanishes; if it fails, it stays around and `docker logs` on the
    // helper name is the debug surface.
    String helperName = "aurora-reset-" + shortId();
    String helperImage = image.reference();

    List<String> argv = List.of(
        "docker", "run",
        "-d",
        "--rm",
        "--name", helperName,
        "--user", "0:0",
        // Labels so an operator can find the helper afterwards, and so
        // reset.sh's own label filter (com.docker.compose.project=aurora)
        // does not match — the helper would be self-deleting otherwise.
        "--label", "aurora.role=reset-helper",
        // Same identity mount aurora uses: repo at its host path, so the
        // helper's `docker` calls resolve compose paths the same way.
        "-v", repoHostPath + ":" + repoHostPath + ":rw",
        "-v", "/var/run/docker.sock:/var/run/docker.sock:rw",
        "-w", repoHostPath,
        "--entrypoint", "bash",
        helperImage,
        "-c",
        // NB: the sleep is deliberately after the audit row is written
        // (that already happened above) and before any destructive work.
        // 6 seconds is enough for the pending HTTP response + one SSE
        // fan-out cycle to finish.
        "sleep 6 && exec bash scripts/reset.sh --yes"
    );

    StringBuilder out = new StringBuilder();
    int exit;
    try {
      exit = commands.stream(null, Map.of(), argv, line -> {
        if (out.length() > 0) out.append('\n');
        out.append(line);
      });
    } catch (Exception e) {
      log.error("could not start the reset helper container", e);
      throw new ResetHelperFailedException(
          "could not start the reset helper: " + e.getMessage());
    }
    if (exit != 0) {
      log.error("reset helper docker run exited {}: {}", exit, out);
      throw new ResetHelperFailedException(
          "docker refused to start the reset helper (exit " + exit + ")");
    }

    String containerId = out.toString().trim();
    log.warn("reset helper started as {} (id={}); this box will be wiped in ~6s",
        helperName, containerId);
    return containerId.isEmpty() ? helperName : containerId;
  }

  /** Random short suffix for the helper container name; not security-sensitive. */
  private static String shortId() {
    return Long.toString(System.nanoTime(), 36)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]", "")
        .substring(0, 8);
  }

  /** The aurora image reference. Same rule NetworkMountService uses. */
  static String defaultAuroraImage() {
    String v = System.getenv("AURORA_BUILD_VERSION");
    return "ghcr.io/tomaytotomato/aurora:" + (v == null || v.isBlank() ? "0.1.0" : v);
  }

  /** Seam so tests can pin the image without touching env vars. */
  @FunctionalInterface
  public interface AuroraImage {
    String reference();
  }

  /** Something went wrong before anything was destroyed. */
  public static class ResetHelperFailedException extends RuntimeException {
    public ResetHelperFailedException(String message) { super(message); }
  }
}
