package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.PasswordGenerator;
import com.tomaytotomato.aurora.services.StalwartMailClient;
import com.tomaytotomato.aurora.services.StalwartMailClient.StalwartApiException;
import com.tomaytotomato.aurora.services.StalwartProvisionService;
import com.tomaytotomato.aurora.services.UsersService;
import com.tomaytotomato.aurora.services.UsersService.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Phase D iter-9 (D8) \u2014 admin-only user management.
 *
 * <p>Every mutating endpoint calls {@link #requireAdmin()} before
 * touching the DB. The guard reads the caller's role from the
 * database on every request (via
 * {@link CurrentUserService#currentRole()}) so a demote / delete
 * takes effect on the next request without needing a session rotate.
 *
 * <p>The read endpoints ({@link #list()}, {@link #get(long)}) also
 * require admin \u2014 user identities are internal signal that a
 * regular USER shouldn't be able to enumerate.
 *
 * <p>Error surface:
 * <ul>
 *   <li>401 when the caller is unauthenticated.</li>
 *   <li>403 when the caller is authenticated but not admin.</li>
 *   <li>400 for bad input (username shape, weak password, missing role).</li>
 *   <li>409 for username collision.</li>
 *   <li>422 for invariant violations (last-admin demote/delete).</li>
 *   <li>404 for missing user id.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users")
public class UsersController {

  private static final Logger log = LoggerFactory.getLogger(UsersController.class);

  private final UsersService users;
  private final CurrentUserService currentUser;
  private final StalwartMailClient mail;
  private final StalwartProvisionService provision;

  public UsersController(UsersService users, CurrentUserService currentUser,
                         StalwartMailClient mail, StalwartProvisionService provision) {
    this.users = users;
    this.currentUser = currentUser;
    this.mail = mail;
    this.provision = provision;
  }

  // \u2500\u2500\u2500 read ────────────────────────────────────────────────────────────

  @GetMapping
  public List<UserSummary> list() {
    requireAdmin();
    return users.list();
  }

  @GetMapping("/{id}")
  public UserSummary get(@PathVariable long id) {
    requireAdmin();
    return users.findById(id).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user"));
  }

  // ─── mutate ────────────────────────────────────────────────────────────

  @PostMapping
  public ResponseEntity<CreatedUser> create(@Valid @RequestBody CreateReq req) {
    Long actor = requireAdmin();
    try {
      Role role = parseRole(req.role());
      // Requirement: Aurora generates the password unless one is given.
      // An admin-chosen password is necessarily known to the admin and
      // tends to travel by chat or paper and never get changed; a
      // generated one is handed over once and can be replaced. Blank is
      // treated as absent so an empty form field means "generate",
      // rather than failing validation on "".
      boolean generated = req.password() == null || req.password().isBlank();
      String password = generated ? PasswordGenerator.generate() : req.password();

      UserSummary created = users.create(
          req.username(), password.toCharArray(), role, req.tz(), actor);

      // Auto-provision a mailbox for the new user (the story). Default
      // address is <username>@<box-domain>; the admin can override the
      // address, or opt out entirely. CRUCIALLY the mailbox password is
      // the SAME password the user got, so "the password you set is your
      // mail password" holds — one credential, whether generated or
      // admin-chosen. Best-effort and NON-fatal: the user account is the
      // primary artifact, and a mail server that is down or a password
      // Stalwart rejects as too weak must not fail user creation. The
      // outcome is reported so the UI can tell the admin what happened.
      MailboxOutcome mailbox = maybeProvisionMailbox(req, created.username(), password, actor);

      // Returned exactly once, and only when we generated it. We never
      // echo a password the caller already knows, and there is no second
      // chance to read it: nothing stores the plaintext, so a lost value
      // means a rotation, not a lookup.
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(new CreatedUser(created, generated ? password : null, mailbox));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "username already taken");
    }
  }

  /**
   * Create the new user's mailbox, best-effort. Returns a
   * {@link MailboxOutcome} describing what happened; never throws, so a
   * mail failure can't undo a successful user create.
   *
   * <p>Off by {@code createMailbox=false}. Otherwise the address is the
   * admin-supplied {@code email} or the default {@code <username>@<domain>}.
   * A supplied address may be a bare local part (mailbox on the box
   * domain) or a full {@code local@domain}. The mailbox password is the
   * user's own password — the whole point of the story.
   */
  private MailboxOutcome maybeProvisionMailbox(CreateReq req, String username,
                                               String password, Long actor) {
    if (Boolean.FALSE.equals(req.createMailbox())) {
      return new MailboxOutcome(false, null, false, null);
    }
    String domain = provision.mailDomain();
    String local;
    String targetDomain;
    String supplied = req.email() == null ? null : req.email().trim();
    if (supplied == null || supplied.isBlank()) {
      local = username;
      targetDomain = domain;
    } else if (supplied.contains("@")) {
      int at = supplied.indexOf('@');
      local = supplied.substring(0, at);
      targetDomain = supplied.substring(at + 1);
    } else {
      local = supplied;
      targetDomain = domain;
    }
    String email = local + "@" + targetDomain;
    try {
      mail.ensureDomain(targetDomain);
      mail.createMailbox(local, targetDomain, password);
      // Audit under users.* so the mailbox shows up on the user's trail,
      // not just the Stalwart one.
      log.info("users: auto-created mailbox {} for new user {}", email, username);
      return new MailboxOutcome(true, email, true, null);
    } catch (StalwartApiException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      String reason;
      if (msg.contains("JMAP request failed") || msg.contains("JMAP HTTP")
          || msg.contains("unreachable")) {
        reason = "the mail server is not reachable right now";
      } else if (msg.toLowerCase().contains("weak")) {
        reason = "the password is too weak for a mailbox";
      } else {
        reason = "a mailbox for that address may already exist";
      }
      log.warn("users: could not auto-create mailbox {} for {}: {}", email, username, msg);
      return new MailboxOutcome(true, email, false, reason);
    }
  }

  /**
   * Rotate a password, generating one when the caller supplies none.
   *
   * <p>Separate from {@code PUT /{id}} because that endpoint patches role
   * and password together and returns the user; this one has to return a
   * secret, and conflating "here is the updated user" with "here is a
   * plaintext credential" invites the latter into logs and caches that
   * only ever expected the former.
   */
  @PostMapping("/{id}/password")
  public GeneratedPassword resetPassword(@PathVariable long id, @RequestBody(required = false) ResetReq req) {
    Long actor = requireAdmin();
    boolean generated = req == null || req.password() == null || req.password().isBlank();
    String password = generated ? PasswordGenerator.generate() : req.password();
    try {
      users.rotatePassword(id, password.toCharArray(), actor);
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("no such user")) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
      }
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return new GeneratedPassword(generated ? password : null, generated);
  }

  @PutMapping("/{id}")
  public UserSummary update(@PathVariable long id, @Valid @RequestBody UpdateReq req) {
    Long actor = requireAdmin();
    try {
      UserSummary updated = users.findById(id).orElseThrow(() ->
          new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user"));
      if (req.role() != null && !req.role().isBlank()) {
        Role r = parseRole(req.role());
        updated = users.updateRole(id, r, actor);
      }
      if (req.password() != null && !req.password().isEmpty()) {
        users.rotatePassword(id, req.password().toCharArray(), actor);
      }
      return users.findById(id).orElse(updated);
    } catch (IllegalArgumentException e) {
      // Distinguish "no such user" (404, thrown above) from "bad input" (400).
      if (e.getMessage() != null && e.getMessage().startsWith("no such user")) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
      }
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    Long actor = requireAdmin();
    try {
      users.delete(id, actor);
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
  }

  // ─── guards ────────────────────────────────────────────────────────────

  /**
   * Returns the acting user's id when the request is authenticated as
   * an admin. Throws 401 unauthenticated or 403 for non-admin \u2014 the
   * two failure modes an operator would want to distinguish when
   * debugging a "why can't I list users" ticket.
   *
   * <p>Package-private so unit tests can stage a role and verify the
   * gate without a real Spring Security context.
   */
  Long requireAdmin() {
    var role = currentUser.currentRole();
    if (role.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in required");
    }
    if (role.get() != Role.ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
    }
    return currentUser.currentUserId().orElse(null);
  }

  private static Role parseRole(String s) {
    return Optional.ofNullable(s)
        .flatMap(Role::fromWireName)
        .orElseThrow(() -> new IllegalArgumentException(
            "role must be one of admin|user|guest, got: " + s));
  }

  // ─── request bodies ────────────────────────────────────────────────────

  /**
   * Password is optional now: absent or blank means "generate one".
   * {@code @NotBlank} was removed from it for exactly that reason.
   *
   * <p>{@code email} and {@code createMailbox} drive the auto-mailbox
   * story: a new user gets a mailbox by default at {@code
   * <username>@<box-domain>}, sharing the user's own password. Set
   * {@code email} to override the address (a bare local part or a full
   * {@code local@domain}); set {@code createMailbox=false} to skip it.
   * Both null = the default behaviour (make the default mailbox).
   */
  public record CreateReq(
      @NotBlank String username,
      String password,
      @NotBlank String role,
      String tz,
      String email,
      Boolean createMailbox
  ) {}

  /**
   * A created user, plus the generated password when Aurora chose it,
   * plus the outcome of the auto-mailbox provisioning.
   *
   * <p>{@code generatedPassword} is null whenever the caller supplied
   * their own — we do not echo back a secret the client already holds.
   */
  public record CreatedUser(UserSummary user, String generatedPassword, MailboxOutcome mailbox) {}

  /**
   * What happened to the new user's mailbox.
   *
   * @param requested whether mailbox provisioning was attempted at all
   *                  (false only when the admin opted out)
   * @param email     the address we tried to create ({@code null} when not requested)
   * @param created   true when the mailbox now exists and works
   * @param error     a human reason when {@code requested} but not {@code created}; else null
   */
  public record MailboxOutcome(boolean requested, String email, boolean created, String error) {}

  public record ResetReq(String password) {}

  public record GeneratedPassword(String password, boolean generated) {}

  public record UpdateReq(String role, String password) {}
}
