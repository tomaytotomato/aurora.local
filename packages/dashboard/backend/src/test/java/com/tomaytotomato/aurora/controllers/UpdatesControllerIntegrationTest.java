package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.JobService;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/updates} against a real repository tree, with the command
 * boundary faked.
 *
 * <p>The behaviour worth pinning is the honesty rule: unknown is a
 * first-class answer, and anything Aurora could not actually determine
 * says so rather than showing a green tick nobody earned.
 */
@WithMockUser
class UpdatesControllerIntegrationTest extends AuroraIntegrationTest {

  private static final String LOCAL = "sha256:" + "a".repeat(64);
  private static final String REMOTE = "sha256:" + "b".repeat(64);

  @Autowired
  JobService jobs;

  @BeforeEach
  void seedOnePackage() throws IOException {
    writeRepoFile("packages/jellyfin/compose.yml",
        "services:\n  jellyfin:\n    image: jellyfin/jellyfin:10.9.6\n");
  }

  /** Both digests match: nothing to do. */
  private void stubUpToDate() {
    commands.stubLines("image inspect", "jellyfin/jellyfin@" + LOCAL);
    commands.stubLines("manifest inspect", "{ \"digest\": \"" + LOCAL + "\" }");
  }

  /** Registry serves something newer than what is installed. */
  private void stubUpdateAvailable() {
    commands.stubLines("image inspect", "jellyfin/jellyfin@" + LOCAL);
    commands.stubLines("manifest inspect", "{ \"digest\": \"" + REMOTE + "\" }");
  }

  private void runCheck() throws Exception {
    MvcResult result = mvc.perform(post("/api/updates/check"))
        .andExpect(status().isAccepted())
        .andReturn();
    String jobId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.jobId");
    awaitTerminal(jobId);
  }

  private void awaitTerminal(String jobId) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(deadline)) {
      var job = jobs.find(jobId).orElseThrow();
      if (job.state.terminal()) return;
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("check job never finished");
  }

  // ------------------------------------------------------------------

  @Nested
  @DisplayName("before anything has been checked")
  class Unchecked {

    @Test
    void reports_unknown_rather_than_up_to_date() throws Exception {
      // The important one. A box that has never asked a registry does not
      // know, and a green tick nobody earned is the one that gets believed.
      mvc.perform(get("/api/updates"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].package").value("jellyfin"))
          .andExpect(jsonPath("$[0].state").value("unknown"))
          .andExpect(jsonPath("$[0].lastCheckedAt").doesNotExist());
    }

    @Test
    void still_reports_the_facts_that_need_no_registry() throws Exception {
      // Which images and whether they are pinned are properties of the
      // compose file, knowable without asking anyone.
      mvc.perform(get("/api/updates/jellyfin"))
          .andExpect(jsonPath("$.images[0].image").value("jellyfin/jellyfin"))
          .andExpect(jsonPath("$.images[0].currentTag").value("10.9.6"))
          .andExpect(jsonPath("$.images[0].pinned").value(false));
    }

    @Test
    void asks_no_registry_just_to_render_the_page() throws Exception {
      mvc.perform(get("/api/updates")).andExpect(status().isOk());
      assertThat(commands.invocations())
          .as("a page load must not hit Docker Hub")
          .isEmpty();
    }

    @Test
    void an_unknown_package_is_a_404() throws Exception {
      mvc.perform(get("/api/updates/not-a-package")).andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("after a check")
  class Checked {

    @Test
    void reports_an_update_when_the_registry_serves_a_different_digest() throws Exception {
      stubUpdateAvailable();
      runCheck();

      mvc.perform(get("/api/updates/jellyfin"))
          .andExpect(jsonPath("$.state").value("available"))
          .andExpect(jsonPath("$.lastCheckedAt").isNotEmpty())
          .andExpect(jsonPath("$.images[0].currentDigest").value(LOCAL))
          .andExpect(jsonPath("$.images[0].latestDigest").value(REMOTE));
    }

    @Test
    void reports_current_when_the_digests_match() throws Exception {
      stubUpToDate();
      runCheck();

      mvc.perform(get("/api/updates/jellyfin"))
          .andExpect(jsonPath("$.state").value("current"))
          .andExpect(jsonPath("$.images[0].state").value("current"));
    }

    @Test
    void asks_without_pulling() throws Exception {
      // A check that downloaded gigabytes would be an antisocial way to
      // answer "is there an update".
      stubUpToDate();
      runCheck();

      assertThat(commands.ran("docker", "manifest", "inspect")).isTrue();
      assertThat(commands.invocations())
          .noneMatch(i -> i.command().contains("docker pull")
              || i.command().contains("docker image pull"));
    }

    @Test
    void passes_the_image_reference_as_its_own_argument() throws Exception {
      stubUpToDate();
      runCheck();

      var invocation = commands.firstMatching("manifest inspect");
      assertThat(invocation.argv()).containsExactly(
          "docker", "manifest", "inspect", "--verbose", "jellyfin/jellyfin:10.9.6");
    }

    @Test
    void stays_unknown_when_the_registry_cannot_be_reached() throws Exception {
      commands.stubLines("image inspect", "jellyfin/jellyfin@" + LOCAL);
      commands.stubFailure("manifest inspect", 1, "dial tcp: lookup registry-1.docker.io: no such host");
      runCheck();

      mvc.perform(get("/api/updates/jellyfin"))
          .andExpect(jsonPath("$.state").value("unknown"))
          .andExpect(jsonPath("$.images[0].latestDigest").doesNotExist())
          // Still records that we looked, so the page can say when.
          .andExpect(jsonPath("$.lastCheckedAt").isNotEmpty());
    }

    @Test
    void stays_unknown_when_the_image_has_never_been_pulled() throws Exception {
      commands.stubFailure("image inspect", 1, "Error: No such image");
      commands.stubLines("manifest inspect", "{ \"digest\": \"" + REMOTE + "\" }");
      runCheck();

      mvc.perform(get("/api/updates/jellyfin"))
          .andExpect(jsonPath("$.state").value("unknown"));
    }

    @Test
    void writes_a_readable_log_for_the_panel() throws Exception {
      stubUpdateAvailable();
      MvcResult result = mvc.perform(post("/api/updates/check")).andReturn();
      String jobId = com.jayway.jsonpath.JsonPath.read(
          result.getResponse().getContentAsString(), "$.jobId");
      awaitTerminal(jobId);

      mvc.perform(get("/api/jobs/{id}", jobId))
          .andExpect(jsonPath("$.kind").value("update-check"))
          .andExpect(jsonPath("$.state").value("success"))
          .andExpect(jsonPath("$.tail[0]").value(
              org.hamcrest.Matchers.containsString("Checking registries")))
          .andExpect(jsonPath("$.tail[-1:]").value(
              org.hamcrest.Matchers.hasItem(
                  org.hamcrest.Matchers.containsString("1 with updates waiting"))));
    }
  }

  @Nested
  @DisplayName("pinned images")
  class Pinned {

    @Test
    void are_reported_as_pinned_and_can_still_have_an_update() throws Exception {
      // A digest-pinned image will not move on its own, but knowing that a
      // newer one exists is still the useful answer.
      writeRepoFile("packages/jellyfin/compose.yml",
          "services:\n  jellyfin:\n    image: jellyfin/jellyfin:10.9.6@" + LOCAL + "\n");
      stubUpdateAvailable();
      runCheck();

      mvc.perform(get("/api/updates/jellyfin"))
          .andExpect(jsonPath("$.images[0].pinned").value(true))
          .andExpect(jsonPath("$.images[0].currentTag").value("10.9.6"))
          .andExpect(jsonPath("$.state").value("available"));
    }
  }
}
