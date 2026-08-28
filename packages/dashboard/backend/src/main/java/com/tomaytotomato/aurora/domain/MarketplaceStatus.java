package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The marketplace's state as the Overview banner and Settings surface see
 * it: which catalogue version is active on the box, whether a newer
 * verified one is waiting for the operator to accept, and provenance
 * (when it was last fetched, whether the signature checked out).
 *
 * <p>Point 6 of the plan lives here: a newer index that Aurora has
 * fetched and verified does <em>not</em> become active until the operator
 * accepts it. {@code available} is that pending version;
 * {@code updateAvailable} is the boolean the banner keys on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketplaceStatus(
    boolean enabled,
    String activeVersion,
    String activeGeneratedAt,
    int appCount,
    boolean signatureValid,
    String source,
    String lastFetchedAt,
    String lastFetchError,
    boolean updateAvailable,
    String availableVersion,
    String availableGeneratedAt,
    Integer availableAppCount,
    Integer availableNewAppCount
) {}
