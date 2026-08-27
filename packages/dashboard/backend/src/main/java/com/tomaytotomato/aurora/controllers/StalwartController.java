package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.StalwartAdminService;
import com.tomaytotomato.aurora.services.StalwartAdminService.AdminCredential;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stalwart-specific dashboard surface.
 *
 * <p>Reveals the currently-configured recovery-admin credential for the
 * Stalwart admin console (mail-admin subdomain). Powers the
 * "Show password" panel on {@code /apps/core/services/stalwart} \u2014
 * without it, operators end up shelling in and {@code cat}-ing
 * {@code packages/core/.env}, which defeats the point of having a
 * dashboard.
 *
 * <p><b>Admin-only.</b> Recovery-admin plaintext is a bearer capability:
 * whoever holds it can add or delete mailboxes, rewrite mail routing
 * rules, and see every stored message. It must not leave the dashboard
 * to a non-admin session. Same {@code requireAdmin()} shape every other
 * admin-only endpoint uses.
 *
 * <p><b>Read-only.</b> No rotation surface here. Rotating the recovery
 * admin belongs to {@code rotate-secrets.sh}: it requires recreating
 * the Stalwart container so compose re-interpolates the env, and that
 * touches process supervision far outside this controller's remit.
 * When the operator asks to rotate, this panel points them at that
 * script.
 */
@RestController
@RequestMapping("/api/services/stalwart")
public class StalwartController {

  private final StalwartAdminService stalwart;
  private final CurrentUserService currentUser;

  public StalwartController(StalwartAdminService stalwart, CurrentUserService currentUser) {
    this.stalwart = stalwart;
    this.currentUser = currentUser;
  }

  /**
   * Current recovery-admin credential. Wire shape matches
   * {@link AdminCredential} on purpose so a future generalisation
   * ("every core service that carries an admin secret") only widens the
   * endpoint, never renames its fields.
   */
  @GetMapping("/admin-secret")
  public AdminCredential adminSecret() {
    requireAdmin();
    return stalwart.currentCredential();
  }

  private void requireAdmin() {
    var role = currentUser.currentRole();
    if (role.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in required");
    }
    if (role.get() != Role.ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
    }
  }
}
