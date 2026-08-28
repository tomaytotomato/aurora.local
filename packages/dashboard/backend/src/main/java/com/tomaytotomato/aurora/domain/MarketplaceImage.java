package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One container image a marketplace app runs, pinned by digest.
 *
 * <p>{@code digest} is the single most important field in the catalogue:
 * it turns "the latest catalogue" into a promise that the operator's next
 * install gets exactly the same bytes on every box that installs from this
 * index. It is null when the composer could not resolve one at build time
 * (no registry access, or an env-interpolated tag like
 * {@code ${FOO:-bar}}); the parent app is then flagged {@code unpinned} so
 * the consent screen can warn.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketplaceImage(
    String ref,
    String digest
) {}
