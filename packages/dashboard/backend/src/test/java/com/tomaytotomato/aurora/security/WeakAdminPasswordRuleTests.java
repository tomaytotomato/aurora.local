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

class WeakAdminPasswordRuleTests {

  private static AdminUser admin(String hash) {
    return new AdminUser(1, "bruce", hash, "UTC", "2026-08-01T00:00:00Z", com.tomaytotomato.aurora.domain.Role.ADMIN);
  }

  private static WeakAdminPasswordRule ruleWith(String hash) {
    AdminUserRepo r = Mockito.mock(AdminUserRepo.class);
    Mockito.when(r.findFirst()).thenReturn(hash == null ? Optional.empty() : Optional.of(admin(hash)));
    return new WeakAdminPasswordRule(r);
  }

  @Test
  void no_admin_yields_no_findings() {
    List<SecurityFinding> got = ruleWith(null).evaluate();
    assertEquals(List.of(), got);
  }

  @Test
  void strong_argon2_hash_yields_no_finding() {
    // m=65536 (64 MiB) t=2 p=1 — well above OWASP floor.
    var got = ruleWith("$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA").evaluate();
    assertEquals(List.of(), got);
  }

  @Test
  void low_memory_hash_flagged_HIGH() {
    // m=4096 (4 MiB) — argon2-jvm's ancient default, way below OWASP.
    var got = ruleWith("$argon2id$v=19$m=4096,t=2,p=1$c2FsdA$aGFzaA").evaluate();
    assertEquals(1, got.size());
    assertEquals(SecurityFinding.HIGH, got.get(0).severity());
    assertTrue(got.get(0).description().contains("memory cost"),
        "description should mention memory cost: " + got.get(0).description());
  }

  @Test
  void low_iteration_hash_flagged_HIGH() {
    var got = ruleWith("$argon2id$v=19$m=65536,t=1,p=1$c2FsdA$aGFzaA").evaluate();
    assertEquals(1, got.size());
    assertEquals(SecurityFinding.HIGH, got.get(0).severity());
    assertTrue(got.get(0).description().contains("iteration"),
        "description should mention iteration count: " + got.get(0).description());
  }

  @Test
  void low_both_dimensions_yields_one_finding_with_both_bits() {
    var got = ruleWith("$argon2id$v=19$m=4096,t=1,p=1$c2FsdA$aGFzaA").evaluate();
    assertEquals(1, got.size());
    String desc = got.get(0).description();
    assertTrue(desc.contains("memory cost") && desc.contains("iteration"),
        "both weak dimensions should be listed: " + desc);
  }

  @Test
  void unparseable_hash_flagged_HIGH_as_unknown_format() {
    var got = ruleWith("$2b$12$abcdefghijklmnopqrstuv1234").evaluate();
    assertEquals(1, got.size());
    assertEquals(SecurityFinding.HIGH, got.get(0).severity());
    assertTrue(got.get(0).title().toLowerCase().contains("unknown format"),
        "title should mention unknown format: " + got.get(0).title());
  }

  @Test
  void findings_have_stable_id_including_username() {
    var got = ruleWith("$argon2id$v=19$m=4096,t=2,p=1$c2FsdA$aGFzaA").evaluate();
    assertEquals(1, got.size());
    assertEquals("weak_admin_password:bruce", got.get(0).id());
  }

  @Test
  void copy_avoids_shell_substrings() {
    var got = ruleWith("$argon2id$v=19$m=4096,t=1,p=1$c2FsdA$aGFzaA").evaluate();
    String all = (got.get(0).title() + " " + got.get(0).description()).toLowerCase();
    assertTrue(!all.contains("sudo ") && !all.contains("docker ")
        && !all.contains("bash ") && !all.contains("./scripts/"),
        "copy must be user-facing, was: " + all);
  }

  @Test
  void parser_handles_argon2i_alias() {
    // The parameter regex matches argon2i as well (argon2id? = argon2i
    // optionally followed by 'd'). Params parse cleanly — the rule
    // then applies the same thresholds regardless of subtype.
    var p = WeakAdminPasswordRule.parse(
        "$argon2i$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA");
    org.junit.jupiter.api.Assertions.assertEquals(16384, p.memoryKib());
    org.junit.jupiter.api.Assertions.assertEquals(2, p.iterations());
  }

  @Test
  void parser_returns_null_on_null_input() {
    assertNull(WeakAdminPasswordRule.parse(null));
  }

  @Test
  void rule_swallows_repo_exceptions() {
    AdminUserRepo r = Mockito.mock(AdminUserRepo.class);
    Mockito.when(r.findFirst()).thenThrow(new RuntimeException("db locked"));
    List<SecurityFinding> got = new WeakAdminPasswordRule(r).evaluate();
    // Never propagates.
    assertEquals(List.of(), got);
  }
}
