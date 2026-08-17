package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.services.AuthService;
import com.tomaytotomato.aurora.services.SessionService;
import com.tomaytotomato.aurora.services.StateFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase D iter-14 (D13) \u2014 {@link AuthController#logout} +
 * {@link AuthController#ssoLogoutUrl}.
 *
 * <p>Standalone MockMvc setup so no Spring context is needed (same
 * pre-existing Spring Boot 4 bean-override collision noted in
 * HealthControllerTests). Verifies:
 *
 * <ul>\n *   <li>Logout invalidates the HttpSession (SPA read via /auth/session\n *       would then return anonymous \u2014 covered by an existing test).</li>\n *   <li>Response body carries {@code next} = null when identity is\n *       disabled, so the SPA does its usual local /login redirect.</li>\n *   <li>Response body carries {@code next} = Authelia logout URL when\n *       identity is enabled, so the SPA bounces through Authelia to\n *       clear the shared session cookie.</li>\n *   <li>URL construction uses url-encoded {@code rd} param.</li>\n *   <li>Missing domain in {@code .state.yml} \u2192 next=null (fail-safe;\n *       Authelia URL would be malformed anyway).</li>\n * </ul>
 */
class AuthControllerLogoutTests {

  private AuthService auth;
  private StateFileService stateFiles;
  private AuthController ctrl;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    auth = Mockito.mock(AuthService.class);
    stateFiles = Mockito.mock(StateFileService.class);
    ctrl = new AuthController(auth, stateFiles, new SessionService());
    mvc = MockMvcBuilders.standaloneSetup(ctrl).build();
  }

  @Test
  void logout_when_identity_disabled_returns_next_null() throws Exception {
    Mockito.when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "media"), List.of()
    ));

    mvc.perform(post("/api/auth/logout"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.next").doesNotExist());
  }

  @Test
  void logout_when_identity_enabled_returns_authelia_logout_url_with_rd() throws Exception {
    Mockito.when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "identity"), List.of()
    ));

    mvc.perform(post("/api/auth/logout"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.next").value(
            "https://auth.aurora.local/logout?rd=https%3A%2F%2Faurora.local%2Flogin"));
  }

  @Test
  void ssoLogoutUrl_null_when_identity_not_in_enabled_list() {
    Mockito.when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core"), List.of()
    ));
    assertThat(ctrl.ssoLogoutUrl()).isEmpty();
  }

  @Test
  void ssoLogoutUrl_null_when_enabled_list_is_null() {
    Mockito.when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null, null, null
    ));
    assertThat(ctrl.ssoLogoutUrl()).isEmpty();
  }

  @Test
  void ssoLogoutUrl_null_when_domain_missing() {
    Mockito.when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", null, null,
        List.of("core", "identity"), List.of()
    ));
    assertThat(ctrl.ssoLogoutUrl()).isEmpty();
  }

  @Test
  void ssoLogoutUrl_url_encodes_the_redirect_target() {
    Mockito.when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "home.example.com", null,
        List.of("core", "identity"), List.of()
    ));
    assertThat(ctrl.ssoLogoutUrl()).contains(
        "https://auth.home.example.com/logout?rd=https%3A%2F%2Fhome.example.com%2Flogin"
    );
  }
}
