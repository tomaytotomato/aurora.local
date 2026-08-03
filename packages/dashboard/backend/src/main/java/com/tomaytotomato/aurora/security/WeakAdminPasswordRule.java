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
 * B4 rule 1: flags admin credentials protected by weak argon2 parameters.
 *
 * <p>Aurora stores admin passwords as argon2id hashes via argon2-jvm.
 * The hash string encodes the KDF parameters:
 * <pre>
 *   $argon2id$v=19$m=65536,t=2,p=1$SALT$HASH
 * </pre>
 * {@code m} = memory in KiB, {@code t} = iterations, {@code p} = parallelism.
 * OWASP's 2024 password storage guidance (updated Jan 2025) recommends
 * argon2id with:
 * <ul>
 *   <li>m &ge; 19456 KiB (19 MiB); we use 15360 as the floor because a
 *       small homelab box sometimes trims memory to fit alongside the
 *       docker stack, and 15 MiB is still meaningfully above the trivial
 *       default of 8 MiB.</li>
 *   <li>t &ge; 2 iterations.</li>
 *   <li>p &ge; 1 (always true).</li>
 * </ul>
 *
 * <p>Any admin whose stored hash falls below either threshold is
 * flagged HIGH. Rationale: an attacker who exfiltrates the SQLite
 * database can brute-force weak-parameter argon2 hashes on commodity
 * hardware; strong parameters are the last line of defence for an
 * offline attack.
 *
 * <p>What this rule can't check:
 * <ul>
 *   <li>Password entropy of the plaintext. That check has to live in
 *       {@code AuthService.setPassword()} because we don't keep the
 *       plaintext once the hash is written.</li>
 *   <li>Reuse across services. Aurora has one admin.</li>
 * </ul>
 */
@Component
public class WeakAdminPasswordRule implements SecurityRule {

  private static final Logger log = LoggerFactory.getLogger(WeakAdminPasswordRule.class);

  /** Minimum memory-cost (KiB) below which we flag a hash. */
  static final int MIN_MEMORY_KIB = 15_360;

  /** Minimum iteration count. */
  static final int MIN_ITERATIONS = 2;

  /**
   * Argon2 parameter pattern. The parameters segment is fixed shape;
   * everything before is the algorithm + version.
   */
  private static final Pattern PARAMS =
      Pattern.compile("\\$argon2id?\\$v=\\d+\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$");

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
      Params p = parse(hash);
      if (p == null) {
        // Unparseable hash (bcrypt-shaped, corrupted, or a future algorithm)
        // \u2014 flag as HIGH so the operator investigates rather than
        // silently pass.
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
      List<String> weakBits = new ArrayList<>(2);
      if (p.memoryKib < MIN_MEMORY_KIB) {
        weakBits.add("memory cost (" + p.memoryKib + " KiB below "
            + MIN_MEMORY_KIB + " KiB)");
      }
      if (p.iterations < MIN_ITERATIONS) {
        weakBits.add("iteration count (" + p.iterations + " below "
            + MIN_ITERATIONS + ")");
      }
      if (!weakBits.isEmpty()) {
        findings.add(new SecurityFinding(
            id() + ":" + maybe.get().username(),
            SecurityFinding.HIGH,
            "Admin password uses weak protection parameters",
            "The admin password is stored with argon2 parameters below the "
                + "OWASP 2024 recommendation: " + String.join(" and ", weakBits)
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

  /** Package-private for tests. */
  static Params parse(String hash) {
    if (hash == null) return null;
    Matcher m = PARAMS.matcher(hash);
    if (!m.find()) return null;
    try {
      return new Params(
          Integer.parseInt(m.group(1)),
          Integer.parseInt(m.group(2)),
          Integer.parseInt(m.group(3))
      );
    } catch (NumberFormatException e) {
      return null;
    }
  }

  record Params(int memoryKib, int iterations, int parallelism) {}
}
