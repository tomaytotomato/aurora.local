package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * v0.1 auth: username/password login, session cookie, no WebAuthn.
 *
 * <p>Session-only. No JWT — the SPA lives at the same origin and cookies work
 * fine. WebAuthn enrollment is a v0.5 concern (see brief §M5).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final String SESSION_USER = "aurora.user";

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/login")
  public Session login(@Valid @RequestBody LoginReq req, HttpServletRequest request) {
    Optional<AdminUser> user = auth.authenticate(req.username(), req.password());
    if (user.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }
    // Rotate session on login (session fixation protection).
    HttpSession old = request.getSession(false);
    if (old != null) old.invalidate();
    HttpSession session = request.getSession(true);
    session.setAttribute(SESSION_USER, user.get().username());

    var authToken = new UsernamePasswordAuthenticationToken(
        user.get().username(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    SecurityContextHolder.getContext().setAuthentication(authToken);

    return new Session(true, user.get().username(), false, user.get().tz());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  /**
   * Public. Always returns 200 with a Session; when nobody's logged in,
   * {@code authenticated} is false. The SPA reads this on every navigation
   * to decide auth vs onboarding vs login redirects, so it MUST NOT 401.
   */
  @GetMapping({"/session", "/me"})
  public Session session(HttpServletRequest request) {
    HttpSession s = request.getSession(false);
    if (s == null) return Session.anonymous();
    Object u = s.getAttribute(SESSION_USER);
    if (u == null) return Session.anonymous();
    return new Session(true, u.toString(), false, null);
  }

  public record LoginReq(@NotBlank String username, @NotBlank String password) {}

  /** SPA-facing session shape. Stable contract; add fields, never rename. */
  public record Session(boolean authenticated, String username, boolean passkeyEnrolled, String tz) {
    public static Session anonymous() {
      return new Session(false, null, false, null);
    }
  }
}
