package com.tomaytotomato.aurora;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.services.DockerService;
import com.tomaytotomato.aurora.services.PackagesService;
import com.tomaytotomato.aurora.services.StateFileService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

  /**
   * Regression pin for the Done-page false negative: the dashboard reported
   * itself as "Not started" (with a "Start" button) on the very screen it
   * was serving, because {@code packages/dashboard/manifest.yml} had no
   * {@code probe:} block at all — StatusProbeService/PackagesService fell
   * back to {@code kind: docker} with the container defaulting to the
   * package name "dashboard", but the real container is named "aurora"
   * (see {@code packages/dashboard/compose.yml}), so the lookup could never
   * succeed. The fix gives {@code dashboard} the same {@code probe.kind:
   * self} shape {@code core} already has (see the two tests above) — this
   * pins it against a fresh {@link PackagesService} pointed at a fixture
   * package set that includes {@code dashboard} as enabled, with the mock
   * docker client reporting zero containers (the exact "container not
   * found" shape the bug depended on), rather than sharing this test
   * class's session-wide fake-repo state (which several other suites —
   * e.g. {@code MdnsAliasServiceTests} — pin to an exact {@code enabled:
   * [core, media, notes]} set).
   */
  @Test
  void dashboardProbeSelfMarksRunningEvenWithoutComposeLabels() {
    var stateFiles = Mockito.mock(StateFileService.class);
    Mockito.when(stateFiles.readState()).thenReturn(
        new RepoState(1, "aurora", "aurora.local", null, List.of("core", "dashboard"), List.of()));
    var docker = Mockito.mock(DockerService.class);
    Mockito.when(docker.listProjectContainers()).thenReturn(List.of());
    var props = new AuroraProperties("src/test/resources/fake-repo", null, List.of(), null);

    var svc = new PackagesService(props, stateFiles, docker);
    Package dashboard = svc.find("dashboard").orElseThrow();

    assertThat(dashboard.running())
        .as("dashboard has probe.kind: self and must be reported running from that fact alone, "
            + "never by asking docker for a container literally named 'dashboard'")
        .isTrue();
  }

  /**
   * Drift check against the real {@code packages/dashboard/manifest.yml} —
   * the file this fix actually edits, as opposed to the fixture copy used
   * above. Guarded the same way as {@code AutheliaConfigurationInvariantsTests
   * .snapshot_matches_source}: some sandboxes only mount
   * {@code packages/dashboard/backend}, in which case this silently no-ops
   * and the fixture-based test above still enforces the behaviour.
   */
  @Test
  @SuppressWarnings("unchecked")
  void realDashboardManifestDeclaresSelfProbe() throws IOException {
    Path realManifest = Path.of("../manifest.yml");
    if (!Files.isRegularFile(realManifest)) return;

    Map<String, Object> m;
    try (var in = Files.newInputStream(realManifest)) {
      m = new Yaml().load(in);
    }
    Object probeRaw = m.get("probe");
    assertThat(probeRaw)
        .as("packages/dashboard/manifest.yml must declare a probe: block — without one, "
            + "StatusProbeService defaults to kind: docker, container: dashboard, which never "
            + "matches the real container name 'aurora'")
        .isInstanceOf(Map.class);
    Map<String, Object> probe = (Map<String, Object>) probeRaw;
    assertThat(probe.get("kind")).isEqualTo("self");
    assertThat(probe.get("container")).isEqualTo("aurora");
  }
}
