package com.tomaytotomato.aurora.security;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests were originally written against a planned argon2id migration
 * that never shipped: AuthService writes bcrypt (cost 12, deliberately
 * matching Authelia's file_password) and the rule now checks bcrypt
 * cost instead. See UNIFIED_AUTH_PLAN.md "Argon2id migration breaks
 * SSO" for why the pin exists.
 *
 * Bug caught during 27 Aug 2026 QA sweep: every fresh box was showing
 * a HIGH "unknown format" finding because the old rule expected
 * $argon2id$ while AuthService writes $2a$.
 */
class WeakAdminPasswordRuleTests {

  /** A bcrypt cost-12 hash. Real shape, real cost — plaintext doesn't matter. */
  private static final String STRONG_HASH =
      "$2a$12$AbCdEfGhIjKlMnOpQrStUuVvWwXxYyZz0123456789abcdefghij";

  private static AdminUser admin(String hash) {
    return new AdminUser(1, "bruce", hash, "UTC", "2026-08-01T00:00:00Z",
        com.tomaytotomato.aurora.domain.Role.ADMIN);
  }

  private static WeakAdminPasswordRule ruleWith(String hash) {
    AdminUserRepo r = Mockito.mock(AdminUserRepo.class);
    Mockito.when(r.findFirst()).thenReturn(hash == null ? Optional.empty() : Optional.of(admin(hash)));
    return new WeakAdminPasswordRule(r);
  }

  @Test
  void no_admin_yields_no_findings() {
    assertEquals(List.of(), ruleWith(null).evaluate());
  }

  @Test
  void strong_bcrypt_hash_yields_no_finding() {
    // Cost 12 is Aurora's baseline (matches Authelia's file_password).
    assertEquals(List.of(), ruleWith(STRONG_HASH).evaluate());
  }

  @Test
  void bcrypt_variant_2b_also_accepted() {
    // $2b$ is the modern OpenBSD variant Spring Security also produces.
    // The pin is about cost, not variant.
    var got = ruleWith("$2b$12$SaltSaltSaltSaltSaltSaHashHashHashHashHashHashHashHash").evaluate();
    assertEquals(List.of(), got);
  }

  @Test
  void bcrypt_variant_2y_also_accepted() {
    // $2y$ is the crypt_blowfish variant from PHP land. Same math,
    // different prefix — no reason to fail an operator whose hash
    // came in via a backup from a PHP-hashing tool.
    var got = ruleWith("$2y$12$SaltSaltSaltSaltSaltSaHashHashHashHashHashHashHashHash").evaluate();
    assertEquals(List.of(), got);
  }

  @Test
  void low_cost_hash_flagged_HIGH() {
    // Cost 10 was the historic default; anything below Aurora's baseline
    // (12) has to be flagged so a downgraded encoder or a much older
    // backup is not silently accepted.
    var got = ruleWith("$2a$10$SaltSaltSaltSaltSaltSaHashHashHashHashHashHashHashHash").evaluate();
    assertEquals(1, got.size());
    assertEquals(SecurityFinding.HIGH, got.get(0).severity());
    assertTrue(got.get(0).description().contains("bcrypt cost 10"),
        "description should name the observed cost: " + got.get(0).description());
    assertTrue(got.get(0).description().contains("12"),
        "description should name the baseline: " + got.get(0).description());
  }

  @Test
  void unparseable_hash_flagged_HIGH_as_unknown_format() {
    // A future algorithm swap, corruption, or an old argon2 hash from
    // a backup — anything not-bcrypt is HIGH so the operator investigates.
    var got = ruleWith("$argon2id$v=19$m=4096,t=2,p=1$c2FsdA$aGFzaA").evaluate();
    assertEquals(1, got.size());
    assertEquals(SecurityFinding.HIGH, got.get(0).severity());
    assertTrue(got.get(0).title().toLowerCase().contains("unknown format"),
        "title should mention unknown format: " + got.get(0).title());
  }

  @Test
  void findings_have_stable_id_including_username() {
    var got = ruleWith("$2a$10$SaltSaltSaltSaltSaltSaHashHashHashHashHashHashHashHash").evaluate();
    assertEquals(1, got.size());
    assertEquals("weak_admin_password:bruce", got.get(0).id());
  }

  @Test
  void copy_avoids_shell_substrings() {
    // User-facing findings must not read like a runbook; the fix URL
    // takes them to Settings, and the description explains the "why".
    var got = ruleWith("$2a$10$SaltSaltSaltSaltSaltSaHashHashHashHashHashHashHashHash").evaluate();
    String all = (got.get(0).title() + " " + got.get(0).description()).toLowerCase();
    assertTrue(!all.contains("sudo ") && !all.contains("docker ")
        && !all.contains("bash ") && !all.contains("./scripts/"),
        "copy must be user-facing, was: " + all);
  }

  @Test
  void parser_returns_null_on_null_input() {
    assertNull(WeakAdminPasswordRule.parseCost(null));
  }

  @Test
  void parser_returns_null_on_empty_input() {
    assertNull(WeakAdminPasswordRule.parseCost(""));
  }

  @Test
  void parser_returns_null_on_non_bcrypt_shape() {
    // Neither cost nor variant matches — the rule falls into the
    // "unknown format" branch, not the cost-check branch.
    assertNull(WeakAdminPasswordRule.parseCost("not-a-hash"));
    assertNull(WeakAdminPasswordRule.parseCost("$argon2id$v=19$m=4096,t=2,p=1$c2FsdA$aGFzaA"));
  }

  @Test
  void parser_returns_cost_for_valid_hashes() {
    assertEquals(12, WeakAdminPasswordRule.parseCost(STRONG_HASH));
    assertEquals(10, WeakAdminPasswordRule.parseCost(
        "$2b$10$SaltSaltSaltSaltSaltSaHashHashHashHashHashHashHashHash"));
    assertEquals(14, WeakAdminPasswordRule.parseCost(
        "$2y$14$SaltSaltSaltSaltSaltSaHashHashHashHashHashHashHashHash"));
  }

  @Test
  void rule_swallows_repo_exceptions() {
    // Never propagates — the rule runs on a security-page render, and
    // a database blip on the audit path must not blank the page.
    AdminUserRepo r = Mockito.mock(AdminUserRepo.class);
    Mockito.when(r.findFirst()).thenThrow(new RuntimeException("db locked"));
    assertEquals(List.of(), new WeakAdminPasswordRule(r).evaluate());
  }
}
