package com.tomaytotomato.aurora.security;

import com.tomaytotomato.aurora.domain.SecurityFinding;

import java.util.List;

/**
 * B4 (v0.3): passive check that inspects Aurora's world and emits zero
 * or more {@link SecurityFinding}s. Rules must be idempotent and
 * side-effect-free \u2014 they are called on every
 * {@code GET /api/security/findings} without caching (v0 scope).
 *
 * <p>Contract:
 * <ul>
 *   <li>Return {@link List#of()} when the rule finds nothing rather
 *       than {@code null}.</li>
 *   <li>Never throw \u2014 a rule that can't run its check (docker down,
 *       DB unreachable) should return empty rather than propagate. The
 *       aggregator {@link SecurityFindingsService} logs at DEBUG when a
 *       rule throws so a bad rule can't take down the endpoint.</li>
 *   <li>Findings from the same rule should carry stable {@code id}s so
 *       the future dismiss/snooze feature can key on them.</li>
 * </ul>
 */
public interface SecurityRule {

  /** Stable rule identifier used as the prefix for finding ids. */
  String id();

  /** Evaluate the rule against the current world state. */
  List<SecurityFinding> evaluate();
}
