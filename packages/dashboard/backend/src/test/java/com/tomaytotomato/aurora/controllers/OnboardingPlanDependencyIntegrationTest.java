package com.tomaytotomato.aurora.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/onboarding/plan} and {@code POST /api/onboarding/install}
 * against the real {@code depends_on}/{@code recommends} manifest fields,
 * exercised through the full HTTP + real-repo-on-disk stack rather than
 * calling {@code OnboardingService} directly — the promise being tested is
 * that the wizard's preview and the actual install agree, which only means
 * something if both go through the same controller/service/manifest path a
 * real request would.
 *
 * <p>The shared {@code fake-repo} fixture ({@code core}, {@code media},
 * {@code notes}) already has a real depends_on ({@code media -> core}) and
 * a real recommends ({@code media -> privacy}, where {@code privacy} isn't
 * even a package in the fixture) — enough to cover the hard/soft cases
 * without adding anything. Cycle and dangling-dependency coverage writes
 * extra manifests into the per-test repo copy via {@link #writeRepoFile}
 * (wiped and reseeded before every test by the base class), so the shared
 * fixture other suites reuse is never touched.
 */
class OnboardingPlanDependencyIntegrationTest extends AuroraIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private JsonNode getPlan(String enabledCsv) throws Exception {
    var req = enabledCsv == null
        ? get("/api/onboarding/plan")
        : get("/api/onboarding/plan").param("enabled", enabledCsv);
    MvcResult result = mvc.perform(req).andExpect(status().isOk()).andReturn();
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  private List<String> texts(JsonNode array) {
    var out = new ArrayList<String>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  private void createAdmin() throws Exception {
    mvc.perform(post("/api/onboarding/admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of(
                "username", "admin",
                "password", "a-very-strong-passw0rd",
                "tz", "UTC"))))
        .andExpect(status().isOk());
  }

  private void patchEnabledPackages(List<String> enabled) throws Exception {
    mvc.perform(patch("/api/onboarding")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("enabled_packages", enabled))))
        .andExpect(status().isOk());
  }

  private void writeManifest(String name, String dependsOnYaml, String recommendsYaml) throws Exception {
    writeRepoFile("packages/" + name + "/manifest.yml",
        "name: " + name + "\n"
            + "title: " + name + "\n"
            + "description: fixture\n"
            + "category: productivity\n"
            + "depends_on: " + dependsOnYaml + "\n"
            + "recommends: " + recommendsYaml + "\n");
  }

  @Nested
  class HardDependenciesAreAutoAdded {

    @Test
    void planAddsTheMissingHardDependencyAndSaysSo() throws Exception {
      // media's real fake-repo manifest depends_on: [core]. Requesting
      // media alone must still resolve core into packages_to_enable —
      // exactly what scripts/up.sh's manifest_resolve_deps would also do
      // before docker compose ever saw the list.
      var plan = getPlan("media");

      assertThat(texts(plan.get("packages_to_enable"))).contains("core", "media");
    }

    @Test
    void installPersistsTheResolvedSetIncludingAnAutoAddedNonCoreDependency() throws Exception {
      // A synthetic package whose only dependency is `media` (not core
      // directly) proves the closure is transitive, not a core special case.
      writeManifest("leaf", "[media]", "[]");
      createAdmin();
      patchEnabledPackages(List.of("leaf"));

      MvcResult result = mvc.perform(post("/api/onboarding/install"))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
      List<String> applied = texts(body.get("applied"));

      assertThat(applied).anySatisfy(line -> assertThat(line)
          .contains("Media")
          .contains("Leaf")
          .contains("requires it"));

      String stateYml = readRepoFile(".state.yml");
      assertThat(stateYml).contains("leaf").contains("media").contains("core");
    }
  }

  @Nested
  class RecommendsIsAdvisoryOnly {

    @Test
    void planWarnsAboutAnUnmetRecommendationWithoutTreatingItAsAnError() throws Exception {
      // Persisted fake-repo state already has core+media+notes enabled and
      // no privacy package at all — media's recommends: [privacy] should
      // still surface as friendly, non-blocking copy.
      var plan = getPlan(null);
      List<String> warnings = texts(plan.get("warnings"));

      assertThat(warnings).anySatisfy(w -> assertThat(w)
          .contains("Media works best alongside Privacy")
          .contains("will still work without it"));
      // The old hardcoded pair-specific string must be gone — this is a
      // generic recommends warning now, not a special case for media.
      assertThat(warnings).noneMatch(w -> w.contains("torrent traffic"));
    }

    @Test
    void planDoesNotWarnWhenTheRecommendedPackageIsAlreadySelected() throws Exception {
      writeManifest("privacy", "[core]", "[]");
      var plan = getPlan("core,media,privacy");
      List<String> warnings = texts(plan.get("warnings"));

      assertThat(warnings).noneMatch(w -> w.contains("Privacy"));
    }
  }

  @Nested
  class ManifestBugsAreReportedNotSilentlyDropped {

    @Test
    void planReportsADanglingDependencyInsteadOfDroppingIt() throws Exception {
      writeManifest("broken", "[ghost-package]", "[]");

      var plan = getPlan("broken");
      List<String> warnings = texts(plan.get("warnings"));

      assertThat(warnings).anySatisfy(w -> assertThat(w)
          .contains("ghost-package")
          .contains("not something you did")
          .contains("installing will fail"));
      // The package the user actually asked for is still in the plan —
      // Aurora doesn't punish the user for someone else's manifest bug.
      assertThat(texts(plan.get("packages_to_enable"))).contains("broken");
    }

    @Test
    void planReportsADependencyCycleInsteadOfHangingForever() throws Exception {
      writeManifest("loop-a", "[loop-b]", "[]");
      writeManifest("loop-b", "[loop-a]", "[]");

      // If the resolver ever regresses to an unguarded recursive walk,
      // this call simply never returns — the request timing out here
      // is the failure signature, not just an assertion mismatch.
      var plan = getPlan("loop-a");
      List<String> warnings = texts(plan.get("warnings"));

      assertThat(warnings).anySatisfy(w -> assertThat(w)
          .contains("depend on each other in a loop")
          .contains("not something you did")
          .contains("installing will fail"));
      assertThat(texts(plan.get("packages_to_enable"))).contains("loop-a", "loop-b");
    }
  }
}
