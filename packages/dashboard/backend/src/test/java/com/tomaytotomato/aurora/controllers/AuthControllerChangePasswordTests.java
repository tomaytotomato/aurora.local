package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.AuthService;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.SessionService;
import com.tomaytotomato.aurora.services.StateFileService;
import com.tomaytotomato.aurora.services.UsersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The self-service password-change endpoint.
 *
 * <p>The security shape that these tests pin: the caller must verify
 * their current password (not just their session cookie), the new
 * password must not be trivially short or the same as the old one, and
 * on success the rotation goes through {@link UsersService} so the
 * standard audit trail catches it.
 *
 * <p>Bug context: caught during the 27 Aug 2026 QA sweep. The Security
 * page's "Fix it \u2192" links pointed at {@code /settings#account} but the
 * Account card had a Sign out button and nothing else. This endpoint
 * is what makes those links usable.
 */
class AuthControllerChangePasswordTests {

  private AuthService auth;
  private CurrentUserService currentUser;
  private UsersService users;
  private MockMvc mvc;

  private static final AdminUser BRUCE = new AdminUser(
      42, "bruce", "$2a$12$hash", "UTC",
      "2026-08-01T00:00:00Z", Role.ADMIN);

  @BeforeEach
  void setUp() {
    auth = Mockito.mock(AuthService.class);
    currentUser = Mockito.mock(CurrentUserService.class);
    users = Mockito.mock(UsersService.class);
    AuthController ctrl = new AuthController(
        auth,
        Mockito.mock(StateFileService.class),
        new SessionService(),
        currentUser,
        users,
        Mockito.mock(com.tomaytotomato.aurora.services.RecoveryCodeService.class));
    mvc = MockMvcBuilders.standaloneSetup(ctrl).build();
  }

  @Test
  void unauthenticated_call_is_401() throws Exception {
    Mockito.when(currentUser.currentUsername()).thenReturn(Optional.empty());
    mvc.perform(post("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"a-brand-new-one\"}"))
        .andExpect(status().isUnauthorized());
    Mockito.verify(users, Mockito.never()).rotatePassword(anyLong(), any(), any());
  }

  @Test
  void wrong_current_password_is_401_and_does_not_rotate() throws Exception {
    Mockito.when(currentUser.currentUsername()).thenReturn(Optional.of("bruce"));
    // authenticate() returns empty when the password does not verify.
    Mockito.when(auth.authenticate(eq("bruce"), any())).thenReturn(Optional.empty());

    mvc.perform(post("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"a-brand-new-one\"}"))
        .andExpect(status().isUnauthorized());
    Mockito.verify(users, Mockito.never()).rotatePassword(anyLong(), any(), any());
  }

  @Test
  void new_password_below_12_chars_is_400() throws Exception {
    Mockito.when(currentUser.currentUsername()).thenReturn(Optional.of("bruce"));
    Mockito.when(auth.authenticate(eq("bruce"), any())).thenReturn(Optional.of(BRUCE));

    mvc.perform(post("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"short\"}"))
        .andExpect(status().isBadRequest());
    Mockito.verify(users, Mockito.never()).rotatePassword(anyLong(), any(), any());
  }

  @Test
  void new_matching_old_is_400() throws Exception {
    // Ergonomic guard: a caller who "rotates" to the same value has
    // almost certainly hit a form-fill mistake. The auth model would
    // accept it, but blocking it early gives a much better error.
    Mockito.when(currentUser.currentUsername()).thenReturn(Optional.of("bruce"));
    Mockito.when(auth.authenticate(eq("bruce"), any())).thenReturn(Optional.of(BRUCE));

    mvc.perform(post("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"same-old-value-here\",\"newPassword\":\"same-old-value-here\"}"))
        .andExpect(status().isBadRequest());
    Mockito.verify(users, Mockito.never()).rotatePassword(anyLong(), any(), any());
  }

  @Test
  void successful_change_returns_204_and_rotates_via_UsersService() throws Exception {
    Mockito.when(currentUser.currentUsername()).thenReturn(Optional.of("bruce"));
    Mockito.when(auth.authenticate(eq("bruce"), any())).thenReturn(Optional.of(BRUCE));

    mvc.perform(post("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"a-brand-new-one\"}"))
        .andExpect(status().isNoContent());

    // Rotation goes through the standard path so the standard audit
    // row lands \u2014 no bespoke logging in the controller.
    ArgumentCaptor<Long> targetId = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Long> actingId = ArgumentCaptor.forClass(Long.class);
    Mockito.verify(users).rotatePassword(targetId.capture(), any(), actingId.capture());
    org.assertj.core.api.Assertions.assertThat(targetId.getValue()).isEqualTo(42L);
    // Self-service: the target and the actor are the same operator.
    org.assertj.core.api.Assertions.assertThat(actingId.getValue()).isEqualTo(42L);
  }

  @Test
  void unusable_new_password_from_UsersService_is_400() throws Exception {
    // UsersService.rotatePassword has its own validate step (entropy,
    // shape, banned words). When that raises, the controller must not
    // 500 the client \u2014 the user just needs to try a different value.
    Mockito.when(currentUser.currentUsername()).thenReturn(Optional.of("bruce"));
    Mockito.when(auth.authenticate(eq("bruce"), any())).thenReturn(Optional.of(BRUCE));
    Mockito.doThrow(new IllegalArgumentException("password contains username"))
        .when(users).rotatePassword(anyLong(), any(), anyLong());

    mvc.perform(post("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"bruce-and-more\"}"))
        .andExpect(status().isBadRequest());
  }
}
