package com.tomaytotomato.aurora.domain;

import java.util.Map;

/**
 * Impact copy for the small set of containers whose being down has a
 * distinctive effect on the rest of the box.
 *
 * <p>Introduced 2026-08-30 (review item 3). The reviewer noted that
 * "Authelia is restart-looping" is technically accurate but tells a
 * homelabber nothing about why every gated vhost is now returning 502.
 * These four containers each take out a distinct capability — SSO,
 * reverse proxy, mail, database — and the message that says so is
 * more useful than the container name.
 *
 * <p>Anything not in this map falls back to the generic reason. Keeping
 * the set small on purpose: a per-container growth of copy invites
 * marketing-style adjectives; the four here are the ones an outage
 * of would strand.
 *
 * <p>Tone follows the Security page's plain-English explanations: what
 * broke, what the user sees, no emojis, no reassurance.
 */
public final class CoreServiceImpact {

  private CoreServiceImpact() {
  }

  /** Generic copy for a restart-looping container Aurora has no specific advice about. */
  public static final String DEFAULT_REASON = "Restarting — see logs";

  private static final Map<String, String> REASONS = Map.of(
      "authelia", "SSO down — every gated app returns 502 until this recovers",
      "caddy", "Reverse proxy down — nothing on this box is reachable by name",
      "stalwart", "Mail down — outgoing password-reset and notification emails will not deliver",
      "core-db", "Core database down — Authelia and Stalwart will follow it within seconds"
  );

  /**
   * Priority order for choosing the "one thing to look at" reason when
   * more than one container inside a package is down. Lower index wins.
   *
   * <p>Ordering rationale: without core-db, everything else is doomed
   * within seconds — but if only authelia is down, that is the visible
   * failure to the operator and the one to name. Authelia therefore
   * outranks core-db because it is the more informative headline
   * message on a mixed failure; core-db still gets its own row.
   */
  private static final Map<String, Integer> PRIORITY = Map.of(
      "authelia", 0,
      "caddy", 1,
      "stalwart", 2,
      "core-db", 3
  );

  private static final int DEFAULT_PRIORITY = 99;

  /** Reason string for a container that is restart-looping. Never null. */
  public static String reasonFor(String container) {
    return REASONS.getOrDefault(container, DEFAULT_REASON);
  }

  /**
   * Priority used to sort a list of degraded containers so the highest-
   * impact reason renders first on the Overview row and on the
   * AttentionStrip. Unknown containers sort last, in insertion order,
   * so a package outside the core map still lists its broken sibling
   * without imposing an arbitrary order.
   */
  public static int priorityFor(String container) {
    return PRIORITY.getOrDefault(container, DEFAULT_PRIORITY);
  }
}
