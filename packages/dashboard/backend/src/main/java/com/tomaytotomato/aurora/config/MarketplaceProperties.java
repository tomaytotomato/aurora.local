package com.tomaytotomato.aurora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Marketplace catalogue-hosting knobs, bound from {@code aurora.marketplace.*}.
 *
 * <p>The whole feature is off by default ({@code enabled: false}) so a box
 * that never opts into a remotely-hosted catalogue behaves exactly as it
 * did before: the marketplace renders from the on-disk {@code packages/}
 * tree and nothing reaches out to GitHub. Turning it on makes Aurora fetch
 * the signed index from {@code indexUrl}, verify it against the pinned
 * public key, cache it under {@code data/marketplace/}, and merge it into
 * the catalogue.
 *
 * <p>Even when enabled, the seed index shipped inside the dashboard build
 * is always available as an offline fallback, so a box with no internet
 * still renders the catalogue it last saw (or the one it shipped with).
 */
@ConfigurationProperties(prefix = "aurora.marketplace")
public record MarketplaceProperties(
    boolean enabled,
    String indexUrl,
    String fetchCron,
    boolean fetchOnStartup
) {
  public MarketplaceProperties {
    if (fetchCron == null || fetchCron.isBlank()) {
      // Once a day at 03:14 — off the top of the hour so a fleet of boxes
      // does not stampede GitHub Releases on the hour.
      fetchCron = "0 14 3 * * *";
    }
  }
}
