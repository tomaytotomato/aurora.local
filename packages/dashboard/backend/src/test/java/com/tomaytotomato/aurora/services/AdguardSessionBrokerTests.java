package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdguardSessionBroker#insertBrokerUser(String, String, String)}
 * yaml surgery. The full integration lifecycle (env write, cookie fetch,
 * cache) is exercised through the live-box smoke test; the pure
 * string surgery has enough shapes to warrant its own tests.
 */
class AdguardSessionBrokerTests {

  private static final String CANONICAL_YAML = """
      http:
        address: 0.0.0.0:3000
      users:
        - name: sarah
          password: $2a$12$abc
      auth_attempts: 5
      dns:
        bind_hosts:
          - 0.0.0.0
      """;

  @Test
  void inserts_broker_after_existing_users_and_before_the_next_key() {
    String out = AdguardSessionBroker.insertBrokerUser(
        CANONICAL_YAML, "aurora-broker", "$2a$12$xyz");

    // Both users present.
    assertThat(out).contains("- name: sarah");
    assertThat(out).contains("- name: aurora-broker");
    assertThat(out).contains("password: $2a$12$xyz");

    // Broker comes AFTER sarah (adding a user, not replacing).
    int sarahAt = out.indexOf("- name: sarah");
    int brokerAt = out.indexOf("- name: aurora-broker");
    assertThat(sarahAt).isPositive();
    assertThat(brokerAt).isGreaterThan(sarahAt);

    // And BEFORE the next top-level key, so we're still inside the
    // users: block.
    int authAt = out.indexOf("auth_attempts:");
    assertThat(brokerAt).isLessThan(authAt);
  }

  @Test
  void is_a_no_op_when_the_broker_is_already_present() {
    // A reconcile that keeps running would rewrite the yaml every tick
    // and burn Caddy's watcher on flap-reloads; the surgery has to be
    // idempotent.
    String once = AdguardSessionBroker.insertBrokerUser(
        CANONICAL_YAML, "aurora-broker", "$2a$12$xyz");
    String twice = AdguardSessionBroker.insertBrokerUser(
        once, "aurora-broker", "$2a$12$xyz");
    assertThat(twice).isEqualTo(once);
  }

  @Test
  void leaves_the_rest_of_the_yaml_verbatim_including_indented_blocks() {
    // Yaml has structure. Dumb string-level surgery MUST NOT munge the
    // dns: block that follows users:. Otherwise Aurora would drop the
    // upstream DNS servers on every write.
    String yaml = """
        users:
          - name: sarah
            password: $2a$12$abc
        dns:
          bind_hosts:
            - 0.0.0.0
          upstream_dns:
            - https://dns.quad9.net/dns-query
          rewrites:
            - domain: aurora.local
              answer: 192.168.0.110
        """;
    String out = AdguardSessionBroker.insertBrokerUser(
        yaml, "aurora-broker", "$2a$12$xyz");

    // The dns: block is intact.
    assertThat(out).contains("bind_hosts:");
    assertThat(out).contains("- 0.0.0.0");
    assertThat(out).contains("https://dns.quad9.net/dns-query");
    assertThat(out).contains("domain: aurora.local");
    assertThat(out).contains("answer: 192.168.0.110");
  }

  @Test
  void adds_a_users_block_when_none_exists() {
    // Not a shape we expect on a real box (AdguardProvisionService
    // always renders users:), but a corrupt/hand-edited yaml
    // shouldn't wedge the broker forever.
    String bare = """
        dns:
          bind_hosts:
            - 0.0.0.0
        """;
    String out = AdguardSessionBroker.insertBrokerUser(
        bare, "aurora-broker", "$2a$12$xyz");
    assertThat(out).contains("users:");
    assertThat(out).contains("- name: aurora-broker");
    assertThat(out).contains("password: $2a$12$xyz");
    // dns: block preserved.
    assertThat(out).contains("bind_hosts:");
  }
}
