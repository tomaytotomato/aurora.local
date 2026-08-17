package com.tomaytotomato.aurora.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.services.LaunchService;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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
 * The owner's first-run complaint: clicking "Go to my dashboard" at the end
 * of the wizard dropped him on the login screen. {@code POST
 * /api/onboarding/admin} returned 200 but never established a session, so
 * the immediately-following {@code GET /api/auth/me} said
 * {@code authenticated: false} and {@code GET /api/packages} 401'd.
 *
 * <p>This walks the wizard in exactly the order the frontend uses —
 * {@code /admin} → {@code /install} → {@code /launch} → {@code /complete}
 * — against the real HTTP layer, and proves the session left behind after
 * {@code /complete} can reach an authenticated endpoint with no separate
 * {@code /api/auth/login} call anywhere in the flow.
 *
 * <p>Also pins the other half of the contract: a second, unauthenticated
 * {@code /complete} call must not be able to mint a session of its own —
 * {@link com.tomaytotomato.aurora.services.OnboardingService#guardMidOnboarding()}
 * still refuses it with 409, same as before this change.
 */
class OnboardingCompleteEstablishesSessionIntegrationTest extends AuroraIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired
  private LaunchService launcher;

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

  private String startLaunch() throws Exception {
    MvcResult result = mvc.perform(post("/api/onboarding/launch"))
        .andExpect(status().isAccepted())
        .andReturn();
    JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
    return body.get("job_id").asText();
  }

  private void awaitTerminal(String jobId) throws InterruptedException {
    LaunchService.Job job = launcher.get(jobId);
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (job.state == LaunchService.State.RUNNING && Instant.now().isBefore(deadline)) {
      Thread.sleep(10);
    }
    assertThat(job.state).isEqualTo(LaunchService.State.SUCCESS);
  }

  @Test
  void completing_onboarding_leaves_the_caller_with_an_authenticated_session() throws Exception {
    writeRepoFile("scripts/up.sh", "#!/usr/bin/env bash\nexit 0\n");

    createAdmin();
    install();
    String jobId = startLaunch();
    awaitTerminal(jobId);

    // No cookie has been issued anywhere above — confirms the assertions
    // below exercise the effect of /complete, not some earlier step.
    mvc.perform(get("/api/auth/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false));

    MvcResult completeResult = mvc.perform(post("/api/onboarding/complete"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true))
        .andReturn();
    MockHttpSession session = (MockHttpSession) completeResult.getRequest().getSession(false);
    assertThat(session).as("/complete must leave the caller holding a session").isNotNull();

    // The session /complete leaves behind is authenticated as the admin
    // the wizard just created — no /api/auth/login call anywhere above.
    mvc.perform(get("/api/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true))
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.role").value("admin"));

    mvc.perform(get("/api/packages").session(session))
        .andExpect(status().isOk());
  }

  @Test
  void a_second_unauthenticated_complete_call_cannot_mint_a_session() throws Exception {
    writeRepoFile("scripts/up.sh", "#!/usr/bin/env bash\nexit 0\n");

    createAdmin();
    install();
    String jobId = startLaunch();
    awaitTerminal(jobId);

    mvc.perform(post("/api/onboarding/complete")).andExpect(status().isOk());

    // A second caller, with no session cookie of their own, replaying
    // /complete — the guard must still refuse it, exactly as it did
    // before this change.
    MvcResult replay = mvc.perform(post("/api/onboarding/complete"))
        .andExpect(status().isConflict())
        .andReturn();
    assertThat(replay.getRequest().getSession(false))
        .as("a refused /complete call must not hand out a session")
        .isNull();
  }
}
