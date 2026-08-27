package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.StalwartAdminService;
import com.tomaytotomato.aurora.services.StalwartAdminService.AdminCredential;
import com.tomaytotomato.aurora.services.StalwartSecretsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stalwart-specific dashboard surface.
 *
 * <p>Reveals the currently-configured recovery-admin credential for the
 * Stalwart admin console (mail-admin subdomain) and lets an admin
 * rotate it. Powers the "Recovery admin" panel on
 * {@code /apps/core/services/stalwart} \u2014 without it, operators end up
 * shelling in and {@code cat}-ing {@code packages/core/.env}, which
 * defeats the point of having a dashboard.
 *
 * <p><b>Admin-only.</b> Recovery-admin plaintext is a bearer capability:
 * whoever holds it can add or delete mailboxes, rewrite mail routing
 * rules, and see every stored message. Neither read nor write must
 * leave the dashboard to a non-admin session. Same {@code requireAdmin()}
 * shape every other admin-only endpoint uses.
 *
 * <p><b>Rotation writes .env, not the running container.</b> Compose
 * interpolates the env at container-create time, so a live Stalwart
 * container keeps whatever value it was created with even after the
 * .env changes. The frontend spells that out in the success alert
 * (run {@code ./scripts/up.sh core} to recreate) \u2014 owning the
 * container lifecycle here would drag in LaunchService semantics and
 * belongs in a separate task. What this endpoint owns is the
 * persistence of the new value + the audit trail.
 */
@RestController
@RequestMapping("/api/services/stalwart")
public class StalwartController {

  private static final Logger log = LoggerFactory.getLogger(StalwartController.class);

  private final StalwartAdminService stalwart;
  private final StalwartSecretsService secrets;
  private final CurrentUserService currentUser;

  public StalwartController(StalwartAdminService stalwart,
                            StalwartSecretsService secrets,
                            CurrentUserService currentUser) {
    this.stalwart = stalwart;
    this.secrets = secrets;
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

  /**
   * Rotate the recovery-admin secret. Writes
   * {@code STALWART_ADMIN_SECRET} in {@code packages/core/.env}
   * preserving every other key + comment, audits as
   * {@code stalwart.admin-secret.rotate}, and returns 204. Does NOT
   * echo the plaintext back \u2014 the caller supplied it and the frontend
   * refetches through {@link #adminSecret()} to confirm the write
   * landed.
   *
   * <p>Rejects a body shorter than the {@link StalwartSecretsService}
   * floor as 400. Bean-validation catches the obvious shapes
   * ({@code null}, blank, {@code @Size}); the service also enforces
   * the floor on its own so a non-HTTP caller cannot bypass it.
   */
  @PutMapping("/admin-secret")
  public ResponseEntity<Void> updateAdminSecret(@Valid @RequestBody UpdateReq req) {
    Long acting = requireAdmin();
    try {
      secrets.writeSecret(req.secret(), acting);
    } catch (IllegalArgumentException e) {
      // Service-level floor \u2014 same message the operator sees.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IOException e) {
      log.warn("stalwart admin secret rotate failed: {}", e.getMessage());
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "could not write packages/core/.env");
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Body for {@link #updateAdminSecret(UpdateReq)}. The 12-char floor
   * matches the change-password endpoint \u2014 short enough that
   * operators picking a memorable value are not fought, long enough
   * that "changeme" is refused up front.
   */
  public record UpdateReq(@NotBlank @Size(min = 12) String secret) {}

  private Long requireAdmin() {
    var role = currentUser.currentRole();
    if (role.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in required");
    }
    if (role.get() != Role.ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
    }
    return currentUser.currentUserId().orElse(null);
  }
}
