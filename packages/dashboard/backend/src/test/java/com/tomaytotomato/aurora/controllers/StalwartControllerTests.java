package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.StalwartAdminService;
import com.tomaytotomato.aurora.services.StalwartAdminService.AdminCredential;
import com.tomaytotomato.aurora.services.StalwartAdminService.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reveal-panel endpoint. Two things worth pinning:
 *
 * <ol>
 *   <li>The bearer capability is the plaintext; anything less than an
 *       authenticated admin session must not see it. Non-admin roles
 *       have to 403, and an unauthenticated caller has to 401 with the
 *       same shape as every other admin-only route.</li>
 *   <li>The controller must not manufacture facts about the value \u2014 it
 *       is just a proxy over {@link StalwartAdminService#currentCredential()}.
 *       The reveal-vs-default classification is the service's, not the
 *       controller's.</li>
 * </ol>
 */
class StalwartControllerTests {

  private CurrentUserService currentUser;
  private StalwartAdminService stalwart;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    currentUser = Mockito.mock(CurrentUserService.class);
    stalwart = Mockito.mock(StalwartAdminService.class);
    StalwartController ctrl = new StalwartController(stalwart, currentUser);
    mvc = MockMvcBuilders.standaloneSetup(ctrl).build();
  }

  @Test
  void unauthenticated_request_is_401() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.empty());
    mvc.perform(get("/api/services/stalwart/admin-secret"))
        .andExpect(status().isUnauthorized());
    Mockito.verifyNoInteractions(stalwart);
  }

  @Test
  void non_admin_user_is_403() throws Exception {
    // A logged-in USER can reach /apps/core/services/stalwart but must
    // not see the recovery-admin plaintext. The bearer capability
    // belongs to admins.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.USER));
    mvc.perform(get("/api/services/stalwart/admin-secret"))
        .andExpect(status().isForbidden());
    Mockito.verifyNoInteractions(stalwart);
  }

  @Test
  void guest_role_is_also_403() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.GUEST));
    mvc.perform(get("/api/services/stalwart/admin-secret"))
        .andExpect(status().isForbidden());
    Mockito.verifyNoInteractions(stalwart);
  }

  @Test
  void admin_gets_the_env_credential_verbatim() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(stalwart.currentCredential()).thenReturn(
        new AdminCredential("admin", "abc123-a-real-strong-value", Source.ENV));

    mvc.perform(get("/api/services/stalwart/admin-secret"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.secret").value("abc123-a-real-strong-value"))
        .andExpect(jsonPath("$.source").value("ENV"));
  }

  @Test
  void admin_sees_DEFAULT_flagged_when_env_is_blank() throws Exception {
    // The compose default is an every-box-knows-it value; the source
    // field is what the panel uses to render the "rotate me" warning.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(stalwart.currentCredential()).thenReturn(
        new AdminCredential("admin", "aurora-change-me", Source.DEFAULT));

    mvc.perform(get("/api/services/stalwart/admin-secret"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("DEFAULT"))
        .andExpect(jsonPath("$.secret").value("aurora-change-me"));
  }
}
