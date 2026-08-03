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

    // Spring Security authority mirrors the DB role. Phase D grew the
    // role model beyond ROLE_ADMIN; the authority string is the DB
    // role uppercased with 'ROLE_' prefix so downstream @PreAuthorize
    // (if we ever adopt it) reads naturally.
    String authority = "ROLE_" + user.get().role().name();
    var authToken = new UsernamePasswordAuthenticationToken(
        user.get().username(), null, List.of(new SimpleGrantedAuthority(authority)));
    SecurityContextHolder.getContext().setAuthentication(authToken);

    return new Session(true, user.get().username(), false, user.get().tz(),
        user.get().role().wireName());
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
    // Fetch role fresh from DB every time so a role change (Phase D
    // /api/users PUT flip) takes effect on the next request without
    // needing a re-login. Falls back to null-role when the DB row
    // disappears out from under the session (rare, but possible via
    // /api/onboarding/reset in E2E mode).
    String username = u.toString();
    String role = auth.roleFor(username).map(r -> r.wireName()).orElse(null);
    String tz = auth.tzFor(username).orElse(null);
    return new Session(true, username, false, tz, role);
  }

  public record LoginReq(@NotBlank String username, @NotBlank String password) {}

  /**
   * SPA-facing session shape. Stable contract; add fields, never rename.
   * Phase D grew {@code role} (D8) so the frontend can gate the /users
   * sidebar link + admin-only views.
   */
  public record Session(
      boolean authenticated,
      String username,
      boolean passkeyEnrolled,
      String tz,
      String role
  ) {
    public static Session anonymous() {
      return new Session(false, null, false, null, null);
    }
  }
}
