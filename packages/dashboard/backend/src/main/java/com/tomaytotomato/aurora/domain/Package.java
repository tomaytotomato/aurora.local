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
    boolean running
) {}
