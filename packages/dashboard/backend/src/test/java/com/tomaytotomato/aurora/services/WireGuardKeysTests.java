package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WireGuardKeys} — no {@code wg} binary involved, see the class
 * javadoc for why. These tests pin the one thing that matters for
 * wire-compatibility: both halves decode to exactly 32 raw bytes, which
 * is the shape every WireGuard tool (and {@code wg show ... dump}) expects.
 */
class WireGuardKeysTests {

  @Test
  void generates_a_private_and_public_key_that_each_decode_to_32_bytes() {
    var keys = WireGuardKeys.generate();

    byte[] priv = Base64.getDecoder().decode(keys.privateKeyBase64());
    byte[] pub = Base64.getDecoder().decode(keys.publicKeyBase64());

    assertThat(priv).hasSize(32);
    assertThat(pub).hasSize(32);
  }

  @Test
  void private_and_public_key_are_not_the_same_value() {
    var keys = WireGuardKeys.generate();
    assertThat(keys.privateKeyBase64()).isNotEqualTo(keys.publicKeyBase64());
  }

  @Test
  void every_call_produces_a_fresh_random_keypair() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      var keys = WireGuardKeys.generate();
      assertThat(seen.add(keys.privateKeyBase64())).as("private key repeated at i=%d", i).isTrue();
      assertThat(seen.add(keys.publicKeyBase64())).as("public key repeated at i=%d", i).isTrue();
    }
  }
}
