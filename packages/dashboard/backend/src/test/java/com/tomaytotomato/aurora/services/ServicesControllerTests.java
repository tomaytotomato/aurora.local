package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.controllers.ServicesController;
import com.tomaytotomato.aurora.domain.Package;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link ServicesController#start} — status-code matrix for
 * the post-onboarding per-package start endpoint. Standalone MockMvc, no
 * Spring context, no docker. Matches the pattern established by
 * {@link OnboardingControllerPatchTests} and {@link HealthControllerTests}.
 *
 * <p>Closes reviewer's residual "no controller-layer test for the launch
 * endpoint status matrix" — same {@link LaunchService} path is now
 * exercised through the sibling endpoint.
 */
class ServicesControllerTests {

  private static Package pkg(String name, boolean enabled) {
    return new Package(
        name, name, name + " package", "media",
        List.of(), List.of(), Map.of(), List.of(), Map.of(), List.of(),
        null, enabled, false);
  }

  private static MockMvc mvc(PackagesService pkgs, LaunchService launcher) {
    return MockMvcBuilders.standaloneSetup(new ServicesController(pkgs, launcher)).build();
  }

  @Test
  void start_enabled_package_returns_202_with_job_id() throws Exception {
    PackagesService pkgs = mock(PackagesService.class);
    when(pkgs.find("media")).thenReturn(Optional.of(pkg("media", true)));

    LaunchService launcher = mock(LaunchService.class);
    // Job constructor is package-private; this test lives in the same
    // package so we can build one directly. That also verifies the
    // controller reads the same fields (id, packages, startedAt) the
    // production runner writes to.
    LaunchService.Job job = new LaunchService.Job("job-123", List.of("media"));
    when(launcher.startLaunch(List.of("media"))).thenReturn(job);

    mvc(pkgs, launcher).perform(post("/api/services/media/start"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.job_id").value("job-123"))
        .andExpect(jsonPath("$.packages[0]").value("media"))
        .andExpect(jsonPath("$.started_at").exists());
  }

  @Test
  void start_unknown_package_returns_404() throws Exception {
    PackagesService pkgs = mock(PackagesService.class);
    when(pkgs.find("nosuch")).thenReturn(Optional.empty());

    mvc(pkgs, mock(LaunchService.class))
        .perform(post("/api/services/nosuch/start"))
        .andExpect(status().isNotFound());
  }

  @Test
  void start_disabled_package_returns_422() throws Exception {
    PackagesService pkgs = mock(PackagesService.class);
    when(pkgs.find("media")).thenReturn(Optional.of(pkg("media", false)));

    mvc(pkgs, mock(LaunchService.class))
        .perform(post("/api/services/media/start"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void start_malformed_name_returns_400() throws Exception {
    // Shape violations: uppercase, path traversal, empty-ish. Spring's path
    // variable binder rejects "/" and ".." at the routing layer, so we test
    // the reachable shape violations: uppercase and leading digit.
    PackagesService pkgs = mock(PackagesService.class);
    LaunchService launcher = mock(LaunchService.class);

    mvc(pkgs, launcher).perform(post("/api/services/UPPER/start"))
        .andExpect(status().isBadRequest());
    mvc(pkgs, launcher).perform(post("/api/services/9media/start"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void start_when_launch_in_progress_returns_409() throws Exception {
    PackagesService pkgs = mock(PackagesService.class);
    when(pkgs.find("media")).thenReturn(Optional.of(pkg("media", true)));

    LaunchService launcher = mock(LaunchService.class);
    when(launcher.startLaunch(List.of("media")))
        .thenThrow(new LaunchService.LaunchInProgressException("job-active"));

    mvc(pkgs, launcher).perform(post("/api/services/media/start"))
        .andExpect(status().isConflict());
  }
}
