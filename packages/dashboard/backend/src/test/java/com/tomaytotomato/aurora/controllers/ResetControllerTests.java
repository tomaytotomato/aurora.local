package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.ResetService;
import com.tomaytotomato.aurora.services.ResetService.ResetHelperFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "start over" endpoint. What we pin here:
 *
 * <ol>
 *   <li>It is admin-only, same shape as every other destructive route:
 *       unauthenticated → 401, non-admin → 403, no helper touched.</li>
 *   <li>The confirmation word is compulsory and case-exact — a body
 *       that says {@code "reset"} or {@code "please"} must 400 without
 *       ever calling the service.</li>
 *   <li>A helper failure (docker refused the {@code run}) must surface
 *       as a 500 with the operator-facing copy from the service, not a
 *       stack trace and not a false-positive 202.</li>
 *   <li>The happy path is 202 Accepted (the work runs in a helper
 *       container after this response returns), and the acting user id
 *       is threaded through to the audit row via the service.</li>
 * </ol>
 */
class ResetControllerTests {

  private CurrentUserService currentUser;
  private ResetService reset;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    currentUser = Mockito.mock(CurrentUserService.class);
    reset = Mockito.mock(ResetService.class);
    ResetController ctrl = new ResetController(reset, currentUser);
    mvc = MockMvcBuilders.standaloneSetup(ctrl).build();
  }

  @Test
  void unauthenticated_request_is_401_and_helper_stays_untouched() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.empty());
    mvc.perform(post("/api/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":\"RESET\"}"))
        .andExpect(status().isUnauthorized());
    Mockito.verify(reset, Mockito.never()).start(Mockito.any());
  }

  @Test
  void non_admin_user_is_403() throws Exception {
    // A regular USER can sign in but must not be able to wipe the box.
    // Same reasoning as StalwartController's admin-secret write.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.USER));
    mvc.perform(post("/api/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":\"RESET\"}"))
        .andExpect(status().isForbidden());
    Mockito.verify(reset, Mockito.never()).start(Mockito.any());
  }

  @Test
  void wrong_confirmation_word_is_400_and_helper_is_not_called() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(7L));

    mvc.perform(post("/api/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":\"please\"}"))
        .andExpect(status().isBadRequest());

    Mockito.verify(reset, Mockito.never()).start(Mockito.any());
  }

  @Test
  void lowercase_reset_is_rejected() throws Exception {
    // The frontend types the word verbatim; a case-insensitive backend
    // would be a footgun the frontend could not detect.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(7L));

    mvc.perform(post("/api/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":\"reset\"}"))
        .andExpect(status().isBadRequest());

    Mockito.verify(reset, Mockito.never()).start(Mockito.any());
  }

  @Test
  void empty_body_is_400() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(7L));

    mvc.perform(post("/api/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":\"\"}"))
        .andExpect(status().isBadRequest());

    Mockito.verify(reset, Mockito.never()).start(Mockito.any());
  }

  @Test
  void helper_failure_surfaces_as_500_with_the_service_message() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(7L));
    Mockito.when(reset.start(7L)).thenThrow(
        new ResetHelperFailedException("docker refused to start the reset helper (exit 125)"));

    mvc.perform(post("/api/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":\"RESET\"}"))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void happy_path_is_202_and_returns_the_helper_id() throws Exception {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(7L));
    Mockito.when(reset.start(7L)).thenReturn("abc123def4");

    mvc.perform(post("/api/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":\"RESET\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.helperId").value("abc123def4"));

    // Acting user id has to be threaded through so the audit row is
    // attributed — anonymous destruction of the box is not acceptable.
    Mockito.verify(reset).start(7L);
  }
}
