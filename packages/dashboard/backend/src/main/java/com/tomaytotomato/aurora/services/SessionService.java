package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Establishes an authenticated session for a known {@link AdminUser}.
 *
 * <p>Extracted out of {@link com.tomaytotomato.aurora.controllers.AuthController}
 * so there is exactly one place that writes the session-user attribute and
 * the Spring Security authentication token. Two call sites need this:
 * <ul>
 *   <li>{@code POST /api/auth/login} — the normal path, after verifying a
 *       password.</li>
 *   <li>{@code POST /api/onboarding/complete} — the wizard's one-shot
 *       finish, so the operator who just created the admin account and
 *       watched the box start lands in the dashboard instead of a login
 *       form. That endpoint only ever reaches this call after
 *       {@code OnboardingService#guardMidOnboarding()} has confirmed an
 *       admin exists and onboarding is not already complete, so this
 *       grants nothing the caller could not already get by logging in
 *       with the credentials they themselves just chose.</li>
 * </ul>
 */
@Service
public class SessionService {

  /** HttpSession attribute key carrying the logged-in username. */
  public static final String SESSION_USER = "aurora.user";

  /**
   * Rotate the session (fixation protection) and mark {@code user} as the
   * authenticated principal for the rest of this request and every one
   * that follows on the same cookie.
   */
  public void establish(AdminUser user, HttpServletRequest request) {
    HttpSession old = request.getSession(false);
    if (old != null) old.invalidate();
    HttpSession session = request.getSession(true);
    session.setAttribute(SESSION_USER, user.username());

    String authority = "ROLE_" + user.role().name();
    var authToken = new UsernamePasswordAuthenticationToken(
        user.username(), null, List.of(new SimpleGrantedAuthority(authority)));
    SecurityContextHolder.getContext().setAuthentication(authToken);
  }
}
