package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.services.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Review 2026-08-30 item 2 companion: surface a Security-page finding
 * for every container currently restart-looping.
 *
 * <p>The top-strip pill going amber (item 2 backend) tells the operator
 * <em>something</em> is wrong; this rule tells them <em>which container</em>,
 * with a count of how often it has bounced. Same shape as the other
 * B4 rules, so it appears on the Security page alongside them and can
 * be dismissed / snoozed by existing machinery.
 *
 * <p>Reads {@link Container#getState()} and {@link Container#getStatus()}:
 * the state string flips to {@code restarting} within the docker daemon's
 * own cadence, and the status string (e.g. {@code "Restarting (1) 43 seconds ago"})
 * carries the count without an inspect roundtrip. Skipping inspect keeps
 * the rule cheap enough to evaluate on every {@code GET /api/security/findings}
 * hit alongside the other rules.
 *
 * <p>Aurora's own container is exempt: if we are dying, we can't emit a
 * finding about it anyway.
 */
@Component
public class RestartLoopRule implements SecurityRule {

  private static final Logger log = LoggerFactory.getLogger(RestartLoopRule.class);

  /** Matches Docker's status format e.g. "Restarting (1) 43 seconds ago". */
  private static final Pattern RESTART_COUNT = Pattern.compile("Restarting \\((\\d+)\\)");

  private final DockerService docker;

  public RestartLoopRule(DockerService docker) {
    this.docker = docker;
  }

  @Override
  public String id() { return "restart_loop"; }

  @Override
  public List<SecurityFinding> evaluate() {
    List<SecurityFinding> out = new ArrayList<>();
    try {
      for (Container c : docker.listProjectContainers()) {
        String state = c.getState();
        if (state == null || !"restarting".equalsIgnoreCase(state)) continue;
        String name = firstName(c);
        if (name == null) continue;
        if (DockerSocketExposureRule.isAuroraOwner(name)) continue;

        int count = extractRestartCount(c.getStatus());
        out.add(new SecurityFinding(
            id() + ":" + name,
            SecurityFinding.MEDIUM,
            prettyName(name) + " is restart-looping",
            countPhrase(prettyName(name), count) + " Something inside it is failing "
                + "on start-up, so every service it fronts is offline until it stays up. "
                + "Check `docker logs " + name + "` for the reason it keeps exiting; "
                + "the top of the log usually names the missing dependency or bad config.",
            null
        ));
      }
    } catch (Exception e) {
      log.debug("restart-loop rule failed: {}", e.getMessage());
    }
    return out;
  }

  /** Pull the restart count out of Docker's status string. Zero if absent. */
  static int extractRestartCount(String status) {
    if (status == null) return 0;
    Matcher m = RESTART_COUNT.matcher(status);
    if (!m.find()) return 0;
    try {
      return Integer.parseInt(m.group(1));
    } catch (NumberFormatException ignore) {
      return 0;
    }
  }

  private static String countPhrase(String pretty, int count) {
    if (count <= 0) return pretty + " keeps crashing on start.";
    if (count == 1) return pretty + " has crashed on start once so far.";
    return pretty + " has crashed on start " + count + " times so far.";
  }

  private static String prettyName(String name) {
    if (name == null || name.isBlank()) return "This container";
    String base = name.replace('_', ' ').replace('-', ' ').trim();
    return Character.toUpperCase(base.charAt(0)) + base.substring(1);
  }

  private static String firstName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return null;
    String n = names[0];
    return n.startsWith("/") ? n.substring(1) : n;
  }
}
