package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Version;
import com.tomaytotomato.aurora.services.JobService;
import com.tomaytotomato.aurora.services.StateFileService;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four app-detail control-panel verbs — Install, Start, Disable,
 * Uninstall — against the real {@link com.tomaytotomato.aurora.services.PackageLifecycleService},
 * the real repository tree, and a faked command boundary.
 *
 * <p>The fixture repo enables {@code core}, {@code media} and
 * {@code notes} (see {@code .state.yml}); {@code photos} exists on disk
 * but is not enrolled, giving a real NOT_INSTALLED case; {@code identity}
 * and {@code storage} exist purely so the core-package refusal can be
 * proven for all three mandatory packages, not just {@code core}.
 *
 * <p>{@code Start} itself ({@code POST /services/{name}/start}) already
 * had its own endpoint and test coverage before this work and is
 * untouched here.
 */
@WithMockUser
class PackagesLifecycleControllerIntegrationTest extends AuroraIntegrationTest {

  @Autowired
  JobService jobs;

  @Autowired
  StateFileService stateFiles;

  @Autowired
  DockerClient dockerClient;

  /**
   * {@code dockerClient} is the shared {@code @Primary} bean across every
   * {@code AuroraIntegrationTest} subclass; reset around each test so a
   * stubbed "media is running" container never leaks into another class.
   */
  @BeforeEach
  void freshDockerStub() {
    Mockito.reset(dockerClient);
    Mockito.when(dockerClient.versionCmd().exec()).thenReturn(new Version());
    stubContainers();
  }

  @AfterEach
  void restoreDockerStub() {
    Mockito.reset(dockerClient);
    Mockito.when(dockerClient.versionCmd().exec()).thenReturn(new Version());
    stubContainers();
  }

  private void stubContainers(Container... containers) {
    Mockito.when(dockerClient.listContainersCmd().withShowAll(true).exec())
        .thenReturn(List.of(containers));
  }

  /**
   * A container that PackagesService.runningPackageNames() attributes to
   * {@code pkg}. Needs both labels: {@code DockerService.listProjectContainers()}
   * filters on {@code com.docker.compose.project} first (must be
   * {@code aurora} or {@code aurora-*}), and only containers that pass
   * that filter are then attributed to a package via
   * {@code com.docker.compose.project.config_files}.
   */
  private Container runningContainerFor(String pkg) {
    Container c = Mockito.mock(Container.class);
    Map<String, String> labels = new HashMap<>();
    labels.put("com.docker.compose.project", "aurora-" + pkg);
    labels.put("com.docker.compose.project.config_files", "/repo/packages/" + pkg + "/compose.yml");
    Mockito.when(c.getLabels()).thenReturn(labels);
    Mockito.when(c.getNames()).thenReturn(new String[] {"/" + pkg});
    return c;
  }

  private String jobIdFrom(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    return body.replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
  }

  private JobService.Job awaitTerminal(String jobId) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    JobService.Job job = jobs.find(jobId).orElseThrow();
    while (!job.state.terminal() && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(job.state.terminal()).as("job %s should reach a terminal state", jobId).isTrue();
    return job;
  }

  // ------------------------------------------------------------------
  // Install (POST /packages/{name}/enable)
  // ------------------------------------------------------------------

  @Nested
  @DisplayName("Install")
  class Install {

    @Test
    void enrols_and_starts_a_package_that_is_not_installed() throws Exception {
      MvcResult result = mvc.perform(post("/api/packages/photos/enable"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.jobId").isNotEmpty())
          .andReturn();

      JobService.Job job = awaitTerminal(jobIdFrom(result));
      assertThat(job.state).isEqualTo(JobService.State.SUCCESS);

      // The critical property: up.sh must see the FULL desired enabled
      // set (existing + the new package), never just the one package —
      // up.sh's own state_set_enabled call at the end overwrites
      // enabled[] with whatever it was given, so a partial argv here
      // would silently un-enrol core, media and notes.
      var invocation = commands.firstMatching("scripts/up.sh");
      assertThat(invocation.argv()).contains("core", "media", "notes", "photos");
    }

    @Test
    void already_enabled_package_is_a_conflict() throws Exception {
      mvc.perform(post("/api/packages/media/enable"))
          .andExpect(status().isConflict());
    }

    @Test
    void unknown_package_is_404() throws Exception {
      mvc.perform(post("/api/packages/no-such-app/enable"))
          .andExpect(status().isNotFound());
    }

    @Test
    void a_core_package_refuses_to_be_installed() throws Exception {
      mvc.perform(post("/api/packages/core/enable")).andExpect(status().isForbidden());
      mvc.perform(post("/api/packages/identity/enable")).andExpect(status().isForbidden());
      mvc.perform(post("/api/packages/storage/enable")).andExpect(status().isForbidden());
    }
  }

  // ------------------------------------------------------------------
  // Disable (POST /packages/{name}/stop) — new: stop without uninstalling
  // ------------------------------------------------------------------

  @Nested
  @DisplayName("Disable")
  class Disable {

    @Test
    void stopping_a_running_package_leaves_it_enrolled() throws Exception {
      stubContainers(runningContainerFor("media"));

      MvcResult result = mvc.perform(post("/api/packages/media/stop"))
          .andExpect(status().isAccepted())
          .andReturn();

      JobService.Job job = awaitTerminal(jobIdFrom(result));
      assertThat(job.state).isEqualTo(JobService.State.SUCCESS);

      var invocation = commands.firstMatching("scripts/down.sh");
      assertThat(invocation.argv()).containsExactly("bash", "scripts/down.sh", "media");

      // Reversible without reinstalling: still in enabled[].
      assertThat(stateFiles.readState().enabled()).contains("media");
    }

    @Test
    void stopping_a_package_that_is_already_stopped_is_a_conflict() throws Exception {
      // Default docker stub: no running containers.
      mvc.perform(post("/api/packages/media/stop"))
          .andExpect(status().isConflict());
    }

    @Test
    void stopping_a_package_that_is_not_installed_is_unprocessable() throws Exception {
      mvc.perform(post("/api/packages/photos/stop"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknown_package_is_404() throws Exception {
      mvc.perform(post("/api/packages/no-such-app/stop"))
          .andExpect(status().isNotFound());
    }

    @Test
    void a_core_package_refuses_to_be_stopped_even_though_it_reports_running() throws Exception {
      // core's manifest declares probe.kind: self, so it always reports
      // running=true regardless of docker state — the refusal must come
      // from the core check, not from a "not running" side effect.
      mvc.perform(post("/api/packages/core/stop")).andExpect(status().isForbidden());
      mvc.perform(post("/api/packages/identity/stop")).andExpect(status().isForbidden());
      mvc.perform(post("/api/packages/storage/stop")).andExpect(status().isForbidden());
    }
  }

  // ------------------------------------------------------------------
  // Uninstall (POST /packages/{name}/disable)
  // ------------------------------------------------------------------

  @Nested
  @DisplayName("Uninstall")
  class Uninstall {

    @Test
    void stops_and_unenrols_a_running_package() throws Exception {
      stubContainers(runningContainerFor("media"));

      MvcResult result = mvc.perform(post("/api/packages/media/disable"))
          .andExpect(status().isAccepted())
          .andReturn();

      JobService.Job job = awaitTerminal(jobIdFrom(result));
      assertThat(job.state).isEqualTo(JobService.State.SUCCESS);

      var invocation = commands.firstMatching("scripts/down.sh");
      assertThat(invocation.argv()).containsExactly("bash", "scripts/down.sh", "media");

      assertThat(stateFiles.readState().enabled()).doesNotContain("media").contains("core", "notes");
    }

    @Test
    void also_works_on_a_package_that_is_enabled_but_already_stopped() throws Exception {
      // notes is enabled and (with the default docker stub) not running —
      // the frontend's own note: uninstall works from either state.
      MvcResult result = mvc.perform(post("/api/packages/notes/disable"))
          .andExpect(status().isAccepted())
          .andReturn();

      JobService.Job job = awaitTerminal(jobIdFrom(result));
      assertThat(job.state).isEqualTo(JobService.State.SUCCESS);
      assertThat(stateFiles.readState().enabled()).doesNotContain("notes");
    }

    @Test
    void data_on_disk_is_never_touched() throws Exception {
      stubContainers(runningContainerFor("media"));
      writeRepoFile("data/media/config/marker.txt", "a photo library, presumably");

      MvcResult result = mvc.perform(post("/api/packages/media/disable")).andReturn();
      awaitTerminal(jobIdFrom(result));

      assertThat(repoFileExists("data/media/config/marker.txt"))
          .as("uninstall must never delete application data")
          .isTrue();
    }

    @Test
    void a_failed_teardown_leaves_enrolment_untouched() throws Exception {
      commands.stubFailure("scripts/down.sh", 1, "compose: something went wrong");

      MvcResult result = mvc.perform(post("/api/packages/media/disable")).andReturn();
      JobService.Job job = awaitTerminal(jobIdFrom(result));

      assertThat(job.state).isEqualTo(JobService.State.FAILED);
      assertThat(stateFiles.readState().enabled())
          .as("a package must stay enrolled when down.sh fails, not be silently un-enrolled")
          .contains("media");
    }

    @Test
    void uninstalling_a_package_that_is_not_installed_is_unprocessable() throws Exception {
      mvc.perform(post("/api/packages/photos/disable"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknown_package_is_404() throws Exception {
      mvc.perform(post("/api/packages/no-such-app/disable"))
          .andExpect(status().isNotFound());
    }

    @Test
    void a_core_package_refuses_to_be_uninstalled() throws Exception {
      mvc.perform(post("/api/packages/core/disable")).andExpect(status().isForbidden());
      mvc.perform(post("/api/packages/identity/disable")).andExpect(status().isForbidden());
      mvc.perform(post("/api/packages/storage/disable")).andExpect(status().isForbidden());
    }
  }
}
