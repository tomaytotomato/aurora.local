package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.JobService;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/jobs} against the real {@link JobService}, the real HTTP
 * layer and real Spring Security. Only the command boundary is faked.
 *
 * <p>The behaviour worth pinning is not the JSON shape so much as the
 * honesty rules the frontend depends on: a running job has no exit code
 * yet, a failed job carries classified copy rather than stderr, and
 * opening the stream of a job that finished an hour ago still replays the
 * whole log.
 */
@WithMockUser
class JobsControllerIntegrationTest extends AuroraIntegrationTest {

  @Autowired
  JobService jobs;

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private JobService.Job awaitTerminal(JobService.Job job) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (!job.state.terminal() && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(job.state.terminal())
        .as("job %s should have reached a terminal state", job.id)
        .isTrue();
    return job;
  }

  private JobService.Job succeedingJob(JobService.Kind kind, String target, String... lines) {
    commands.stubLines("aurora-test-command", lines);
    return awaitTerminal(jobs.submitCommand(kind, target, Path.of("."),
        List.of("aurora-test-command", "--target", target == null ? "" : target)));
  }

  // ------------------------------------------------------------------

  @Nested
  @DisplayName("access")
  class Access {

    @Test
    @WithMockUser(username = "")
    void listing_requires_a_session() throws Exception {
      // Belt and braces on SecurityConfig: /api/jobs is not on the public
      // allow-list, and a job log can contain paths and image names.
      mvc.perform(get("/api/jobs").with(anonymous())).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor anonymous() {
      return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .anonymous();
    }
  }

  @Nested
  @DisplayName("an empty box")
  class Empty {

    @Test
    void reports_no_jobs_rather_than_failing() throws Exception {
      mvc.perform(get("/api/jobs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray());
    }

    @Test
    void an_unknown_job_is_a_404_not_an_empty_object() throws Exception {
      mvc.perform(get("/api/jobs/does-not-exist"))
          .andExpect(status().isNotFound());
    }

    @Test
    void streaming_an_unknown_job_is_a_404() throws Exception {
      mvc.perform(get("/api/jobs/does-not-exist/stream"))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("a job that succeeds")
  class Succeeding {

    @Test
    void appears_in_the_list_with_its_kind_and_target() throws Exception {
      JobService.Job job = succeedingJob(JobService.Kind.UPDATE, "jellyfin", "Pulling…", "Done");

      mvc.perform(get("/api/jobs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.id == '" + job.id + "')].kind").value("update"))
          .andExpect(jsonPath("$[?(@.id == '" + job.id + "')].target").value("jellyfin"))
          .andExpect(jsonPath("$[?(@.id == '" + job.id + "')].state").value("success"));
    }

    @Test
    void carries_its_whole_log_on_the_detail_endpoint() throws Exception {
      JobService.Job job = succeedingJob(JobService.Kind.UPDATE, "jellyfin",
          "Pulling jellyfin/jellyfin:10.10.0", "Pull complete", "Recreating");

      mvc.perform(get("/api/jobs/{id}", job.id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tail.length()").value(3))
          .andExpect(jsonPath("$.tail[0]").value("Pulling jellyfin/jellyfin:10.10.0"))
          .andExpect(jsonPath("$.tail[2]").value("Recreating"));
    }

    @Test
    void reports_exit_zero_and_no_failure() throws Exception {
      JobService.Job job = succeedingJob(JobService.Kind.START, "media", "Started");

      mvc.perform(get("/api/jobs/{id}", job.id))
          .andExpect(jsonPath("$.exitCode").value(0))
          .andExpect(jsonPath("$.failureCode").value(nullValue()))
          .andExpect(jsonPath("$.failureReason").value(nullValue()))
          .andExpect(jsonPath("$.finishedAt").isNotEmpty());
    }

    private static org.hamcrest.Matcher<Object> nullValue() {
      return org.hamcrest.Matchers.nullValue();
    }

    @Test
    void passes_its_arguments_separately_rather_than_as_a_shell_string() {
      // The whole reason CommandRunner takes a list. A package name is
      // operator-supplied and must never be spliced into a command line.
      succeedingJob(JobService.Kind.UPDATE, "jellyfin", "ok");

      var invocation = commands.firstMatching("aurora-test-command");
      assertThat(invocation.argv()).containsExactly("aurora-test-command", "--target", "jellyfin");
    }
  }

  @Nested
  @DisplayName("a job that fails")
  class Failing {

    @Test
    void is_classified_into_copy_rather_than_echoing_stderr() throws Exception {
      commands.stubFailure("aurora-test-command", 1,
          "Pulling ollama/ollama:0.4.1",
          "toomanyrequests: You have reached your pull rate limit.");
      JobService.Job job = awaitTerminal(
          jobs.submitCommand(JobService.Kind.UPDATE, "ai", Path.of("."),
              List.of("aurora-test-command")));

      mvc.perform(get("/api/jobs/{id}", job.id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("failed"))
          .andExpect(jsonPath("$.exitCode").value(1))
          .andExpect(jsonPath("$.failureCode").value("pull_rate_limited"));

      assertThat(job.failureReason)
          .as("failure copy is for a person, not a terminal")
          .doesNotContain("toomanyrequests")
          .contains("rate-limiting");
    }

    @Test
    void keeps_a_daemon_outage_distinct_from_a_registry_one() throws Exception {
      // These both contain "connection refused"; telling them apart is the
      // difference between checking Docker and checking the router.
      commands.stubFailure("aurora-test-command", 1,
          "Cannot connect to the Docker daemon at unix:///var/run/docker.sock.",
          "Is the docker daemon running?");
      JobService.Job job = awaitTerminal(
          jobs.submitCommand(JobService.Kind.START, "media", Path.of("."),
              List.of("aurora-test-command")));

      mvc.perform(get("/api/jobs/{id}", job.id))
          .andExpect(jsonPath("$.failureCode").value("docker_down"));
    }

    @Test
    void explains_itself_in_the_log_so_a_replay_shows_why() throws Exception {
      commands.stubFailure("aurora-test-command", 1, "no space left on device");
      JobService.Job job = awaitTerminal(
          jobs.submitCommand(JobService.Kind.BACKUP, null, Path.of("."),
              List.of("aurora-test-command")));

      mvc.perform(get("/api/jobs/{id}", job.id))
          .andExpect(jsonPath("$.failureCode").value("disk_full"))
          .andExpect(jsonPath("$.tail[1]").value(org.hamcrest.Matchers.containsString("full")));
    }

    @Test
    void a_missing_binary_still_reaches_a_terminal_state() throws Exception {
      // Otherwise the panel spins forever, which is the exact failure the
      // job abstraction exists to prevent.
      commands.stubMissingBinary("aurora-test-command");
      JobService.Job job = awaitTerminal(
          jobs.submitCommand(JobService.Kind.PARITY_SYNC, null, Path.of("."),
              List.of("aurora-test-command")));

      mvc.perform(get("/api/jobs/{id}", job.id))
          .andExpect(jsonPath("$.state").value("failed"))
          .andExpect(jsonPath("$.failureReason").isNotEmpty());
    }

    @Test
    void a_body_that_throws_unexpectedly_still_finishes() throws Exception {
      JobService.Job job = awaitTerminal(jobs.submit(JobService.Kind.DEPLOY, "my-stack", j -> {
        throw new IllegalStateException("something nobody anticipated");
      }));

      mvc.perform(get("/api/jobs/{id}", job.id))
          .andExpect(jsonPath("$.state").value("failed"))
          .andExpect(jsonPath("$.failureCode").value("unknown"));
    }
  }

  @Nested
  @DisplayName("filtering")
  class Filtering {

    @Test
    void narrows_by_state_and_by_kind() throws Exception {
      succeedingJob(JobService.Kind.UPDATE, "jellyfin", "ok");
      succeedingJob(JobService.Kind.BACKUP, null, "ok");

      mvc.perform(get("/api/jobs").param("kind", "update"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[*].kind").value(org.hamcrest.Matchers.everyItem(
              org.hamcrest.Matchers.is("update"))));

      mvc.perform(get("/api/jobs").param("state", "success"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[*].state").value(org.hamcrest.Matchers.everyItem(
              org.hamcrest.Matchers.is("success"))));
    }

    @Test
    void accepts_the_hyphenated_wire_form_of_a_kind() throws Exception {
      mvc.perform(get("/api/jobs").param("kind", "update-check"))
          .andExpect(status().isOk());
    }

    @Test
    void rejects_an_unknown_filter_rather_than_returning_everything() throws Exception {
      // A caller that typos state=finished should be told, not handed the
      // whole list and left to wonder.
      mvc.perform(get("/api/jobs").param("state", "finished"))
          .andExpect(status().isBadRequest());
      mvc.perform(get("/api/jobs").param("kind", "reticulate"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("the log stream")
  class Streaming {

    @Test
    void replays_the_whole_log_of_a_finished_job_and_closes() throws Exception {
      JobService.Job job = succeedingJob(JobService.Kind.UPDATE, "jellyfin",
          "Pulling…", "Pull complete", "Recreating");

      MvcResult result = mvc.perform(get("/api/jobs/{id}/stream", job.id))
          .andExpect(request().asyncStarted())
          .andReturn();
      result.getAsyncResult();
      // Explicit UTF-8: MockMvc decodes as ISO-8859-1 by default, which
      // turns a perfectly good ellipsis into mojibake and sends you looking
      // for a bug in the encoder.
      String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

      assertThat(body)
          .as("a log that finished an hour ago is still readable")
          .contains("Pulling…")
          .contains("Pull complete")
          .contains("Recreating");
      assertThat(body).contains("event:done");
      assertThat(body).contains("\"state\":\"success\"");
    }

    @Test
    void a_failed_job_streams_its_classified_reason_in_the_terminal_event() throws Exception {
      commands.stubFailure("aurora-test-command", 1, "no space left on device");
      JobService.Job job = awaitTerminal(
          jobs.submitCommand(JobService.Kind.RESTORE, "snap-1", Path.of("."),
              List.of("aurora-test-command")));

      MvcResult result = mvc.perform(get("/api/jobs/{id}/stream", job.id))
          .andExpect(request().asyncStarted())
          .andReturn();
      result.getAsyncResult();
      // Explicit UTF-8: MockMvc decodes as ISO-8859-1 by default, which
      // turns a perfectly good ellipsis into mojibake and sends you looking
      // for a bug in the encoder.
      String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

      assertThat(body).contains("\"failureCode\":\"disk_full\"");
    }
  }
}
