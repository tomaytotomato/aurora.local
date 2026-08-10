package com.tomaytotomato.aurora.services;

import java.util.regex.Pattern;

/**
 * Turns a failed command's output into human copy plus a machine-readable
 * code.
 *
 * <p>Extracted from {@link LaunchService}, which has done this for launch
 * failures since iter-3, because every job kind needs it now: an update
 * that hits a registry rate limit, a restore that runs out of disk and a
 * parity sync that cannot reach the daemon all fail in exactly the same
 * handful of ways. {@code LaunchService.classify} delegates here so its
 * existing suite keeps pinning the behaviour.
 *
 * <p>Two rules that the tests enforce and that matter more than the
 * pattern list:
 *
 * <ul>
 *   <li><b>Reason strings are user copy.</b> No {@code sudo}, no
 *       {@code docker}, no {@code bash}, no script paths. Someone reading
 *       this has a broken box, not a terminal open.</li>
 *   <li><b>No match falls through to {@code unknown}.</b> Guessing a
 *       cause is worse than admitting we do not know, because the
 *       operator then spends an evening chasing the wrong thing.</li>
 * </ul>
 */
public final class JobFailureClassifier {

  private JobFailureClassifier() {
  }

  /**
   * @param reason one sentence, human, safe to show on its own
   * @param code   stable identifier the frontend maps to its own copy
   */
  public record Classified(String reason, String code) {
  }

  private static final Pattern PORT_RE =
      Pattern.compile(":(\\d{2,5})[ :\\\"']|port (\\d{2,5})\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern CONTAINER_RE =
      Pattern.compile("Container ([\\w.-]+)", Pattern.CASE_INSENSITIVE);

  /**
   * @param tail       recent output, typically the last couple of hundred lines
   * @param exitCode   process exit status, kept for callers that log it
   * @param subject    what to name in the fallback copy — a package, a
   *                   snapshot, "your services"
   * @param rawReason  the mechanical reason, deliberately never surfaced
   */
  public static Classified classify(String tail, int exitCode, String subject, String rawReason) {
    String t = tail == null ? "" : tail;
    String tl = t.toLowerCase();

    if (tl.contains("address already in use") || tl.contains("port is already allocated")) {
      String port = firstMatch(PORT_RE, t);
      String p = port == null ? "a required port" : ("Port " + port);
      return new Classified(
          p + " is already in use by another program on this box. Free it up or pick a different port.",
          "port_conflict");
    }

    if (tl.contains("toomanyrequests") || tl.contains("429 too many requests")
        || tl.contains("pull access denied") || tl.contains("rate limit")) {
      return new Classified(
          "The container registry is rate-limiting Aurora right now. Wait a couple of minutes and try again.",
          "pull_rate_limited");
    }

    // Checked before the generic connectivity patterns below, because the
    // real message when the daemon is down is "Cannot connect to the Docker
    // daemon at unix:///var/run/docker.sock ... connect: connection refused"
    // — which would otherwise be reported as a registry problem and send
    // the operator looking at their router.
    if (tl.contains("cannot connect to the docker daemon")
        || tl.contains("is the docker daemon running")) {
      return new Classified(
          "Aurora can't reach the container engine on this box. Check that the container service is running.",
          "docker_down");
    }

    if (tl.contains("no space left on device")) {
      return new Classified(
          "The disk Aurora is writing to is full. Free up space or pick a different drive.",
          "disk_full");
    }

    // Distinct from a rate limit: the registry could not be reached at all,
    // which is a connectivity problem rather than a quota one and needs a
    // different thing checked.
    if (tl.contains("no such host") || tl.contains("dial tcp")
        || tl.contains("connection refused") || tl.contains("i/o timeout")
        || tl.contains("temporary failure in name resolution")) {
      return new Classified(
          "Aurora couldn't reach the image registry. Check this box's internet connection and try again.",
          "registry_unreachable");
    }

    if ((tl.contains("not a directory") || tl.contains("not a file"))
        && (tl.contains("mount") || tl.contains("rootfs") || tl.contains("bind"))) {
      return new Classified(
          "Aurora couldn't find one of its config files on the host. "
              + "This usually means the aurora repo isn't mounted at the same path "
              + "inside and outside the aurora container. Check AURORA_REPO_PATH_HOST.",
          "bind_mount_missing");
    }

    if (tl.contains(" exited (") || tl.contains("exited with code") || tl.contains("unhealthy")) {
      String container = firstMatch(CONTAINER_RE, t);
      String who = container == null ? subject : container;
      return new Classified(
          who + " started but crashed straight away. Aurora tailed its log to the panel below.",
          "container_crashed");
    }

    return new Classified(
        "Something went wrong with " + subject + ". The log below has the details.",
        "unknown");
  }

  private static String firstMatch(Pattern p, String s) {
    var m = p.matcher(s);
    if (!m.find()) return null;
    for (int g = 1; g <= m.groupCount(); g++) {
      if (m.group(g) != null) return m.group(g);
    }
    return null;
  }
}
