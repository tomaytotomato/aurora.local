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
import java.util.Map;
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
  public Map<String, Object> login(@Valid @RequestBody LoginReq req, HttpServletRequest request) {
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

    return Map.of("username", user.get().username(), "id", user.get().id());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    Object u = session.getAttribute(SESSION_USER);
    if (u == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    return ResponseEntity.ok(Map.of("username", u.toString()));
  }

  public record LoginReq(@NotBlank String username, @NotBlank String password) {}
}
