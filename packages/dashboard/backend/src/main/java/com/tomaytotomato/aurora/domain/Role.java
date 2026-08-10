package com.tomaytotomato.aurora.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Phase D — RBAC role for {@link AdminUser}.
 *
 * <p>Three tiers, deliberately narrow so the mental model stays
 * legible for a homelab operator:
 *
 * <ul>
 *   <li>{@link #ADMIN} — full control. Can create/delete users,
 *       change any other user's role, rotate secrets, enable/disable
 *       packages. Aurora needs at least one admin at all times; the
 *       delete/demote flow guards this.</li>
 *   <li>{@link #USER} — regular authenticated identity. Can log into
 *       Aurora + any package whose manifest declares
 *       {@code sso.min_role: user} (default). No user management.</li>
 *   <li>{@link #GUEST} — read-mostly identity. Reserved for packages
 *       whose manifest declares {@code sso.min_role: guest}
 *       (e.g. shared Grafana dashboards). Cannot log into Aurora
 *       itself \u2014 the dashboard controllers gate on
 *       {@code min_role >= USER}. Ships as a first-class role
 *       because pushing it later means schema migration + Authelia
 *       config surgery on live boxes.</li>
 * </ul>
 *
 * <p>Canonical DB form is lowercase (see V3 migration). Java-side we
 * use ALL_CAPS enum constants for readability; {@link #wireName()}
 * returns the DB form, {@link #fromWireName(String)} parses the
 * inverse.
 *
 * <p>Ordering matters: constants are declared least-to-most privileged
 * so {@code role.compareTo(other) >= 0} answers "is this role at
 * least as privileged as {@code other}?".
 */
public enum Role {
  GUEST,
  USER,
  ADMIN;

  /** Lowercase name used in SQLite + REST JSON. */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parse a DB / API value into the enum. Returns empty on unknown
   * strings; callers decide whether to reject the request or default.
   */
  public static Optional<Role> fromWireName(String s) {
    if (s == null) return Optional.empty();
    String normalised = s.trim().toLowerCase(Locale.ROOT);
    for (Role r : values()) {
      if (r.wireName().equals(normalised)) return Optional.of(r);
    }
    return Optional.empty();
  }

  /**
   * Convenience for policy checks. Reads more naturally than
   * {@code a.compareTo(b) >= 0} at call sites.
   */
  public boolean isAtLeast(Role other) {
    return this.compareTo(other) >= 0;
  }
}
