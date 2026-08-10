package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.AuthService;
import com.tomaytotomato.aurora.services.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code /api/users} — who can sign in, and how far each of them reaches.
 *
 * <p>Aurora starts single-admin from the onboarding bootstrap. A home
 * server is often shared, so this manages the small set of people with
 * access across three roles: admin, operator, viewer.
 *
 * <p>Three guards, and the second and third are the ones that matter:
 *
 * <ul>
 *   <li><b>Only an admin can manage people.</b> That is what the role
 *       blurb on the Users page promises, so it is enforced here rather
 *       than assumed.</li>
 *   <li><b>You cannot remove your own account.</b> The frontend says so
 *       too; the backend has to mean it.</li>
 *   <li><b>You cannot remove or demote the last admin.</b> The frontend
 *       does not know about this one. Without it, an owner who demotes
 *       themselves to operator to "test what a housemate sees" locks
 *       every person out of the dashboard permanently, with no recovery
 *       short of editing SQLite by hand.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users")
public class UsersController {

  /**
   * Same shape the onboarding admin form accepts: something you could type
   * into a login box without wondering about encoding.
   */
  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,31}$");

  /**
   * Minimum password length. Twelve because the security rule engine
   * already calls anything shorter a high-severity finding, and it would
   * be odd for the API to hand out accounts it will immediately complain
   * about.
   */
  static final int MIN_PASSWORD_LENGTH = 12;

  private final AdminUserRepo users;
  private final AuthService auth;
  private final AuditEventRepo audit;
  private final CurrentUserService currentUser;

  public UsersController(AdminUserRepo users, AuthService auth, AuditEventRepo audit,
                         CurrentUserService currentUser) {
    this.users = users;
    this.auth = auth;
    this.audit = audit;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    // Readable by any signed-in user: knowing who else has access is not
    // privileged information on a box you already have an account on, and
    // hiding it would make the Users page useless for an operator.
    return users.listAll().stream().map(UsersController::toWire).toList();
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
    requireAdmin();

    String username = string(body, "username");
    String password = string(body, "password");
    String role = string(body, "role");

    if (username == null || !USERNAME.matcher(username).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Usernames are 2 to 32 characters: lower-case letters, digits, dot, dash or underscore.");
    }
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Passwords need to be at least " + MIN_PASSWORD_LENGTH + " characters.");
    }
    if (role == null || !AdminUser.isValidRole(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Role must be admin, operator or viewer.");
    }
    if (users.findByUsername(username).isPresent()) {
      // 409 rather than 400: the request was well-formed, the world just
      // already contains this person. The frontend renders this inline.
      throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken.");
    }

    long id = users.create(username, auth.hash(password.toCharArray()), "UTC", role);
    audit.record(currentUserId(), "users.create", "user:" + id, "{\"role\":\"" + role + "\"}");

    AdminUser created = users.findById(id).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "user vanished after creation"));
    return ResponseEntity.status(HttpStatus.CREATED).body(toWire(created));
  }

  @PatchMapping("/{id}")
  public Map<String, Object> patch(@PathVariable("id") long id,
                                   @RequestBody Map<String, Object> body) {
    requireAdmin();

    AdminUser target = users.findById(id).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user"));

    String role = string(body, "role");
    if (role == null || !AdminUser.isValidRole(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Role must be admin, operator or viewer.");
    }

    if (target.isAdmin() && !AdminUser.ROLE_ADMIN.equals(role) && users.countAdmins() <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "That's the only admin left. Make someone else an admin first, "
              + "or nobody will be able to manage this box.");
    }

    users.updateRole(id, role);
    audit.record(currentUserId(), "users.role", "user:" + id,
        "{\"from\":\"" + target.role() + "\",\"to\":\"" + role + "\"}");

    return toWire(users.findById(id).orElseThrow());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable("id") long id) {
    requireAdmin();

    AdminUser target = users.findById(id).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user"));

    Long me = currentUserId();
    if (me != null && me == id) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You can't remove your own account.");
    }
    if (target.isAdmin() && users.countAdmins() <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "That's the only admin left. Removing it would lock everyone out of this box.");
    }

    users.delete(id);
    audit.record(currentUserId(), "users.delete", "user:" + id, null);
    return ResponseEntity.noContent().build();
  }

  // ------------------------------------------------------------------

  /**
   * Managing people is admin-only. A caller whose role cannot be resolved
   * is refused rather than waved through: an unresolvable principal on an
   * authenticated route means something is wrong, and the safe reading of
   * "wrong" is "no".
   */
  private void requireAdmin() {
    Optional<AdminUser> me = currentUserIdOpt().flatMap(users::findById);
    if (me.isEmpty() || !me.get().isAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Only an admin can manage who has access.");
    }
  }

  private Optional<Long> currentUserIdOpt() {
    try {
      return currentUser.currentUserId();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Long currentUserId() {
    return currentUserIdOpt().orElse(null);
  }

  private static String string(Map<String, Object> body, String key) {
    if (body == null) return null;
    Object v = body.get(key);
    if (!(v instanceof String s)) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * The wire shape from openapi.yaml. {@code id} is a string there because
   * the frontend treats it as an opaque handle; SQLite hands us a number.
   */
  static Map<String, Object> toWire(AdminUser u) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", String.valueOf(u.id()));
    m.put("username", u.username());
    m.put("role", u.role());
    m.put("createdAt", u.createdAt());
    m.put("lastLoginAt", u.lastLoginAt());
    m.put("passkeyEnrolled", u.passkeyEnrolled());
    return m;
  }
}
