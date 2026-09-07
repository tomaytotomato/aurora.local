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
 * <p>{@code sourceUrl}, {@code homepageUrl} and {@code icon} are
 * deliberately <em>summary</em> fields, matching the spec: the catalogue
 * renders the Source and Docs links and the app logo without a detail
 * fetch per card. {@code icon} is a bundled-icon slug (e.g. {@code
 * jellyfin}), which the frontend resolves to {@code /icons/<slug>.svg};
 * null when the manifest declares none, so the card falls back.
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
    /**
     * True when at least one container that belongs to this package is
     * healthy AND at least one other is not (state {@code restarting},
     * {@code exited}, {@code dead}, or {@code paused}). Populated by
     * {@link com.tomaytotomato.aurora.services.PackagesService}.
     *
     * <p>Introduced 2026-08-30 (review item 2). Before this, the health
     * pill went green as soon as any single container in a package was
     * up, which lied about the state of the box when Authelia
     * restart-looped inside {@code core}: caddy/db/stalwart stayed
     * {@code running}, so {@code core.running == true}, and the top
     * strip read “Apps: all running” while every gated vhost 502’d.
     *
     * <p>Distinct from {@code !running}: a package with every container
     * stopped is {@code running=false, degraded=false}. Both dimensions
     * are needed so “we never got here” and “we were here and
     * something broke” can render differently.
     */
    boolean degraded,
    /**
     * When {@link #degraded} is true, the broken container(s) that
     * pushed it there, each paired with a one-line impact string from
     * {@link CoreServiceImpact}. Null when {@code degraded == false}
     * so the wire is quiet on healthy packages ({@code @JsonInclude
     * NON_NULL}).
     *
     * <p>Introduced 2026-08-30 (review item 3). Item 2 landed the
     * package-level boolean; the reviewer's next question was "which
     * container inside {@code core} is down, and what does that take
     * out?" — the answer belongs on the same DTO the pill reads so
     * Overview and CoreServiceDetail cannot disagree.
     *
     * <p>Sorted by {@link CoreServiceImpact#priorityFor(String)} so
     * the first element is the highest-impact reason to render on the
     * Overview row and in the AttentionStrip's "one thing to look at"
     * slot.
     */
    List<DegradedService> degradedServices,
    SsoBlock sso,
    String sourceUrl,
    String homepageUrl,
    String icon,
    String readme,
    List<String> vhosts,
    List<EnvVarSpec> envVars,
    PackageBackupSpec backup,
    /**
     * The job this package does, when more than one package does it:
     * {@code webmail}, {@code notes}, {@code media-player}. Null for the
     * packages that are the only answer to their question.
     *
     * <p>ESSENCE calls for "one clear choice per job", and the manifests
     * have recorded that choice all along — roundcube is the default
     * webmail, silverbullet the default notes app — but nothing read the
     * fields, so the catalogue showed three webmails as three equal
     * options and left a non-technical owner to pick between them on
     * nothing.
     */
    String variantGroup,
    /** True for the package that is the recommended answer in its group. */
    Boolean variantDefault
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
        requires, requiredEnv, postInstallNotes, enabled, running, false, null, sso,
        null, null, null, null, null, null, null, null, null);
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
        ports, requires, requiredEnv, postInstallNotes, enabled, running, degraded,
        degradedServices, sso,
        sourceUrl, homepageUrl, icon, readme, vhosts, envVars, backup,
        variantGroup, variantDefault);
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
