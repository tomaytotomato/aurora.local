package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A parsed, verified marketplace index blob.
 *
 * <p>Mirrors {@code marketplace/schema/marketplace-v1.json}'s root. Aurora
 * only ever holds an index it has verified against the pinned public key,
 * so a {@code MarketplaceIndex} in memory is by construction a
 * signature-checked one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketplaceIndex(
    int schemaVersion,
    String indexVersion,
    String generatedAt,
    String minDashboardVersion,
    List<MarketplaceApp> apps
) {}
