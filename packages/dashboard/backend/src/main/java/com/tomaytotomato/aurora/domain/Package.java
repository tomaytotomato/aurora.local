package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a package as parsed from packages/&lt;name&gt;/manifest.yml,
 * augmented with runtime cross-references from .state.yml + docker ps.
 *
 * <p>This one record backs two schemas. {@code openapi.yaml}'s
 * {@code PackageSummary} is what {@code GET /packages} returns;
 * {@code PackageDetail} extends it with {@code readme}, {@code vhosts},
 * {@code envVars} and {@code backup}, which only {@code GET
 * /packages/{name}} serves. Those four are null on the list path and
 * populated by {@link #withDetail} on the detail path.
 *
 * <p>Hence {@code NON_NULL}: the list response must not carry them at
 * all. {@code OpenApiConformance} fails any response with a property its
 * schema does not document, so serving them everywhere would mean adding
 * four more entries to that check's known-undocumented registry — the
 * one cost this feature must not pay. Omission also handles the plain-
 * string fields ({@code readme}, {@code title}) whose schema would reject
 * an explicit null.
 *
 * <p>{@code sourceUrl} and {@code homepageUrl} are deliberately
 * <em>summary</em> fields, matching the spec: the catalogue renders the
 * Source and Docs links without a detail fetch per card.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
    SsoBlock sso,
    String sourceUrl,
    String homepageUrl,
    String readme,
    List<String> vhosts,
    List<EnvVarSpec> envVars,
    PackageBackupSpec backup
) {

  /**
   * The shape every caller outside the packages-detail path uses: a
   * summary, with no upstream links and none of the detail-only fields.
   *
   * <p>Kept so that adding the six fields above did not mean editing
   * seven unrelated test files to pass six nulls each, which would have
   * been churn with no behaviour attached to it.
   */
  public Package(
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
    this(name, title, description, category, dependsOn, recommends, profiles, ports,
        requires, requiredEnv, postInstallNotes, enabled, running, sso,
        null, null, null, null, null, null);
  }

  /**
   * Returns a copy carrying the detail-only fields. {@code vhosts} and
   * {@code envVars} are expected to be non-null here (empty lists where a
   * package genuinely serves no vhosts or declares no variables) so the
   * detail response always has the arrays its schema promises;
   * {@code readme} and {@code backup} stay nullable and are simply
   * omitted when the package has neither.
   */
  public Package withDetail(
      String readme,
      List<String> vhosts,
      List<EnvVarSpec> envVars,
      PackageBackupSpec backup
  ) {
    return new Package(name, title, description, category, dependsOn, recommends, profiles,
        ports, requires, requiredEnv, postInstallNotes, enabled, running, sso,
        sourceUrl, homepageUrl, readme, vhosts, envVars, backup);
  }

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
