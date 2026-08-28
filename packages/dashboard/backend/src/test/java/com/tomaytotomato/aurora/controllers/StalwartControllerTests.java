package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.StalwartAdminService;
import com.tomaytotomato.aurora.services.StalwartAdminService.AdminCredential;
import com.tomaytotomato.aurora.services.StalwartAdminService.Source;
import com.tomaytotomato.aurora.services.StalwartSecretsService;
import com.tomaytotomato.aurora.services.StalwartMailClient;
import com.tomaytotomato.aurora.services.StalwartProvisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reveal-panel + rotation endpoints. Things worth pinning:
 *
 * <ol>
 *   <li>The bearer capability is the plaintext; anything less than an
 *       authenticated admin session must not see it OR rotate it.
 *       Non-admin roles have to 403, and an unauthenticated caller has
 *       to 401 with the same shape as every other admin-only route.</li>
 *   <li>The read controller must not manufacture facts about the value
 *       \u2014 it is just a proxy over
 *       {@link StalwartAdminService#currentCredential()}. The
 *       reveal-vs-default classification is the service's, not the
 *       controller's.</li>
 *   <li>The write controller must never echo the plaintext back and
 *       must pass the acting user id through to the audit row so
 *       rotations are attributed.</li>
 * </ol>
 */
class StalwartControllerTests {

  private CurrentUserService currentUser;
  private StalwartAdminService stalwart;
  private StalwartSecretsService secrets;
  private StalwartMailClient mail;
  private StalwartProvisionService provision;
  private com.tomaytotomato.aurora.persistence.AuditEventRepo audit;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    currentUser = Mockito.mock(CurrentUserService.class);
    stalwart = Mockito.mock(StalwartAdminService.class);
    secrets = Mockito.mock(StalwartSecretsService.class);
    mail = Mockito.mock(StalwartMailClient.class);
    provision = Mockito.mock(StalwartProvisionService.class);
    audit = Mockito.mock(com.tomaytotomato.aurora.persistence.AuditEventRepo.class);
    StalwartController ctrl = new StalwartController(stalwart, secrets, currentUser, mail, provision, audit);
    mvc = MockMvcBuilders.standaloneSetup(ctrl).build();
  }

  // \u2500\u2500\u2500 GET /admin-secret \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

  // \u2500\u2500\u2500 PUT /admin-secret \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @Test
  void put_admin_secret_unauthenticated_is_401() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.empty());
    mvc.perform(put("/api/services/stalwart/admin-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"secret\":\"a-strong-value-here\"}"))
        .andExpect(status().isUnauthorized());
    Mockito.verifyNoInteractions(secrets);
  }

  @Test
  void put_admin_secret_non_admin_is_403() throws Exception {
    // Rotation is a write and admin-only \u2014 same bearer-capability
    // reasoning as the read side.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.USER));
    mvc.perform(put("/api/services/stalwart/admin-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"secret\":\"a-strong-value-here\"}"))
        .andExpect(status().isForbidden());
    Mockito.verifyNoInteractions(secrets);
  }

  @Test
  void put_admin_secret_short_value_is_400() throws Exception {
    // 12-char floor \u2014 matches the change-password endpoint. Bean
    // validation catches this before the service is ever asked, so
    // the service mock stays untouched.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    mvc.perform(put("/api/services/stalwart/admin-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"secret\":\"short\"}"))
        .andExpect(status().isBadRequest());
    Mockito.verifyNoInteractions(secrets);
  }

  @Test
  void put_admin_secret_blank_value_is_400() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    mvc.perform(put("/api/services/stalwart/admin-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"secret\":\"\"}"))
        .andExpect(status().isBadRequest());
    Mockito.verifyNoInteractions(secrets);
  }

  @Test
  void put_admin_secret_success_returns_204_and_passes_acting_user() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(42L));

    mvc.perform(put("/api/services/stalwart/admin-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"secret\":\"brand-new-strong-value\"}"))
        .andExpect(status().isNoContent());

    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> acting = ArgumentCaptor.forClass(Long.class);
    Mockito.verify(secrets).writeSecret(value.capture(), acting.capture());
    assertThat(value.getValue()).isEqualTo("brand-new-strong-value");
    assertThat(acting.getValue()).isEqualTo(42L);
  }

  @Test
  void put_admin_secret_service_level_illegal_arg_maps_to_400() throws Exception {
    // Belt + braces: even if bean validation is misconfigured, the
    // service-level floor still keeps a bad value out of .env. The
    // controller must not 500 in that case.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
    Mockito.doThrow(new IllegalArgumentException("recovery-admin password must be at least 12 characters"))
        .when(secrets).writeSecret(Mockito.any(), Mockito.any());

    mvc.perform(put("/api/services/stalwart/admin-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"secret\":\"twelve-chars\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void put_admin_secret_io_failure_is_500() throws Exception {
    // A truly unwritable .env is a real system-level failure, not a
    // client error. 500 surfaces that honestly.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
    Mockito.doThrow(new IOException("permission denied"))
        .when(secrets).writeSecret(Mockito.any(), Mockito.any());

    mvc.perform(put("/api/services/stalwart/admin-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"secret\":\"brand-new-strong-value\"}"))
        .andExpect(status().isInternalServerError());
  }

  // ─── POST /mailboxes ──────────────────────────────────────

  @Test
  void create_mailbox_requires_admin() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.USER));
    mvc.perform(post("/api/services/stalwart/mailboxes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"localPart\":\"bruce\"}"))
        .andExpect(status().isForbidden());
    Mockito.verifyNoInteractions(mail);
  }

  @Test
  void create_mailbox_returns_the_address_and_a_one_time_password() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
    Mockito.when(provision.mailDomain()).thenReturn("aurora.local");
    Mockito.when(mail.createMailbox(Mockito.eq("bruce"), Mockito.eq("aurora.local"), Mockito.anyString()))
        .thenReturn("acct-id");

    mvc.perform(post("/api/services/stalwart/mailboxes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"localPart\":\"bruce\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("bruce@aurora.local"))
        .andExpect(jsonPath("$.password").isNotEmpty());
    Mockito.verify(mail).ensureDomain("aurora.local");
  }

  @Test
  void create_mailbox_rejects_a_bad_local_part() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
    mvc.perform(post("/api/services/stalwart/mailboxes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"localPart\":\"Not Valid!\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_mailbox_maps_stalwart_unreachable_to_502() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
    Mockito.when(provision.mailDomain()).thenReturn("aurora.local");
    Mockito.doThrow(new StalwartMailClient.StalwartApiException("JMAP request failed: connection refused"))
        .when(mail).ensureDomain("aurora.local");
    mvc.perform(post("/api/services/stalwart/mailboxes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"localPart\":\"bruce\"}"))
        .andExpect(status().isBadGateway());
  }

  @Test
  void create_mailbox_maps_already_exists_to_409() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
    Mockito.when(provision.mailDomain()).thenReturn("aurora.local");
    Mockito.when(mail.createMailbox(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenThrow(new StalwartMailClient.StalwartApiException("could not create mailbox: primaryKeyViolation"));
    mvc.perform(post("/api/services/stalwart/mailboxes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"localPart\":\"bruce\"}"))
        .andExpect(status().isConflict());
  }
}
