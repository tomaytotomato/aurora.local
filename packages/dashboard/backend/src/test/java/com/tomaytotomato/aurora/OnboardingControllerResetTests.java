package com.tomaytotomato.aurora;

import com.tomaytotomato.aurora.controllers.OnboardingController;
import com.tomaytotomato.aurora.services.LaunchService;
import com.tomaytotomato.aurora.services.OnboardingService;
import com.tomaytotomato.aurora.services.StateFileService;
import com.tomaytotomato.aurora.services.SystemService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link OnboardingController#reset} (TD5 wizard-reset
 * endpoint, 2026-08-02).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code aurora.e2e-mode=false} → 404 (endpoint hidden), no state
 *       touched.</li>
 *   <li>{@code aurora.e2e-mode=true}  → 200 {@code {reset: true}}, service
 *       {@link OnboardingService#reset()} called exactly once.</li>
 *   <li>Idempotency lives in the service (already covered by
 *       {@code OnboardingServiceResetTests}); this slice only guards the
 *       controller-level env-var gate.</li>
 * </ul>
 *
 * <p>Uses standalone MockMvc + {@link ReflectionTestUtils} to set the
 * {@code @Value}-injected {@code e2eMode} field so we don't need a full
 * Spring context. Matches the pattern already used by
 * {@link OnboardingControllerPatchTests}.
 */
class OnboardingControllerResetTests {

  private MockMvc build(boolean e2eMode, OnboardingService onboarding) {
    OnboardingController c = new OnboardingController(
        onboarding,
        mock(SystemService.class),
        mock(LaunchService.class),
        mock(StateFileService.class),
        mock(com.tomaytotomato.aurora.services.IdentitySecretsService.class),
        mock(com.tomaytotomato.aurora.persistence.AuditEventRepo.class),
            mock(com.tomaytotomato.aurora.services.PackagesService.class),
            mock(com.tomaytotomato.aurora.services.SessionService.class));
    ReflectionTestUtils.setField(c, "e2eMode", e2eMode);
    return MockMvcBuilders.standaloneSetup(c).build();
  }

  @Test
  void reset_withE2eModeOff_returns404_andNeverCallsService() throws Exception {
    OnboardingService onboarding = mock(OnboardingService.class);
    MockMvc mvc = build(false, onboarding);

    mvc.perform(post("/api/onboarding/reset"))
        .andExpect(status().isNotFound());

    verify(onboarding, never()).reset();
  }

  @Test
  void reset_withE2eModeOn_returns200_shapedBody_andCallsServiceOnce() throws Exception {
    OnboardingService onboarding = mock(OnboardingService.class);
    MockMvc mvc = build(true, onboarding);

    mvc.perform(post("/api/onboarding/reset"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reset").value(true));

    verify(onboarding, times(1)).reset();
  }

  @Test
  void reset_isIdempotentAtTheHttpLayer_secondCallSucceeds() throws Exception {
    // Two POSTs in succession, both 200. Service is called twice; its
    // idempotency contract is tested at the service layer.
    OnboardingService onboarding = mock(OnboardingService.class);
    MockMvc mvc = build(true, onboarding);

    mvc.perform(post("/api/onboarding/reset")).andExpect(status().isOk());
    mvc.perform(post("/api/onboarding/reset")).andExpect(status().isOk());

    verify(onboarding, times(2)).reset();
  }
}
