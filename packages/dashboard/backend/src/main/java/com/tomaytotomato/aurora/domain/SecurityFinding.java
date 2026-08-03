package com.tomaytotomato.aurora.domain;

/**
 * B4 (v0.3): a single finding surfaced by a {@link com.tomaytotomato.aurora.security.SecurityRule}.
 *
 * <p>Kept trim on purpose \u2014 the frontend renders one card per finding
 * with a "Fix it" link, and any richer metadata (evidence, screenshots,
 * per-container context) belongs in a follow-up model.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} \u2014 stable rule identifier ({@code rule.instance})
 *       so a returning user can dismiss a specific occurrence. Example:
 *       {@code unpinned_image_tags:aurora-media-sonarr}.</li>
 *   <li>{@code severity} \u2014 one of {@code LOW}, {@code MEDIUM},
 *       {@code HIGH}. Not an enum because the frontend maps directly to
 *       Badge tones and adding a new severity should be a copy change,
 *       not a schema change.</li>
 *   <li>{@code title} \u2014 short, human-friendly headline. No shell
 *       substrings ({@code sudo }, {@code docker }, {@code bash }) \u2014
 *       Sarah reads these, not a sysadmin.</li>
 *   <li>{@code description} \u2014 one paragraph explaining what and
 *       why. Same copy rules.</li>
 *   <li>{@code remediationUrl} \u2014 either an in-app route
 *       ({@code /packages/media/config}) or an external doc URL. May be
 *       null when there's no single fix path.</li>
 * </ul>
 */
public record SecurityFinding(
    String id,
    String severity,
    String title,
    String description,
    String remediationUrl
) {
  public static final String LOW = "low";
  public static final String MEDIUM = "medium";
  public static final String HIGH = "high";
}
