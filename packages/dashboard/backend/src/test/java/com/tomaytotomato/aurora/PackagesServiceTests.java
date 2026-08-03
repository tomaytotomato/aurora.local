package com.tomaytotomato.aurora;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.services.PackagesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestDockerConfig.class)
class PackagesServiceTests {

  @Autowired PackagesService packages;

  @Test
  void parsesFakeRepoManifests() {
    List<Package> list = packages.list();
    assertThat(list).extracting(Package::name).contains("core", "media");
    Package media = list.stream().filter(p -> "media".equals(p.name())).findFirst().orElseThrow();
    assertThat(media.category()).isEqualTo("media");
    assertThat(media.dependsOn()).containsExactly("core");
    assertThat(media.enabled()).isTrue(); // fake-repo state has media enabled
  }

  /**
   * B1 (iter-3): a package with probe.kind == 'self' means "the dashboard
   * itself". Even when no docker container carries a compose project label
   * pointing at /packages/<name>/, that package must report running=true —
   * otherwise Aurora (bootstrapped, not started from packages/core/) shows a
   * Start button on the dashboard. TestDockerConfig's mock returns an empty
   * container list, so this is exactly the scenario we're covering.
   */
  @Test
  void coreProbeSelfMarksRunningEvenWithoutComposeLabels() {
    Package core = packages.list().stream()
        .filter(p -> "core".equals(p.name()))
        .findFirst()
        .orElseThrow();
    assertThat(core.running())
        .as("core has probe.kind: self and must be reported running when Aurora is serving")
        .isTrue();
  }

  /**
   * B1 negative case: packages without probe.kind: self stay off unless a
   * compose label proves the containers are up. Media in the fake repo has
   * probe.kind: http_json (not self) so it must NOT get the self-probe boost.
   */
  @Test
  void nonSelfProbePackagesDoNotGetSelfProbeBoost() {
    Package media = packages.list().stream()
        .filter(p -> "media".equals(p.name()))
        .findFirst()
        .orElseThrow();
    assertThat(media.running())
        .as("media has no probe.kind: self and mock docker returns no containers")
        .isFalse();
  }
}
