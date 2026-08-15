package com.tomaytotomato.aurora.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.services.LaunchService;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walks {@code /install}, {@code /launch} and {@code /complete} in exactly
 * the order the frontend wizard uses them, against the real HTTP layer and
 * a real {@link LaunchService} (only the OS process boundary is faked).
 *
 * <p>The bug this pins: {@code OnboardingReview.vue} used to call
 * {@code /complete} straight after {@code /install}, before the Done page
 * ever got to call {@code /launch}. {@link com.tomaytotomato.aurora.services.OnboardingService#guardMidOnboarding()}
 * refuses every mutating onboarding call once {@code onboarding.complete}
 * is true, so that early commit made the subsequent launch 409 every
 * single time — nobody could finish the wizard. The fix moved the
 * commit to after a successful launch; these tests exist so a future
 * change can't quietly put it back in front again.
 *
 * <p>Deliberately anonymous (no {@code @WithMockUser}): every one of these
 * endpoints is public during onboarding — that is the entire reason the
 * launch/complete ordering matters. There is no session yet for the Done
 * page to fall back on.
 */
class OnboardingLaunchOrderingIntegrationTest extends AuroraIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired
  private LaunchService launcher;

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private void createAdmin() throws Exception {
    mvc.perform(post("/api/onboarding/admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of(
                "username", "admin",
                "password", "a-very-strong-passw0rd",
                "tz", "UTC"))))
        .andExpect(status().isOk());
  }

  private void install() throws Exception {
    mvc.perform(post("/api/onboarding/install")).andExpect(status().isOk());
  }

  /** A launch that finishes SUCCESS almost instantly (fake command runner, exit 0). */
  private void stageSucceedingUpSh() throws Exception {
    writeRepoFile("scripts/up.sh", "#!/usr/bin/env bash\nexit 0\n");
  }

  private String startLaunchExpecting202() throws Exception {
    MvcResult result = mvc.perform(post("/api/onboarding/launch"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.job_id").exists())
        .andReturn();
    JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
    return body.get("job_id").asText();
  }

  private LaunchService.Job awaitTerminal(String jobId) throws InterruptedException {
    LaunchService.Job job = launcher.get(jobId);
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (job.state == LaunchService.State.RUNNING && Instant.now().isBefore(deadline)) {
      Thread.sleep(10);
    }
    assertThat(job.state).as("job %s should have reached a terminal state", jobId)
        .isNotEqualTo(LaunchService.State.RUNNING);
    return job;
  }

  // ------------------------------------------------------------------
  // The happy path, in the frontend's actual order
  // ------------------------------------------------------------------

  @Test
  void install_then_launch_then_complete_matches_frontend_order_and_never_409s() throws Exception {
    createAdmin();
    stageSucceedingUpSh();
    install();

    // OnboardingDone.vue: POST /launch while onboarding is still incomplete.
    String jobId = startLaunchExpecting202();
    LaunchService.Job job = awaitTerminal(jobId);
    assertThat(job.state).isEqualTo(LaunchService.State.SUCCESS);

    // Only now, once the launch has actually succeeded, does the frontend commit.
    mvc.perform(post("/api/onboarding/complete"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true));

    mvc.perform(get("/api/onboarding"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true));
  }

  // ------------------------------------------------------------------
  // The guard must still stand: completing early still blocks a launch.
  // ------------------------------------------------------------------

  @Test
  void launch_after_complete_is_still_rejected_with_409() throws Exception {
    createAdmin();
    stageSucceedingUpSh();
    install();

    // The old (buggy) frontend order: commit before ever launching.
    mvc.perform(post("/api/onboarding/complete")).andExpect(status().isOk());

    // A genuinely completed onboarding must not be replayable by an
    // unauthenticated caller — this is the guard the fix must not weaken.
    mvc.perform(post("/api/onboarding/launch"))
        .andExpect(status().isConflict());
  }

  @Test
  void a_completed_onboarding_also_refuses_a_second_install_and_a_second_complete() throws Exception {
    createAdmin();
    stageSucceedingUpSh();
    install();
    String jobId = startLaunchExpecting202();
    awaitTerminal(jobId);
    mvc.perform(post("/api/onboarding/complete")).andExpect(status().isOk());

    mvc.perform(post("/api/onboarding/install")).andExpect(status().isConflict());
    mvc.perform(post("/api/onboarding/complete")).andExpect(status().isConflict());
  }

  // ------------------------------------------------------------------
  // A failed launch must leave onboarding retryable, not stranded.
  // ------------------------------------------------------------------

  @Test
  void a_failed_launch_leaves_onboarding_incomplete_and_retryable() throws Exception {
    createAdmin();
    install();
    // Deliberately no scripts/up.sh staged: LaunchService fails the job
    // immediately with "scripts/up.sh not found", the same shape of
    // failure a real broken checkout would hit.

    String jobId = startLaunchExpecting202();
    LaunchService.Job job = awaitTerminal(jobId);
    assertThat(job.state).isEqualTo(LaunchService.State.FAILED);

    // Onboarding must still be incomplete — no /complete call ever
    // happened, and none should have been able to succeed even if it had
    // (guardMidOnboarding only cares about isComplete(), not about launch
    // history, so this is really asserting the frontend never called it).
    mvc.perform(get("/api/onboarding"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(false));

    // Retry: stage a working script and launch again. Must succeed (202),
    // proving the failed attempt didn't leave the box permanently stuck.
    stageSucceedingUpSh();
    String retryJobId = startLaunchExpecting202();
    LaunchService.Job retryJob = awaitTerminal(retryJobId);
    assertThat(retryJob.state).isEqualTo(LaunchService.State.SUCCESS);

    mvc.perform(post("/api/onboarding/complete"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true));
  }

  // ------------------------------------------------------------------
  // /install must also still be reachable after /launch (Done page racing
  // ahead of a stale hydrate would otherwise be blocked prematurely).
  // ------------------------------------------------------------------

  @Test
  void install_remains_available_after_a_launch_completes_but_before_complete_is_called() throws Exception {
    createAdmin();
    stageSucceedingUpSh();
    install();
    String jobId = startLaunchExpecting202();
    awaitTerminal(jobId);

    // Onboarding is not complete yet (frontend hasn't called /complete),
    // so a re-PATCH/install must still be permitted — the guard keys off
    // isComplete(), not off launch history.
    mvc.perform(post("/api/onboarding/install")).andExpect(status().isOk());
  }
}
