package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The way back into a box whose password has been lost.
 *
 * <p><b>Why.</b> The admin step told the operator: "If you lose the
 * password, use the password recovery option on this screen to reset it."
 * Clicking that option opened a dialog admitting recovery was not built,
 * and suggesting they "ask whoever set this box up" — who is the person
 * reading it. The only real recovery was
 * {@code scripts/reset-admin-password.sh} over SSH: a terminal, on the box,
 * for the single most likely thing to go wrong with a password-only login.
 *
 * <p><b>What.</b> One recovery code, shown once, at the moment the account
 * is created — the same moment the operator is already being told to save a
 * password, so it costs them no extra ceremony. Only its bcrypt hash is
 * stored, in the same settings table as the rest of Aurora's own state.
 * Redeeming it sets a new password and immediately issues a fresh code, so
 * a box is never left without one; the used code stops working the instant
 * it is redeemed.
 *
 * <p><b>Deliberately not:</b> email (a box whose owner is locked out cannot
 * read mail on that box), security questions (guessable), or a "reset from
 * the LAN" bypass (anyone on the wifi could take the box).
 */
@Service
public class RecoveryCodeService {

  private static final Logger log = LoggerFactory.getLogger(RecoveryCodeService.class);

  /** bcrypt hash of the current code. Absent means none has been issued. */
  static final String KEY_HASH = "auth.recovery_code_hash";
  /** ISO instant the current code was issued, for the Settings panel. */
  static final String KEY_ISSUED_AT = "auth.recovery_code_issued_at";

  /**
   * Six words from the same curated list the password generator uses:
   * readable aloud, typable on a phone, and impossible to confuse with a
   * password (which is what stops someone pasting the wrong one).
   */
  private static final int WORDS = 6;

  private final SettingsRepo settings;
  private final AdminUserRepo users;
  private final AuthService auth;
  private final AuditEventRepo audit;
  private final SecureRandom random = new SecureRandom();

  public RecoveryCodeService(SettingsRepo settings, AdminUserRepo users,
                             AuthService auth, AuditEventRepo audit) {
    this.settings = settings;
    this.users = users;
    this.auth = auth;
    this.audit = audit;
  }

  /** Whether this box has a usable recovery code. */
  public boolean isIssued() {
    return settings.get(KEY_HASH).filter(h -> !h.isBlank()).isPresent();
  }

  /** When the current code was issued, if there is one. */
  public Optional<String> issuedAt() {
    return settings.get(KEY_ISSUED_AT);
  }

  /**
   * Generate a code, store its hash, return the plaintext. The plaintext is
   * never stored and never logged; this is the only moment it exists.
   */
  public String issue() {
    String code = generateCode();
    settings.put(KEY_HASH, auth.hash(code.toCharArray()));
    settings.put(KEY_ISSUED_AT, Instant.now().toString());
    // No user id: this can happen before or after any given account exists,
    // and the code belongs to the box rather than to a person.
    audit.record(null, "auth.recovery_code.issue", null, null);
    return code;
  }

  /**
   * Spend the code: set a new password for {@code username} and issue a
   * replacement code.
   *
   * @return the replacement code, or empty when the username or the code is
   *         wrong. Callers must not distinguish the two in what they tell
   *         the browser — a wrong username and a wrong code have to look
   *         identical from outside, or this becomes a way to enumerate
   *         accounts.
   */
  public Optional<String> redeem(String username, String code, String newPassword) {
    if (username == null || code == null || newPassword == null) return Optional.empty();
    if (newPassword.length() < 12) {
      throw new IllegalArgumentException("password must be at least 12 characters");
    }

    Optional<String> stored = settings.get(KEY_HASH).filter(h -> !h.isBlank());
    if (stored.isEmpty()) return Optional.empty();

    // Normalise the shape people will actually type: spaces for hyphens,
    // stray case, a trailing full stop from a notes app.
    String normalised = normalise(code);
    if (!auth.verify(stored.get(), normalised.toCharArray())) {
      audit.record(null, "auth.recovery_code.reject", null, null);
      return Optional.empty();
    }

    Optional<AdminUser> user = users.findByUsername(username.trim());
    if (user.isEmpty()) {
      audit.record(null, "auth.recovery_code.reject", null, null);
      return Optional.empty();
    }

    users.updatePasswordHash(user.get().id(), auth.hash(newPassword.toCharArray()));
    audit.record(user.get().id(), "auth.recovery_code.redeem",
        "admin_user:" + user.get().id(), null);
    log.info("recovery code redeemed for {}; a new code has been issued", user.get().username());

    // Never leave the box without a way back in.
    return Optional.of(issue());
  }

  /** Package-private for tests. */
  static String normalise(String code) {
    return code.trim().toLowerCase()
        .replace(' ', '-')
        .replaceAll("[^a-z0-9-]", "")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "");
  }

  private String generateCode() {
    List<String> words = PasswordGenerator.words();
    var sb = new StringBuilder();
    for (int i = 0; i < WORDS; i++) {
      if (i > 0) sb.append('-');
      sb.append(words.get(random.nextInt(words.size())));
    }
    return sb.toString();
  }
}
