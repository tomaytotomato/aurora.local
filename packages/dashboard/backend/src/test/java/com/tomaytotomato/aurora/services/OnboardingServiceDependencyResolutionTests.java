package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.SsoBlock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the manifest {@code depends_on}/{@code recommends} walk in
 * {@link OnboardingService}: {@code resolveDependencies}, the copy builders
 * {@code dependencyWarnings}/{@code recommendsWarnings}, and the
 * {@code prettyPackageName} label helper they share with the frontend's
 * {@code prettyPackageName} (frontend/src/lib/packageName.ts).
 *
 * <p>Pure-helper tests: {@link Package} records are built in memory with
 * {@link #pkg} rather than written to disk, so these run without a
 * filesystem, Spring context, or PackagesService — the resolver only ever
 * needs a {@code Map<String, Package>}. Fixture-on-disk coverage (cycles and
 * dangling deps discovered through the real {@code /plan}/{@code /install}
 * endpoints) lives in {@code OnboardingPlanDependencyIntegrationTest}.
 */
class OnboardingServiceDependencyResolutionTests {

  private static OnboardingService svc() {
    return new OnboardingService(null, null, null, null, null, null, null, null);
  }

  private static Package pkg(String name, List<String> dependsOn, List<String> recommends) {
    return new Package(name, name, "fixture", "productivity",
        dependsOn, recommends, Map.of(), List.of(), Map.of(), List.of(),
        "", false, false, SsoBlock.DISABLED);
  }

  private static Map<String, Package> byName(Package... pkgs) {
    var m = new LinkedHashMap<String, Package>();
    for (var p : pkgs) m.put(p.name(), p);
    return m;
  }

  @Nested
  class ResolvingHardDependencies {

    @Test
    void pullsInADirectDependencyNotOriginallyRequested() {
      var core = pkg("core", List.of(), List.of());
      var media = pkg("media", List.of("core"), List.of());
      var byName = byName(core, media);

      var res = svc().resolveDependencies(List.of("media"), byName);

      assertThat(res.resolved()).contains("core", "media");
      assertThat(res.addedDependencies()).containsExactly("core");
      assertThat(res.requiredBy()).containsEntry("core", List.of("media"));
      assertThat(res.danglingDependencies()).isEmpty();
      assertThat(res.cycles()).isEmpty();
    }

    @Test
    void walksTransitiveDependenciesTwoHopsDeep() {
      var core = pkg("core", List.of(), List.of());
      var media = pkg("media", List.of("core"), List.of());
      var leaf = pkg("leaf", List.of("media"), List.of());
      var byName = byName(core, media, leaf);

      var res = svc().resolveDependencies(List.of("leaf"), byName);

      assertThat(res.resolved()).containsExactlyInAnyOrder("core", "media", "leaf");
      assertThat(res.addedDependencies()).containsExactlyInAnyOrder("core", "media");
      // Dependency-first: a package's own deps always resolve before it does.
      assertThat(res.resolved().indexOf("core")).isLessThan(res.resolved().indexOf("media"));
      assertThat(res.resolved().indexOf("media")).isLessThan(res.resolved().indexOf("leaf"));
    }

    @Test
    void aPackageAlreadySelectedIsNotReportedAsAdded() {
      var core = pkg("core", List.of(), List.of());
      var media = pkg("media", List.of("core"), List.of());
      var byName = byName(core, media);

      var res = svc().resolveDependencies(List.of("media", "core"), byName);

      assertThat(res.addedDependencies()).isEmpty();
      assertThat(res.resolved()).containsExactlyInAnyOrder("core", "media");
    }

    @Test
    void twoRequestedPackagesSharingADependencyBothAppearInRequiredBy() {
      var core = pkg("core", List.of(), List.of());
      var media = pkg("media", List.of("core"), List.of());
      var docs = pkg("documents", List.of("core"), List.of());
      var byName = byName(core, media, docs);

      var res = svc().resolveDependencies(List.of("media", "documents"), byName);

      assertThat(res.addedDependencies()).containsExactly("core");
      assertThat(res.requiredBy().get("core")).containsExactlyInAnyOrder("media", "documents");
    }
  }

  @Nested
  class DetectingCycles {

    @Test
    void aPackageDependingOnItselfIsReportedAsACycleNotAnInfiniteLoop() {
      var selfLoop = pkg("selfloop", List.of("selfloop"), List.of());
      var byName = byName(selfLoop);

      var res = svc().resolveDependencies(List.of("selfloop"), byName);

      assertThat(res.cycles()).hasSize(1);
      assertThat(res.cycles().get(0)).containsExactly("selfloop", "selfloop");
      assertThat(res.resolved()).contains("selfloop");
    }

    @Test
    void twoPackagesDependingOnEachOtherAreReportedAsACycleAndBothStillResolve() {
      var a = pkg("loop-a", List.of("loop-b"), List.of());
      var b = pkg("loop-b", List.of("loop-a"), List.of());
      var byName = byName(a, b);

      var res = svc().resolveDependencies(List.of("loop-a"), byName);

      assertThat(res.cycles()).hasSize(1);
      assertThat(res.cycles().get(0)).containsExactly("loop-a", "loop-b", "loop-a");
      // Both ends of the loop still land in the resolved set — Aurora
      // doesn't silently drop what the user asked for because of a bug
      // elsewhere in the graph.
      assertThat(res.resolved()).containsExactlyInAnyOrder("loop-a", "loop-b");
      // Not double-reported as a generic auto-add on top of the cycle.
      assertThat(res.addedDependencies()).doesNotContain("loop-b");
    }

    @Test
    void aCycleDoesNotStopUnrelatedPackagesFromResolving() {
      var core = pkg("core", List.of(), List.of());
      var media = pkg("media", List.of("core"), List.of());
      var a = pkg("loop-a", List.of("loop-b"), List.of());
      var b = pkg("loop-b", List.of("loop-a"), List.of());
      var byName = byName(core, media, a, b);

      var res = svc().resolveDependencies(List.of("media", "loop-a"), byName);

      assertThat(res.resolved()).containsExactlyInAnyOrder("core", "media", "loop-a", "loop-b");
      assertThat(res.cycles()).hasSize(1);
    }
  }

  @Nested
  class DetectingDanglingDependencies {

    @Test
    void aDependencyNamingAPackageWithNoManifestIsReportedNotDropped() {
      var broken = pkg("broken", List.of("ghost"), List.of());
      var byName = byName(broken);

      var res = svc().resolveDependencies(List.of("broken"), byName);

      assertThat(res.danglingDependencies()).containsExactly("broken -> ghost");
      assertThat(res.resolved()).containsExactly("broken");
      assertThat(res.cycles()).isEmpty();
    }

    @Test
    void realDependenciesStillResolveAlongsideADanglingOne() {
      var core = pkg("core", List.of(), List.of());
      var broken = pkg("broken", List.of("core", "ghost"), List.of());
      var byName = byName(core, broken);

      var res = svc().resolveDependencies(List.of("broken"), byName);

      assertThat(res.resolved()).containsExactlyInAnyOrder("core", "broken");
      assertThat(res.danglingDependencies()).containsExactly("broken -> ghost");
    }
  }

  @Nested
  class BuildingWarningCopy {

    @Test
    void autoAddedDependencyReadsAsHelpfulNotAsAnError() {
      var core = pkg("core", List.of(), List.of());
      var media = pkg("media", List.of("core"), List.of());
      var byName = byName(core, media);
      var res = svc().resolveDependencies(List.of("media"), byName);

      var warnings = svc().dependencyWarnings(res);

      // core is deliberately excluded — plan()'s two dedicated core
      // messages already cover it.
      assertThat(warnings).isEmpty();
    }

    @Test
    void autoAddedNonCoreDependencyNamesWhatRequiredItAndWhatWillHappen() {
      var media = pkg("media", List.of(), List.of());
      var leaf = pkg("leaf", List.of("media"), List.of());
      var byName = byName(media, leaf);
      var res = svc().resolveDependencies(List.of("leaf"), byName);

      var warnings = svc().dependencyWarnings(res);

      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0))
          .startsWith("Media is needed by Leaf")
          .contains("Aurora will turn it on for you")
          .endsWith(".");
    }

    @Test
    void danglingDependencyIsFramedAsAManifestBugNotAUserMistake() {
      var broken = pkg("broken-pkg", List.of("ghost-pkg"), List.of());
      var byName = byName(broken);
      var res = svc().resolveDependencies(List.of("broken-pkg"), byName);

      var warnings = svc().dependencyWarnings(res);

      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0))
          .contains("Broken Pkg")
          .contains("ghost-pkg")
          .contains("not something you did")
          .contains("installing will fail");
    }

    @Test
    void cycleWarningNamesEveryPackageInTheLoop() {
      var a = pkg("loop-a", List.of("loop-b"), List.of());
      var b = pkg("loop-b", List.of("loop-a"), List.of());
      var byName = byName(a, b);
      var res = svc().resolveDependencies(List.of("loop-a"), byName);

      var warnings = svc().dependencyWarnings(res);

      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0))
          .contains("Loop A → Loop B → Loop A")
          .contains("not something you did")
          .contains("installing will fail");
    }

    @Test
    void everyDependencyWarningIsAFullSentenceEndingInPunctuation() {
      // docs/UX_SPEC.md P4: warnings render as full sentences, no
      // internal rule-id tokens leaking into user-facing copy.
      var broken = pkg("broken", List.of("ghost", "broken"), List.of());
      var byName = byName(broken);
      var res = svc().resolveDependencies(List.of("broken"), byName);

      var warnings = svc().dependencyWarnings(res);

      assertThat(warnings).isNotEmpty();
      for (String w : warnings) {
        assertThat(w).endsWith(".");
        assertThat(w).doesNotContain("_below").doesNotContain("_lt").doesNotContain("rule_id");
      }
    }

    @Test
    void recommendsMissingFromSelectionReadsAsAdvisoryNotAsAnError() {
      var media = pkg("media", List.of(), List.of("privacy"));
      var byName = byName(media);

      var warnings = svc().recommendsWarnings(List.of("media"), byName);

      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0))
          .contains("Media works best alongside Privacy")
          .contains("will still work without it");
    }

    @Test
    void recommendsPresentInTheResolvedSetDoesNotWarn() {
      var media = pkg("media", List.of(), List.of("privacy"));
      var privacy = pkg("privacy", List.of(), List.of());
      var byName = byName(media, privacy);

      var warnings = svc().recommendsWarnings(List.of("media", "privacy"), byName);

      assertThat(warnings).isEmpty();
    }
  }

  @Nested
  class PrettyPackageNameLabels {

    @Test
    void prettifiesHyphenatedSlugs() {
      assertThat(svc().prettyPackageName("home-automation")).isEqualTo("Home Automation");
    }

    @Test
    void usesKnownAcronymsInsteadOfTitleCasing() {
      assertThat(svc().prettyPackageName("ai")).isEqualTo("AI");
      assertThat(svc().prettyPackageName("vpn")).isEqualTo("VPN");
    }

    @Test
    void capitalizesAPlainSlug() {
      assertThat(svc().prettyPackageName("media")).isEqualTo("Media");
    }
  }
}
