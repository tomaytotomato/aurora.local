package com.tomaytotomato.aurora;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.controllers.OnboardingController;
import com.tomaytotomato.aurora.services.LaunchService;
import com.tomaytotomato.aurora.services.OnboardingService;
import com.tomaytotomato.aurora.services.PackageNameValidator;
import com.tomaytotomato.aurora.services.StateFileService;
import com.tomaytotomato.aurora.services.SystemService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link OnboardingController#patch} — asserts that a PATCH with
 * malformed or unknown package names returns HTTP 400 with the shaped body
 * {@code {error, invalid, unknown, message}} rather than a generic 400.
 *
 * <p>Uses standalone MockMvc (no Spring context, no DB, no docker) to isolate
 * the controller and the {@link OnboardingService} mock. Matches the pattern
 * already established by {@link HealthControllerTests}.
 */
class OnboardingControllerPatchTests {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void patchWithInvalidPackageNamesReturnsShapedBadRequest() throws Exception {
    OnboardingService onboarding = mock(OnboardingService.class);
    when(onboarding.patch(any())).thenThrow(
        new PackageNameValidator.InvalidPackageNamesException(
            "one or more package names are malformed",
            List.of("../etc"), List.of()));

    MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new OnboardingController(
            onboarding,
            mock(SystemService.class),
            mock(LaunchService.class),
            mock(StateFileService.class),
            mock(com.tomaytotomato.aurora.persistence.AuditEventRepo.class),
            mock(com.tomaytotomato.aurora.services.SessionService.class))).build();

    String body = JSON.writeValueAsString(Map.of("enabled_packages", List.of("../etc")));
    mvc.perform(patch("/api/onboarding")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_package_names"))
        .andExpect(jsonPath("$.invalid[0]").value("../etc"))
        .andExpect(jsonPath("$.unknown").isArray());
  }

  @Test
  void patchWithUnknownPackageReturnsShapedBadRequestWithUnknownList() throws Exception {
    OnboardingService onboarding = mock(OnboardingService.class);
    when(onboarding.patch(any())).thenThrow(
        new PackageNameValidator.InvalidPackageNamesException(
            "one or more packages do not exist on this box",
            List.of(), List.of("foo")));

    MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new OnboardingController(
            onboarding,
            mock(SystemService.class),
            mock(LaunchService.class),
            mock(StateFileService.class),
            mock(com.tomaytotomato.aurora.persistence.AuditEventRepo.class),
            mock(com.tomaytotomato.aurora.services.SessionService.class))).build();

    String body = JSON.writeValueAsString(Map.of("enabled_packages", List.of("foo")));
    mvc.perform(patch("/api/onboarding")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_package_names"))
        .andExpect(jsonPath("$.unknown[0]").value("foo"))
        .andExpect(jsonPath("$.invalid").isArray());
  }
}
