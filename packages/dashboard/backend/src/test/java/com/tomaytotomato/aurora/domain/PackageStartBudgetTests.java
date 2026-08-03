package com.tomaytotomato.aurora.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A8 (iter-7): {@link Package#startBudgetSeconds()} must mirror the frontend
 * {@code startBudgetMs()} contract — read {@code requires.start_budget_seconds}
 * from the manifest, clamp to {@code [30, 600]}, and fall back to the 30 s
 * default for absent / malformed / non-positive values.
 *
 * <p>These live on the domain record (not {@code PackagesService}) so both
 * {@code LaunchService} (launch-header logging) and any future policy engine
 * agree on the effective budget for a package without re-parsing manifests.
 */
class PackageStartBudgetTests {

  private static Package pkg(Map<String, Object> requires) {
    return new Package(
        "test", "Test", "desc", "cat",
        List.of(), List.of(), Map.of(), List.of(),
        requires, List.of(),
        null, false, false,
        com.tomaytotomato.aurora.domain.SsoBlock.DISABLED);
  }

  @Test
  void reads_integer_from_manifest() {
    assertEquals(180, pkg(Map.of("start_budget_seconds", 180)).startBudgetSeconds());
  }

  @Test
  void reads_long_from_manifest() {
    // SnakeYAML represents plain YAML integers as Integer, but larger
    // values become Long. Coerce.
    Map<String, Object> req = new HashMap<>();
    req.put("start_budget_seconds", 240L);
    assertEquals(240, pkg(req).startBudgetSeconds());
  }

  @Test
  void reads_numeric_string_from_manifest() {
    assertEquals(60, pkg(Map.of("start_budget_seconds", "60")).startBudgetSeconds());
  }

  @Test
  void defaults_to_30_when_absent() {
    assertEquals(30, pkg(Map.of()).startBudgetSeconds());
  }

  @Test
  void defaults_to_30_when_requires_is_null() {
    Map<String, Object> req = null;
    assertEquals(30, pkg(req).startBudgetSeconds());
  }

  @Test
  void defaults_to_30_on_non_numeric_string() {
    assertEquals(30, pkg(Map.of("start_budget_seconds", "soon")).startBudgetSeconds());
  }

  @Test
  void defaults_to_30_on_negative() {
    assertEquals(30, pkg(Map.of("start_budget_seconds", -5)).startBudgetSeconds());
  }

  @Test
  void defaults_to_30_on_zero() {
    assertEquals(30, pkg(Map.of("start_budget_seconds", 0)).startBudgetSeconds());
  }

  @Test
  void clamps_above_600_to_600() {
    assertEquals(600, pkg(Map.of("start_budget_seconds", 9999)).startBudgetSeconds());
  }

  @Test
  void clamps_at_boundary_600() {
    // Boundary: exactly 600 stays 600; 601 clamps to 600.
    assertEquals(600, pkg(Map.of("start_budget_seconds", 600)).startBudgetSeconds());
    assertEquals(600, pkg(Map.of("start_budget_seconds", 601)).startBudgetSeconds());
  }

  @Test
  void defaults_to_30_on_boolean_shape() {
    // A fat-fingered manifest with `start_budget_seconds: true` shouldn't
    // crash the record or the launch header.
    assertEquals(30, pkg(Map.of("start_budget_seconds", true)).startBudgetSeconds());
  }
}
