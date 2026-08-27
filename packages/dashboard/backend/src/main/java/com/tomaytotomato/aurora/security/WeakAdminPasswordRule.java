package com.tomaytotomato.aurora.security;

import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B4 rule 1: flags admin credentials protected by weak bcrypt parameters.
 *
 * <p>Aurora stores admin passwords as bcrypt hashes via
 * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}.
 * The hash string encodes the cost:
 * <pre>
 *   $2a$12$SALT/HASH
 *   ^^^ ^^
 *   |   +-- log2 rounds ("cost"); 12 => 4096 rounds ~= 250 ms on a Core i5-6500T
 *   +------ algorithm variant (2, 2a, 2b, 2y are all bcrypt)
 * </pre>
 *
 * <p><b>Why bcrypt on a homelab appliance.</b> Aurora is designed to
 * co-project user hashes into Authelia's {@code users_database.yml}
 * (see {@link com.tomaytotomato.aurora.services.AutheliaService}), and
 * Authelia's {@code file_password} is pinned to bcrypt cost 12
 * ({@code packages/core/authelia/configuration.yml}). The two sides
 * must verify against the same hash without a rehash-on-first-login
 * dance, so Aurora writes bcrypt at the same cost. Argon2id was queued
 * for v0.2 ({@link com.tomaytotomato.aurora.services.AuthService}
 * javadoc) but has not shipped, and switching Aurora without also
 * switching Authelia would lock every user out of every service
 * (see the UNIFIED_AUTH_PLAN risk table).
 *
 * <p><b>What weak means here.</b> OWASP's password-storage cheat sheet
 * (last updated 2024) recommends bcrypt cost 10 as a floor; the auth
 * plan pins 12 as Aurora's operational baseline because bcrypt-10 is
 * over a decade old as a default. Cost below 12 is flagged HIGH: it
 * means the operator is running a downgraded encoder or a hash
 * migrated in from an older backup that predates the pin.
 *
 * <p><b>What this rule cannot check.</b>
 * <ul>
 *   <li>Password entropy of the plaintext. That check has to live in
 *       {@code AuthService.setPassword()} because we don't keep the
 *       plaintext once the hash is written.</li>
 *   <li>Reuse across services. Aurora has one admin plane; every
 *       downstream service authenticates through Authelia against the
 *       same hash the projector writes.</li>
 * </ul>
 *
 * <p><b>Forward compatibility.</b> When argon2id ships, this rule's
 * parser should recognise both prefixes and evaluate each with its own
 * thresholds. The current implementation deliberately fails closed on
 * an unrecognised prefix (HIGH "unknown format") so the operator
 * investigates rather than a mid-flight algorithm swap slipping through
 * as silent success.
 */
@Component
public class WeakAdminPasswordRule implements SecurityRule {

  private static final Logger log = LoggerFactory.getLogger(WeakAdminPasswordRule.class);

  /**
   * Minimum bcrypt cost accepted without flagging.
   *
   * <p>12 matches the {@code BCRYPT_COST} constant in
   * {@link com.tomaytotomato.aurora.services.AuthService} and the
   * {@code file_password} cost pinned in Authelia's configuration.
   * Bumping this value requires changing all three in the same commit
   * so re-hashed passwords still verify against Authelia; see the
   * "Argon2id migration breaks SSO" risk row in
   * {@code docs/UNIFIED_AUTH_PLAN.md}.
   */
  static final int MIN_BCRYPT_COST = 12;

  /**
   * bcrypt hash shape.
   *
   * <p>Spring Security's {@code BCryptPasswordEncoder} writes {@code $2a$}
   * by default; other variants ({@code $2b$}, {@code $2y$}) are equally
   * valid bcrypt and covered by the same pin. The cost is the two-digit
   * group between the second and third {@code $}.
   */
  private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$");

  private final AdminUserRepo admins;

  public WeakAdminPasswordRule(AdminUserRepo admins) {
    this.admins = admins;
  }

  @Override
  public String id() { return "weak_admin_password"; }

  @Override
  public List<SecurityFinding> evaluate() {
    try {
      // v0.1 only has a single admin; findFirst() covers the current
      // world without a full-table scan.
      Optional<com.tomaytotomato.aurora.domain.AdminUser> maybe = admins.findFirst();
      if (maybe.isEmpty()) return List.of();
      String hash = maybe.get().passwordHash();
      Integer cost = parseCost(hash);
      if (cost == null) {
        // Unparseable hash (corrupted, or a future algorithm that
        // slipped past this rule) — flag as HIGH so the operator
        // investigates rather than silently pass.
        return List.of(new SecurityFinding(
            id() + ":" + maybe.get().username(),
            SecurityFinding.HIGH,
            "Admin password hash is in an unknown format",
            "Aurora couldn't recognise the format of the stored admin "
                + "password hash. This can happen if the database was "
                + "restored from a much older backup. Rotate the admin "
                + "password to bring the format up to date.",
            "/settings#account"
        ));
      }
      List<SecurityFinding> findings = new ArrayList<>(1);
      if (cost < MIN_BCRYPT_COST) {
        findings.add(new SecurityFinding(
            id() + ":" + maybe.get().username(),
            SecurityFinding.HIGH,
            "Admin password uses weak protection parameters",
            "The admin password is stored with bcrypt cost " + cost
                + ", below Aurora's baseline of " + MIN_BCRYPT_COST
                + ". Rotate the admin password on the Settings page to "
                + "re-hash with the current defaults.",
            "/settings#account"
        ));
      }
      return findings;
    } catch (Exception e) {
      log.debug("weak-admin-password rule failed: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Parse the bcrypt cost from a hash. Returns null if the hash does
   * not match the bcrypt shape (algorithm swap, corruption, empty).
   * Package-private for tests.
   */
  static Integer parseCost(String hash) {
    if (hash == null) return null;
    Matcher m = BCRYPT.matcher(hash);
    if (!m.find()) return null;
    try {
      return Integer.parseInt(m.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
