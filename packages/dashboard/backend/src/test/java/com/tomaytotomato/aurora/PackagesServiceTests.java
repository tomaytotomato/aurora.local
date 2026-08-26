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

  @Test
  void parsesTheManifestIconWhenDeclared() {
    Package photos = packages.list().stream()
        .filter(p -> "photos".equals(p.name()))
        .findFirst().orElseThrow();
    assertThat(photos.icon())
        .as("photos fixture declares `icon: immich`, which the card renders as a logo")
        .isEqualTo("immich");
  }

  @Test
  void iconIsNullWhenTheManifestDeclaresNone() {
    Package core = packages.list().stream()
        .filter(p -> "core".equals(p.name()))
        .findFirst().orElseThrow();
    assertThat(core.icon())
        .as("a manifest with no icon field serialises no icon, so the card falls back")
        .isNull();
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

    // mdns is only reached by detail() for the vhost list; find() never
    // touches it, so null is honest about what this test exercises.
    var svc = new PackagesService(props, stateFiles, docker, null);
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

  /**
   * Every shipped package must name its upstream source.
   *
   * <p>The app page renders Source and Docs buttons from
   * {@code source_url} / {@code homepage_url}. Both lived only in the MSW
   * fixtures for months, which is precisely why the reviewed page looked
   * complete and a real box's did not — the buttons were there and led
   * nowhere. A new package added without a source link would reintroduce
   * exactly that, silently, and no other test would notice.
   *
   * <p>{@code homepage_url} is deliberately not required: for a few
   * packages the repository <em>is</em> the documentation, and inventing a
   * homepage to satisfy a test would be worse than omitting the button.
   *
   * <p>Guarded like the drift check above: no-ops in a sandbox that only
   * mounts the backend directory.
   */
  @Test
  void everyRealManifestNamesItsUpstreamSource() throws IOException {
    Path packagesDir = Path.of("../../../packages");
    if (!Files.isDirectory(packagesDir)) return;

    List<String> missing = new java.util.ArrayList<>();
    try (var dirs = Files.list(packagesDir)) {
      for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
        String name = dir.getFileName().toString();
        // _template is a scaffold, not a shipped package; CI's schema
        // check skips it for the same reason.
        if (name.startsWith("_") || name.startsWith(".")) continue;
        Path manifest = dir.resolve("manifest.yml");
        if (!Files.isRegularFile(manifest)) continue;
        Map<String, Object> m;
        try (var in = Files.newInputStream(manifest)) {
          m = new Yaml().load(in);
        }
        Object src = m == null ? null : m.get("source_url");
        if (src == null || src.toString().isBlank()) missing.add(name);
      }
    }

    assertThat(missing)
        .as("these packages declare no source_url, so their app page's Source button "
            + "would render with nothing behind it")
        .isEmpty();
  }
}
