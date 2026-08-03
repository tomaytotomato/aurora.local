package com.tomaytotomato.aurora.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D iter-2 — {@link Role} wire-format + ordering contract.
 * Pinned here because Authelia's users_database.yml (D2) will read
 * these strings verbatim, and a future refactor that flips them to
 * TitleCase or {@code role.name()} would silently break every Aurora
 * user's group membership.
 */
class RoleTests {

  @Test
  void wireName_is_lowercase() {
    assertThat(Role.ADMIN.wireName()).isEqualTo("admin");
    assertThat(Role.USER.wireName()).isEqualTo("user");
    assertThat(Role.GUEST.wireName()).isEqualTo("guest");
  }

  @Test
  void fromWireName_round_trips() {
    for (Role r : Role.values()) {
      assertThat(Role.fromWireName(r.wireName())).contains(r);
    }
  }

  @Test
  void fromWireName_is_case_insensitive_and_trims() {
    assertThat(Role.fromWireName(" ADMIN ")).contains(Role.ADMIN);
    assertThat(Role.fromWireName("Admin")).contains(Role.ADMIN);
    assertThat(Role.fromWireName("user")).contains(Role.USER);
  }

  @Test
  void fromWireName_rejects_unknown_values() {
    assertThat(Role.fromWireName(null)).isEmpty();
    assertThat(Role.fromWireName("")).isEmpty();
    assertThat(Role.fromWireName("root")).isEmpty();
    assertThat(Role.fromWireName("superuser")).isEmpty();
  }

  @Test
  void isAtLeast_encodes_the_expected_privilege_ladder() {
    // ADMIN dominates every other role.
    assertThat(Role.ADMIN.isAtLeast(Role.ADMIN)).isTrue();
    assertThat(Role.ADMIN.isAtLeast(Role.USER)).isTrue();
    assertThat(Role.ADMIN.isAtLeast(Role.GUEST)).isTrue();
    // USER covers itself + GUEST.
    assertThat(Role.USER.isAtLeast(Role.USER)).isTrue();
    assertThat(Role.USER.isAtLeast(Role.GUEST)).isTrue();
    assertThat(Role.USER.isAtLeast(Role.ADMIN)).isFalse();
    // GUEST covers only itself.
    assertThat(Role.GUEST.isAtLeast(Role.GUEST)).isTrue();
    assertThat(Role.GUEST.isAtLeast(Role.USER)).isFalse();
    assertThat(Role.GUEST.isAtLeast(Role.ADMIN)).isFalse();
  }

  @Test
  void enum_declaration_order_matches_privilege_ladder() {
    // GUEST < USER < ADMIN — relied upon by isAtLeast + any future
    // JOIN that sorts users by role. If someone reorders the enum,
    // this test screams before Authelia group mapping goes weird.
    assertThat(Role.values()).containsExactly(Role.GUEST, Role.USER, Role.ADMIN);
  }
}
