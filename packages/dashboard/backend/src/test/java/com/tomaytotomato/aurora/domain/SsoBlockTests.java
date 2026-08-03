package com.tomaytotomato.aurora.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D iter-6 \u2014 {@link SsoBlock} parser + defaults.
 *
 * <p>The manifest {@code sso:} block drives Caddy snippet rendering
 * (D6) and Authelia access-control rule emission (D6/D12). Its shape
 * is source of truth for whether a vhost is protected + at what
 * minimum role + whether the service reads trusted-header auth. This
 * test pins:
 *
 * <ul>
 *   <li>Absent block \u2192 {@link SsoBlock#DISABLED} (safe default).</li>
 *   <li>Field-level parsing of {@code protect}, {@code min_role},
 *       {@code trusted_headers} with lenient bool coercion.</li>
 *   <li>Unknown keys silently ignored (forward-compat).</li>
 *   <li>Bad {@code min_role} falls back to USER (never crashes).</li>
 * </ul>
 */
class SsoBlockTests {

  @Test
  void absent_block_returns_disabled_default() {
    assertThat(SsoBlock.fromManifest(null)).isEqualTo(SsoBlock.DISABLED);
    // Anything non-Map (a rogue scalar in the yaml) also returns the
    // default \u2014 fail-safe rather than fail-shut, matches how other
    // manifest parsers here already handle malformed input.
    assertThat(SsoBlock.fromManifest("nope")).isEqualTo(SsoBlock.DISABLED);
    assertThat(SsoBlock.fromManifest(42)).isEqualTo(SsoBlock.DISABLED);
  }

  @Test
  void disabled_default_is_not_protected() {
    assertThat(SsoBlock.DISABLED.protect()).isFalse();
    assertThat(SsoBlock.DISABLED.isDisabled()).isTrue();
    assertThat(SsoBlock.DISABLED.minRole()).isEqualTo(Role.USER);
    assertThat(SsoBlock.DISABLED.trustedHeaders()).isFalse();
  }

  @Test
  void parses_full_block_from_yaml_shape() {
    Map<String, Object> yaml = new LinkedHashMap<>();
    yaml.put("protect", true);
    yaml.put("min_role", "admin");
    yaml.put("trusted_headers", true);

    SsoBlock b = SsoBlock.fromManifest(yaml);
    assertThat(b.protect()).isTrue();
    assertThat(b.minRole()).isEqualTo(Role.ADMIN);
    assertThat(b.trustedHeaders()).isTrue();
    assertThat(b.isDisabled()).isFalse();
  }

  @Test
  void min_role_defaults_to_user_when_missing() {
    // The most common shape: package wants Authelia in front but is
    // happy with any authenticated user. Both notes + home-automation
    // will land here.
    SsoBlock b = SsoBlock.fromManifest(Map.of("protect", true));
    assertThat(b.protect()).isTrue();
    assertThat(b.minRole()).isEqualTo(Role.USER);
  }

  @Test
  void min_role_defaults_to_user_on_unknown_value() {
    // Fat-fingered manifest? Fall back to the safe middle tier rather
    // than crashing the whole packages list. A subtle typo shouldn't
    // lock every service into admin-only or open every service to
    // guest.
    SsoBlock b = SsoBlock.fromManifest(Map.of(
        "protect", true,
        "min_role", "superuser"
    ));
    assertThat(b.minRole()).isEqualTo(Role.USER);
  }

  @Test
  void protect_coerces_stringy_bool_shapes() {
    // Common yaml pitfall: authors write true / "true" / yes / on
    // interchangeably. The parser accepts any of them.
    for (Object truthy : new Object[]{true, "true", "TRUE", "yes", "on", "1", 1}) {
      SsoBlock b = SsoBlock.fromManifest(Map.of("protect", truthy));
      assertThat(b.protect()).as("truthy value: %s", truthy).isTrue();
    }
    for (Object falsy : new Object[]{false, "false", "no", "off", "0", 0}) {
      SsoBlock b = SsoBlock.fromManifest(Map.of("protect", falsy));
      assertThat(b.protect()).as("falsy value: %s", falsy).isFalse();
    }
  }

  @Test
  void trusted_headers_coerces_same_way_as_protect() {
    SsoBlock b = SsoBlock.fromManifest(Map.of(
        "protect", true,
        "trusted_headers", "yes"
    ));
    assertThat(b.trustedHeaders()).isTrue();
  }

  @Test
  void unknown_keys_are_ignored_for_forward_compat() {
    // A Phase E manifest that adds sso.oauth_client: authelia
    // shouldn't break a Phase D backend parsing it.
    SsoBlock b = SsoBlock.fromManifest(Map.of(
        "protect", true,
        "min_role", "user",
        "future_key_that_does_not_exist_yet", "hello"
    ));
    assertThat(b.protect()).isTrue();
    assertThat(b.minRole()).isEqualTo(Role.USER);
  }

  @Test
  void min_role_case_insensitive() {
    assertThat(SsoBlock.fromManifest(Map.of("min_role", "ADMIN")).minRole())
        .isEqualTo(Role.ADMIN);
    assertThat(SsoBlock.fromManifest(Map.of("min_role", "Guest")).minRole())
        .isEqualTo(Role.GUEST);
  }
}
