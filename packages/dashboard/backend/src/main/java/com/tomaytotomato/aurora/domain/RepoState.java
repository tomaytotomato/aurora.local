package com.tomaytotomato.aurora.domain;

import java.util.List;

/**
 * Mirror of .state.yml. Fields optional so old/new schemas both parse.
 */
public record RepoState(
    Integer bootstrapVersion,
    String hostname,
    String domain,
    String installedAt,
    List<String> enabled,
    List<String> profiles
) {}
