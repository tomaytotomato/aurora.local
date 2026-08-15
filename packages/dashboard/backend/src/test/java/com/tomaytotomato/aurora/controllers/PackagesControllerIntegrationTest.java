package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/packages/{name}} through a real Spring context, real
 * SQLite and a real repository tree — the coverage gap that let
 * {@code PackagesController.get()} wrap its response in {@code {package,
 * env_example}} for months without a single test failing.
 *
 * <p>{@code PackagesControllerTests} already pins the flat shape with a
 * standalone, mocked-collaborator MockMvc; it does not run through
 * {@code AuroraIntegrationTest}, so it never went near the
 * {@code OpenApiConformance} check wired into every integration test's
 * default expectations. This class is the endpoint's first coverage in
 * the suite that actually exercises that check.
 */
@WithMockUser
class PackagesControllerIntegrationTest extends AuroraIntegrationTest {

  @Test
  @DisplayName("detail is flat, matches openapi.yaml's PackageDetail, and is what the app detail page reads")
  void detail_matches_the_spec() throws Exception {
    mvc.perform(get("/api/packages/notes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("notes"))
        .andExpect(jsonPath("$.category").value("productivity"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.running").value(false))
        // The old {package, env_example} wrapper is exactly what this
        // guards against: if it ever comes back, these fields are gone
        // and the ones above read null instead.
        .andExpect(jsonPath("$.package").doesNotExist())
        .andExpect(jsonPath("$.env_example").doesNotExist());
  }

  @Test
  void unknown_package_is_a_404() throws Exception {
    mvc.perform(get("/api/packages/does-not-exist")).andExpect(status().isNotFound());
  }
}
