package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.services.AuthService;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.SessionService;
import com.tomaytotomato.aurora.services.StateFileService;
import com.tomaytotomato.aurora.services.UsersService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

  private final AuthService auth;
  private final StateFileService stateFiles;
  private final SessionService sessions;
  private final CurrentUserService currentUser;
  private final UsersService users;

  public AuthController(AuthService auth, StateFileService stateFiles,
                        SessionService sessions, CurrentUserService currentUser,
                        UsersService users) {
    this.auth = auth;
    this.stateFiles = stateFiles;
    this.sessions = sessions;
    this.currentUser = currentUser;
    this.users = users;
  }

  @PostMapping("/login")
  public Session login(@Valid @RequestBody LoginReq req, HttpServletRequest request) {
    Optional<AdminUser> user = auth.authenticate(req.username(), req.password());
    if (user.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }
    // Rotates the session (fixation protection) and sets the Spring
    // Security authentication token — see SessionService for why this is
    // shared with the onboarding-completion path.
    sessions.establish(user.get(), request);

    return new Session(true, user.get().username(), false, user.get().tz(),
        user.get().role().wireName());
  }

  @PostMapping("/logout")
  public LogoutResponse logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();

    // Phase D iter-14 (D13): Authelia ships in core and is always-on, so
    // we always return the Authelia logout URL (when a domain is set) so
    // the SPA can bounce through it after killing the Aurora session.
    // Otherwise there's a shared .{DOMAIN} `authelia_session` cookie
    // sitting in the browser that outlives the Aurora sign-out — a
    // shared-computer next-user could walk into notes.aurora.local
    // without a login prompt.
    String next = ssoLogoutUrl().orElse(null);
    return new LogoutResponse(next);
  }

  /**
   * Compute {@code https://auth.{DOMAIN}/logout?rd=https://{DOMAIN}/login}
   * when core (and therefore Authelia SSO) is enabled AND we have a
   * domain. Empty otherwise. Package-private for tests.
   */
  java.util.Optional<String> ssoLogoutUrl() {
    RepoState state = stateFiles.readState();
    if (state.enabled() == null || !state.enabled().contains("core")) {
      return java.util.Optional.empty();
    }
    String domain = state.domain();
    if (domain == null || domain.isBlank()) return java.util.Optional.empty();
    String redirect = "https://" + domain + "/login";
    String url = "https://auth." + domain + "/logout?rd="
        + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8);
    return java.util.Optional.of(url);
  }

  /** SPA-visible logout payload. Backward-compat: next may be null. */
  public record LogoutResponse(String next) {}

  /**
   * Public. Always returns 200 with a Session; when nobody's logged in,
   * {@code authenticated} is false. The SPA reads this on every navigation
   * to decide auth vs onboarding vs login redirects, so it MUST NOT 401.
   */
  @GetMapping({"/session", "/me"})
  public Session session(HttpServletRequest request) {
    HttpSession s = request.getSession(false);
    if (s == null) return Session.anonymous();
    Object u = s.getAttribute(SessionService.SESSION_USER);
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
   * Self-service password change for the currently signed-in operator.
   *
   * <p><b>Why not reuse {@code POST /users/{id}/password}.</b> That
   * endpoint is for admin-driven rotation — an admin rotates someone
   * else's password, hands them the new value, and the plaintext is
   * echoed back to the admin (see {@code UsersController.resetPassword}).
   * The security shape here is opposite: the caller supplies both the
   * old and the new plaintext themselves, we verify the old before
   * writing the new, and we return nothing. Conflating the two would
   * either leak echo of self-chosen passwords into API responses or
   * require the admin path to ask for the current password nobody
   * knows.
   *
   * <p><b>Why require the current password.</b> A stolen session
   * cookie is an existing threat we already model against (secure,
   * HttpOnly, SameSite=Lax); requiring the current password on rotate
   * caps the blast radius of that theft — the attacker still cannot
   * lock the real operator out of their own box. Same reason every
   * other password-change flow on the internet does this.
   *
   * <p>Return shape is deliberately empty on success: nothing to log,
   * nothing to echo, no plaintext ever leaves this method. The audit
   * row lives in {@link UsersService#rotatePassword} where every other
   * password rotation on the box is recorded.
   */
  @PostMapping("/password")
  public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordReq req) {
    String username = currentUser.currentUsername()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not signed in"));
    Optional<AdminUser> user = auth.authenticate(username, req.currentPassword());
    if (user.isEmpty()) {
      // Deliberately not "wrong password" — same 401 the login route
      // would return, same amount of information.
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "current password is wrong");
    }
    if (req.newPassword() == null || req.newPassword().length() < 12) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "new password must be at least 12 characters");
    }
    if (req.newPassword().equals(req.currentPassword())) {
      // Not a hard invariant of the auth model, but a good ergonomic
      // guard — an operator who "rotates" to the same password is
      // usually the victim of a form-fill mistake. Cheap to catch, and
      // the message tells them what happened.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "new password matches the current one");
    }
    try {
      users.rotatePassword(user.get().id(), req.newPassword().toCharArray(),
          user.get().id());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.noContent().build();
  }

  public record ChangePasswordReq(
      @NotBlank String currentPassword,
      @NotBlank String newPassword
  ) {}

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
