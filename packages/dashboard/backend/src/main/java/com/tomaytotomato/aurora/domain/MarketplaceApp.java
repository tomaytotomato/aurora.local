package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One app as it appears in the fetched marketplace index.
 *
 * <p>Two projections back this record. The catalogue list
 * ({@code GET /marketplace}) returns the summary fields only — no
 * {@code compose}, {@code envExample}, {@code caddySnippet} or
 * {@code readme} — because shipping every app's full compose + README on a
 * list of eighteen would be ~130 KB the marketplace grid never renders.
 * The detail path ({@code GET /marketplace/{slug}}) carries the embedded
 * bodies. {@code NON_NULL} keeps the list response free of the four heavy
 * fields so it conforms to the summary schema.
 *
 * <p>Mirrors {@code marketplace/schema/marketplace-v1.json}'s
 * {@code app} definition; the composer
 * ({@code marketplace/scripts/compose.py}) produces it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketplaceApp(
    String slug,
    String title,
    String description,
    String category,
    String icon,
    List<String> dependsOn,
    List<String> recommends,
    String variantGroup,
    Boolean variantDefault,
    String sourceUrl,
    String homepageUrl,
    java.util.Map<String, Object> requires,
    List<MarketplaceImage> images,
    boolean unpinned,
    // Detail-only embedded bodies; omitted on the list projection.
    String compose,
    String envExample,
    String caddySnippet,
    String readme
) {

  /** The summary projection: no embedded bodies. */
  public MarketplaceApp toSummary() {
    return new MarketplaceApp(slug, title, description, category, icon, dependsOn, recommends,
        variantGroup, variantDefault, sourceUrl, homepageUrl, requires, images, unpinned,
        null, null, null, null);
  }
}
