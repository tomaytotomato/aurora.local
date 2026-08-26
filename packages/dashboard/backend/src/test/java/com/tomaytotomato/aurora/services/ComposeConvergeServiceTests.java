package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.support.FakeCommandRunner;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java converge that replaces {@code scripts/up.sh}'s docker-compose
 * orchestration (Option A: Java owns the logic, compose stays the engine).
 * The interesting property is the argv — which compose files, in what
 * order, with which services scoped — so the tests assert on exactly that.
 */
class ComposeConvergeServiceTests {

  private static AuroraProperties props(Path repo) {
    return new AuroraProperties(
        repo.toString(),
        "/host/proc",
        List.of(),
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
  }

  private static void writePackage(Path repo, String name, boolean withEnv) throws IOException {
    Path dir = repo.resolve("packages").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("compose.yml"), "name: aurora-" + name + "\n");
    if (withEnv) {
      Files.writeString(dir.resolve(".env"), "TZ=Europe/London\n");
    }
  }

  private static ComposeConvergeService service(Path repo, FakeCommandRunner runner) {
    return new ComposeConvergeService(props(repo), runner);
  }

  @Nested
  class ComposeFileAssembly {

    @Test
    void keepsResolvedOrderSoCoreIsTheFirstFile(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      writePackage(repo, "roundcube", true);
      var svc = service(repo, new FakeCommandRunner());

      var files = svc.composeFiles(List.of("core", "roundcube"));

      assertThat(files)
          .as("compose resolves relative bind-mounts against the first -f file, so core must be first")
          .containsExactly(
              repo.resolve("packages/core/compose.yml"),
              repo.resolve("packages/roundcube/compose.yml"));
    }

    @Test
    void skipsAPackageWithAManifestButNoComposeFile(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      Files.createDirectories(repo.resolve("packages/halfwritten")); // manifest-only, no compose.yml
      var svc = service(repo, new FakeCommandRunner());

      var files = svc.composeFiles(List.of("core", "halfwritten"));

      assertThat(files).containsExactly(repo.resolve("packages/core/compose.yml"));
    }

    @Test
    void forcesTheInstalledDashboardInSoRemoveOrphansCannotReapIt(@TempDir Path repo)
        throws IOException {
      writePackage(repo, "core", true);
      writePackage(repo, "dashboard", true); // installed (has .env) but NOT requested
      var svc = service(repo, new FakeCommandRunner());

      var files = svc.composeFiles(List.of("core"));

      assertThat(files).contains(repo.resolve("packages/dashboard/compose.yml"));
      assertThat(svc.dashboardForced(List.of("core"), files)).isTrue();
    }

    @Test
    void leavesAnUninstalledDashboardOutEntirely(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      writePackage(repo, "dashboard", false); // compose.yml but no .env → not installed
      var svc = service(repo, new FakeCommandRunner());

      var files = svc.composeFiles(List.of("core"));

      assertThat(files).doesNotContain(repo.resolve("packages/dashboard/compose.yml"));
      assertThat(svc.dashboardForced(List.of("core"), files)).isFalse();
    }
  }

  @Nested
  class UpArgv {

    @Test
    void bringsTheWholeProjectUpWhenNothingIsExcluded(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      var core = repo.resolve("packages/core/compose.yml");

      var argv = ComposeConvergeService.upArgv(List.of(core), List.of());

      assertThat(argv).containsExactly(
          "docker", "compose", "-p", "aurora",
          "-f", core.toString(),
          "up", "-d", "--pull", "missing", "--remove-orphans");
    }

    @Test
    void scopesUpToExactlyTheGivenServicesWhenExcludingTheDashboard(@TempDir Path repo)
        throws IOException {
      writePackage(repo, "core", true);
      var core = repo.resolve("packages/core/compose.yml");

      var argv = ComposeConvergeService.upArgv(List.of(core), List.of("caddy", "authelia", "stalwart"));

      // The service scope is the tail after --remove-orphans. Assert on that
      // rather than the whole argv, because "aurora" also appears as the
      // compose project (-p aurora), so a bare doesNotContain would misfire.
      var services = argv.subList(argv.indexOf("--remove-orphans") + 1, argv.size());
      assertThat(services)
          .containsExactly("caddy", "authelia", "stalwart")
          .doesNotContain("aurora"); // the dashboard's own service, deliberately absent
    }

    @Test
    void pullsOnlyMissingImagesNotEveryFloatingTag(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      var argv = ComposeConvergeService.upArgv(List.of(repo.resolve("packages/core/compose.yml")), List.of());
      // The fix for the floating-tag hazard: never a blanket `pull`, only
      // fetch what is absent.
      assertThat(argv).containsSequence("--pull", "missing");
      assertThat(argv).doesNotContain("always");
    }
  }

  @Nested
  class Profiles {

    @Test
    void addsTheCpuProfileWhenGpuIsNotRequested() {
      assertThat(ComposeConvergeService.effectiveProfiles(List.of())).containsExactly("cpu");
      assertThat(ComposeConvergeService.effectiveProfiles(List.of("torrent")))
          .containsExactly("torrent", "cpu");
    }

    @Test
    void leavesGpuAloneAndDoesNotAddCpuAlongsideIt() {
      assertThat(ComposeConvergeService.effectiveProfiles(List.of("gpu")))
          .containsExactly("gpu")
          .doesNotContain("cpu");
    }

    @Test
    void rendersProfilesIntoComposeProfilesEnv(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      var svc = service(repo, new FakeCommandRunner());

      var plan = svc.plan(List.of("core"), List.of("torrent"), false);

      assertThat(plan.env()).containsEntry("COMPOSE_PROFILES", "torrent,cpu");
    }
  }

  @Nested
  class SelfLaunchGuard {

    @Test
    void excludesTheDashboardsOwnServicesFromUpWhenItWasOnlyForcedIn(@TempDir Path repo)
        throws IOException {
      writePackage(repo, "core", true);
      writePackage(repo, "dashboard", true); // installed, not requested → forced in

      var runner = new FakeCommandRunner();
      // dashboard's own compose file (no -p aurora) → its single service
      runner.stubLines("compose -f " + repo.resolve("packages/dashboard/compose.yml") + " config", "aurora");
      // the merged project (-p aurora) → every service
      runner.stubLines("-p aurora", "caddy", "authelia", "stalwart", "aurora");

      var svc = service(repo, runner);
      var plan = svc.plan(List.of("core"), List.of(), false);

      var services = plan.argv().subList(plan.argv().indexOf("--remove-orphans") + 1, plan.argv().size());
      assertThat(services)
          .as("the container issuing the converge must never be recreated by its own converge")
          .containsExactly("caddy", "authelia", "stalwart")
          .doesNotContain("aurora");
    }

    @Test
    void bringsTheWholeProjectUpForAPlainHostConverge(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      var svc = service(repo, new FakeCommandRunner());

      var plan = svc.plan(List.of("core"), List.of(), false);

      // No dashboard installed, not self-launched: no service scoping, so the
      // argv ends at --remove-orphans with no trailing service names.
      assertThat(plan.argv()).endsWith("up", "-d", "--pull", "missing", "--remove-orphans");
    }
  }

  @Nested
  class Running {

    @Test
    void createsTheSharedNetworkOnlyWhenItIsAbsent(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      var runner = new FakeCommandRunner();
      runner.stubFailure("network inspect aurora_net", 1); // absent

      service(repo, runner).ensureNetwork();

      assertThat(runner.ran("network", "create", "aurora_net")).isTrue();
    }

    @Test
    void doesNotRecreateAnExistingNetwork(@TempDir Path repo) throws IOException {
      writePackage(repo, "core", true);
      var runner = new FakeCommandRunner(); // inspect succeeds by default

      service(repo, runner).ensureNetwork();

      assertThat(runner.ran("network", "create")).isFalse();
    }

    @Test
    void streamsTheComposeUpCommand(@TempDir Path repo) throws Exception {
      writePackage(repo, "core", true);
      var runner = new FakeCommandRunner();
      var svc = service(repo, runner);

      int exit = svc.run(List.of("core"), List.of(), false, line -> {}, new CommandRunner.CancelToken());

      assertThat(exit).isZero();
      assertThat(runner.ran("compose", "-p", "aurora", "up", "-d", "--remove-orphans")).isTrue();
    }
  }
}
