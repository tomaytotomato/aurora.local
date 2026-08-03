package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.events.UserChangedEvent;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Phase D iter-9 (D8) \u2014 user management orchestrator.
 *
 * <p>Fronted by {@code UsersController} (admin-only). Every mutation:
 * <ol>
 *   <li>Guards the "must keep at least one admin" invariant for the
 *       demote / delete paths.</li>
 *   <li>Writes to {@code AdminUserRepo}.</li>
 *   <li>Publishes {@link UserChangedEvent} so
 *       {@link AutheliaService} re-projects the users_database.yml
 *       on-disk (D2 wiring).</li>
 *   <li>Records an audit row.</li>
 * </ol>
 *
 * <p>Errors surface as {@link IllegalStateException} + {@link IllegalArgumentException};
 * the controller translates them into 4xx.
 */
@Service
public class UsersService {

  private static final Logger log = LoggerFactory.getLogger(UsersService.class);

  private final AdminUserRepo users;
  private final AuthService auth;
  private final AuditEventRepo audit;
  private final ApplicationEventPublisher events;

  public UsersService(AdminUserRepo users, AuthService auth, AuditEventRepo audit,
                      ApplicationEventPublisher events) {
    this.users = users;
    this.auth = auth;
    this.audit = audit;
    this.events = events;
  }

  /** Read-only projection safe for API responses \u2014 never carries the password hash. */
  public record UserSummary(long id, String username, Role role, String tz, String createdAt) {
    public static UserSummary of(AdminUser u) {
      return new UserSummary(u.id(), u.username(), u.role(), u.tz(), u.createdAt());
    }
  }

  // \u2500\u2500\u2500 read ────────────────────────────────────────────────────────────

  public List<UserSummary> list() {
    return users.findAll().stream().map(UserSummary::of).toList();
  }

  public Optional<UserSummary> findById(long id) {
    return users.findAll().stream().filter(u -> u.id() == id).findFirst().map(UserSummary::of);
  }

  // ─── create ────────────────────────────────────────────────────────────

  /**
   * Create a new user. Throws:
   * <ul>
   *   <li>{@link IllegalArgumentException} when username/password fails validation.</li>
   *   <li>{@link DuplicateKeyException} when the username is already taken (unique constraint).</li>
   * </ul>
   */
  public UserSummary create(String username, char[] password, Role role, String tz, Long actingUserId) {
    validateUsername(username);
    validatePassword(password);
    if (role == null) throw new IllegalArgumentException("role is required");

    String hash = auth.hash(password); // clears the char[] under the hood
    long id = users.create(username.trim(), hash, tzOrDefault(tz), role);
    audit.record(actingUserId, "users.create", "user:" + id,
        "{\"username\":\"" + escapeJson(username.trim()) + "\",\"role\":\"" + role.wireName() + "\"}");
    events.publishEvent(new UserChangedEvent(UserChangedEvent.CREATE));
    log.info("users: created id={} username={} role={}", id, username.trim(), role.wireName());
    return findById(id).orElseThrow();
  }

  // ─── update role ───────────────────────────────────────────────────────

  /**
   * Change a user's role. Guards the "must keep at least one admin"
   * invariant: demoting the last admin throws
   * {@link IllegalStateException}.
   */
  public UserSummary updateRole(long id, Role newRole, Long actingUserId) {
    if (newRole == null) throw new IllegalArgumentException("role is required");
    AdminUser existing = users.findAll().stream()
        .filter(u -> u.id() == id)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such user: " + id));

    if (existing.role() == newRole) {
      // No-op — don't churn audit rows on identity edits.
      return UserSummary.of(existing);
    }

    // Demote-of-last-admin guard. Delete + demote are the only paths
    // that can strand the box without an admin; both go through this
    // check (delete calls it separately, see #delete).
    if (existing.role() == Role.ADMIN && newRole != Role.ADMIN) {
      if (users.countByRole(Role.ADMIN) <= 1) {
        throw new IllegalStateException("cannot demote the last admin");
      }
    }

    users.updateRole(id, newRole);
    audit.record(actingUserId, "users.role-change", "user:" + id,
        "{\"from\":\"" + existing.role().wireName() + "\",\"to\":\"" + newRole.wireName() + "\"}");
    events.publishEvent(new UserChangedEvent(UserChangedEvent.ROLE_CHANGE));
    log.info("users: role change id={} {} -> {}", id, existing.role().wireName(), newRole.wireName());
    return findById(id).orElseThrow();
  }

  // ─── password rotation ─────────────────────────────────────────────────

  public void rotatePassword(long id, char[] newPassword, Long actingUserId) {
    validatePassword(newPassword);
    AdminUser existing = users.findAll().stream()
        .filter(u -> u.id() == id)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such user: " + id));
    String hash = auth.hash(newPassword);
    // Repo doesn't currently expose an updatePasswordHash; do it via
    // direct SQL for now. If a future test grows to need this via
    // AdminUserRepo, promote to a method there.
    users.updatePasswordHash(id, hash);
    audit.record(actingUserId, "users.password-rotate", "user:" + id, null);
    events.publishEvent(new UserChangedEvent(UserChangedEvent.PASSWORD_ROTATE));
    log.info("users: password rotated for id={} username={}", id, existing.username());
  }

  // ─── delete ────────────────────────────────────────────────────────────

  public void delete(long id, Long actingUserId) {
    AdminUser existing = users.findAll().stream()
        .filter(u -> u.id() == id)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such user: " + id));

    if (existing.role() == Role.ADMIN && users.countByRole(Role.ADMIN) <= 1) {
      throw new IllegalStateException("cannot delete the last admin");
    }

    users.deleteById(id);
    audit.record(actingUserId, "users.delete", "user:" + id,
        "{\"username\":\"" + escapeJson(existing.username()) + "\",\"role\":\"" + existing.role().wireName() + "\"}");
    events.publishEvent(new UserChangedEvent(UserChangedEvent.DELETE));
    log.info("users: deleted id={} username={}", id, existing.username());
  }

  // ─── validation helpers ────────────────────────────────────────────────

  static void validateUsername(String username) {
    if (username == null || username.trim().isEmpty()) {
      throw new IllegalArgumentException("username is required");
    }
    String u = username.trim();
    if (u.length() < 2 || u.length() > 32) {
      throw new IllegalArgumentException("username must be 2-32 characters");
    }
    // Match Authelia's expectation: DNS-label-ish (lowercase letters,
    // digits, dash, dot, underscore) so the username can appear in
    // subject strings + logs without escaping. Deliberately narrower
    // than what SQLite would accept.
    if (!u.matches("[a-z0-9][a-z0-9._-]*")) {
      throw new IllegalArgumentException(
          "username may only contain lowercase letters, digits, dot, underscore, dash");
    }
  }

  static void validatePassword(char[] password) {
    if (password == null || password.length < 12) {
      throw new IllegalArgumentException("password must be at least 12 characters");
    }
  }

  private static String tzOrDefault(String tz) {
    return (tz == null || tz.isBlank()) ? "UTC" : tz;
  }

  private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
