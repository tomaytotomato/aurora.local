package com.tomaytotomato.aurora.domain;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a package as parsed from packages/&lt;name&gt;/manifest.yml,
 * augmented with runtime cross-references from .state.yml + docker ps.
 */
public record Package(
    String name,
    String title,
    String description,
    String category,
    List<String> dependsOn,
    List<String> recommends,
    Map<String, Object> profiles,
    List<Map<String, Object>> ports,
    Map<String, Object> requires,
    List<String> requiredEnv,
    String postInstallNotes,
    boolean enabled,
    boolean running,
    SsoBlock sso
) {

  /**
   * Default start-poll budget (seconds) used when the manifest doesn't
   * declare one. Matches the frontend fallback in
   * {@code frontend/src/api/packages.ts::startBudgetMs()} so a package
   * without a manifest hint reads consistently on both sides of the wire.
   */
  public static final int DEFAULT_START_BUDGET_SECONDS = 30;

  /**
   * Absolute upper bound on the start-poll budget. A malicious or
   * fat-fingered manifest can't hold the UI on an optimistic "Starting…"
   * row for longer than ten minutes. Matches the frontend cap.
   */
  public static final int MAX_START_BUDGET_SECONDS = 600;

  /**
   * How long the frontend should wait after clicking Start before it
   * flips the row to "Couldn't start". Reads {@code requires.start_budget_seconds}
   * from the manifest, coerces numeric-shaped values, clamps to
   * {@link #MAX_START_BUDGET_SECONDS}, and falls back to
   * {@link #DEFAULT_START_BUDGET_SECONDS} for absent / malformed / non-positive
   * values.
   *
   * <p>Mirrors {@code frontend/src/api/packages.ts::startBudgetMs()} so
   * backend consumers (e.g. {@code LaunchService} launch-header logging)
   * and the frontend agree on the effective budget for a package.
   * Introduced in A8 (iter-7); precedent set by media/privacy manifests.
   */
  public int startBudgetSeconds() {
    Object raw = requires == null ? null : requires.get("start_budget_seconds");
    Integer v = coerceInt(raw);
    if (v == null || v <= 0) return DEFAULT_START_BUDGET_SECONDS;
    return Math.min(MAX_START_BUDGET_SECONDS, v);
  }

  private static Integer coerceInt(Object raw) {
    if (raw instanceof Number n) return n.intValue();
    if (raw instanceof String s) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignore) {
        return null;
      }
    }
    return null;
  }
}
