package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.domain.MailboxSummary;
import java.util.List;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.PasswordGenerator;
import com.tomaytotomato.aurora.services.StalwartAdminService;
import com.tomaytotomato.aurora.services.StalwartAdminService.AdminCredential;
import com.tomaytotomato.aurora.services.StalwartMailClient;
import com.tomaytotomato.aurora.services.StalwartMailClient.StalwartApiException;
import com.tomaytotomato.aurora.services.StalwartProvisionService;
import com.tomaytotomato.aurora.services.StalwartSecretsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
  private final StalwartMailClient mail;
  private final StalwartProvisionService provision;
  private final AuditEventRepo audit;

  public StalwartController(StalwartAdminService stalwart,
                            StalwartSecretsService secrets,
                            CurrentUserService currentUser,
                            StalwartMailClient mail,
                            StalwartProvisionService provision,
                            AuditEventRepo audit) {
    this.stalwart = stalwart;
    this.secrets = secrets;
    this.currentUser = currentUser;
    this.mail = mail;
    this.provision = provision;
    this.audit = audit;
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

  /**
   * Create the first (or another) mailbox on the box's mail domain.
   * Aurora provisions the domain automatically
   * ({@link StalwartProvisionService}); this is the one genuinely
   * per-operator step — a mailbox needs a password. Aurora generates a
   * strong one and returns it <em>once</em> (the same pattern as the
   * admin-password reset), because it is never stored in plaintext and
   * cannot be shown again.
   *
   * <p>Ensures the domain exists first (idempotent), then creates
   * {@code localPart@domain}. 409 when the mailbox already exists or the
   * password is refused as weak (Stalwart's own check); 502 when Stalwart
   * is unreachable.
   */
  @PostMapping("/mailboxes")
  public ResponseEntity<MailboxCreated> createMailbox(@Valid @RequestBody CreateMailboxReq req) {
    Long acting = requireAdmin();
    String domain = provision.mailDomain();
    String password = PasswordGenerator.generate();
    try {
      mail.ensureDomain(domain);
      String id = mail.createMailbox(req.localPart(), domain, password);
      audit.record(acting, "stalwart.mailbox.create",
          req.localPart() + "@" + domain, "{\"id\":\"" + id + "\"}");
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(new MailboxCreated(req.localPart() + "@" + domain, password));
    } catch (StalwartApiException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      if (msg.contains("unreachable") || msg.contains("JMAP request failed")
          || msg.contains("JMAP HTTP")) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "the mail server is not reachable right now");
      }
      // Already exists, or a weak password Stalwart refused.
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "could not create that mailbox — it may already exist");
    }
  }

  /**
   * Every mailbox on the box. Empty list is a valid 200 (a fresh box has
   * no mailboxes yet), never a 404. 502 when Stalwart is unreachable, so
   * the UI shows a retry rather than an empty table that reads as "0".
   */
  @GetMapping("/mailboxes")
  public List<MailboxSummary> listMailboxes() {
    requireAdmin();
    try {
      return mail.listMailboxes();
    } catch (StalwartApiException e) {
      throw unreachableOrThrow(e, "could not list mailboxes");
    }
  }

  /**
   * Reset a mailbox's password. Generates a strong one and returns it
   * once (the same one-time-reveal contract as create). 404 when the
   * mailbox id is unknown; 502 when Stalwart is unreachable.
   */
  @PostMapping("/mailboxes/{id}/reset-password")
  public MailboxCreated resetMailboxPassword(@PathVariable String id) {
    Long acting = requireAdmin();
    String password = PasswordGenerator.generate();
    try {
      mail.resetMailboxPassword(id, password);
      audit.record(acting, "stalwart.mailbox.reset-password", "mailbox:" + id, null);
      // Address is looked up so the reveal panel can name it; best-effort.
      String address = mail.listMailboxes().stream()
          .filter(m -> id.equals(m.id())).map(MailboxSummary::address).findFirst().orElse(id);
      return new MailboxCreated(address, password);
    } catch (StalwartApiException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      if (isUnreachable(msg)) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "the mail server is not reachable right now");
      }
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such mailbox");
    }
  }

  /**
   * Delete a mailbox and all its mail. Irreversible. 404 when unknown;
   * 502 when Stalwart is unreachable.
   */
  @DeleteMapping("/mailboxes/{id}")
  public ResponseEntity<Void> deleteMailbox(@PathVariable String id) {
    Long acting = requireAdmin();
    try {
      mail.deleteMailbox(id);
      audit.record(acting, "stalwart.mailbox.delete", "mailbox:" + id, null);
      return ResponseEntity.noContent().build();
    } catch (StalwartApiException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      if (isUnreachable(msg)) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "the mail server is not reachable right now");
      }
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such mailbox");
    }
  }

  private static boolean isUnreachable(String msg) {
    return msg.contains("unreachable") || msg.contains("JMAP request failed")
        || msg.contains("JMAP HTTP");
  }

  private static ResponseStatusException unreachableOrThrow(StalwartApiException e, String ctx) {
    String msg = e.getMessage() == null ? "" : e.getMessage();
    if (isUnreachable(msg)) {
      return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "the mail server is not reachable right now");
    }
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ctx);
  }

  /**
   * Request for {@link #createMailbox(CreateMailboxReq)}. Only the local
   * part — the domain is the box's own, and the password is generated so
   * it is strong enough to pass Stalwart's own strength check.
   */
  public record CreateMailboxReq(
      @NotBlank @Size(max = 64)
      @Pattern(regexp = "^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$",
          message = "use lowercase letters, numbers, dot, dash or underscore")
      String localPart) {}

  /** The created address and its one-time password. */
  public record MailboxCreated(String email, String password) {}

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
