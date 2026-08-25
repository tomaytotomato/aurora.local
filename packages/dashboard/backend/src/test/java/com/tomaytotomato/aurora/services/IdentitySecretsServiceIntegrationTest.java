package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IdentitySecretsService} against a real Spring context, a real
 * SQLite {@code audit_event} table, and a real repository tree.
 *
 * <p>{@link IdentitySecretsServiceTests} already covers the service
 * hermetically with a mocked {@code AuditEventRepo} and a temp directory
 * it builds by hand. This class exists for the thing that style
 * deliberately can't see: what a rotation or a bootstrap actually leaves
 * behind in the real audit table once Spring, JDBC, and the on-disk
 * repository fixture are all real, per {@code TESTING.md}'s "integration
 * tests are the default" rule.
 */
class IdentitySecretsServiceIntegrationTest extends AuroraIntegrationTest {

  @Autowired
  private IdentitySecretsService secrets;

  private static String stateWithIdentityEnabled() {
    return String.join("\n",
        "bootstrap_version: 1",
        "enabled:",
        "  - core",
        "  - identity",
        "profiles: []",
        "");
  }

  private static String stateWithoutCore() {
    return String.join("\n",
        "bootstrap_version: 1",
        "enabled:",
        "  - media",
        "profiles: []",
        "");
  }

  private static String envExample() {
    return String.join("\n",
        "# Copy to .env and fill in.",
        "TZ=Europe/London",
        "DOMAIN=aurora.local",
        "",
        "AUTHELIA_JWT_SECRET=",
        "AUTHELIA_SESSION_SECRET=",
        "AUTHELIA_STORAGE_ENCRYPTION_KEY=",
        "");
  }

  private static String valueOf(String envBody, String key) {
    return Arrays.stream(envBody.split("\n"))
        .filter(line -> line.startsWith(key + "="))
        .map(line -> line.substring(key.length() + 1))
        .findFirst()
        .orElse(null);
  }

  @BeforeEach
  void seedIdentity() throws IOException {
    writeRepoFile(".state.yml", stateWithIdentityEnabled());
    writeRepoFile("packages/core/.env.example", envExample());
  }

  @Nested
  @DisplayName("ensureSecrets")
  class EnsureSecrets {

    @Test
    void writes_three_real_secrets_into_the_repo_env_file() throws IOException {
      secrets.ensureSecrets();

      String body = readRepoFile("packages/core/.env");
      for (String key : IdentitySecretsService.MANAGED_KEYS) {
        assertThat(valueOf(body, key)).hasSize(64).matches("[0-9a-f]{64}");
      }
    }

    @Test
    void records_a_real_audit_row_naming_every_generated_key() throws IOException {
      secrets.ensureSecrets();

      List<Map<String, Object>> rows = jdbcTemplate.queryForList(
          "SELECT user_id, target, diff_json FROM audit_event WHERE action = ?",
          "identity.secrets.bootstrap");

      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("target")).isEqualTo("packages/core/.env");
      String diff = (String) rows.get(0).get("diff_json");
      assertThat(diff).contains("AUTHELIA_JWT_SECRET", "AUTHELIA_SESSION_SECRET",
          "AUTHELIA_STORAGE_ENCRYPTION_KEY");
    }

    @Test
    void a_second_call_is_idempotent_and_adds_no_second_audit_row() throws IOException {
      secrets.ensureSecrets();
      secrets.ensureSecrets();

      List<Map<String, Object>> rows = jdbcTemplate.queryForList(
          "SELECT id FROM audit_event WHERE action = ?", "identity.secrets.bootstrap");
      assertThat(rows).hasSize(1);
    }
  }

  @Nested
  @DisplayName("rotateSecrets")
  class RotateSecrets {

    @Test
    void replaces_every_key_on_disk() throws IOException {
      secrets.ensureSecrets();
      String before = valueOf(readRepoFile("packages/core/.env"), "AUTHELIA_JWT_SECRET");

      Set<String> rotated = secrets.rotateSecrets(7L);

      assertThat(rotated).containsExactlyInAnyOrderElementsOf(IdentitySecretsService.MANAGED_KEYS);
      String after = valueOf(readRepoFile("packages/core/.env"), "AUTHELIA_JWT_SECRET");
      assertThat(after).isNotEqualTo(before);
    }

    @Test
    void records_the_acting_user_without_ever_recording_the_new_secret_value() throws IOException {
      secrets.rotateSecrets(7L);
      String rotatedValue = valueOf(readRepoFile("packages/core/.env"), "AUTHELIA_JWT_SECRET");

      List<Map<String, Object>> rows = jdbcTemplate.queryForList(
          "SELECT user_id, diff_json FROM audit_event WHERE action = ?",
          "identity.secrets.rotate");

      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("user_id")).isEqualTo(7);
      assertThat((String) rows.get(0).get("diff_json")).doesNotContain(rotatedValue);
    }

    @Test
    void a_second_rotation_writes_a_second_audit_row_and_a_third_value() throws IOException {
      secrets.rotateSecrets(1L);
      String first = valueOf(readRepoFile("packages/core/.env"), "AUTHELIA_JWT_SECRET");
      secrets.rotateSecrets(2L);
      String second = valueOf(readRepoFile("packages/core/.env"), "AUTHELIA_JWT_SECRET");

      assertThat(second).isNotEqualTo(first);
      List<Map<String, Object>> rows = jdbcTemplate.queryForList(
          "SELECT user_id FROM audit_event WHERE action = ? ORDER BY id", "identity.secrets.rotate");
      assertThat(rows).hasSize(2);
      assertThat(rows.get(0).get("user_id")).isEqualTo(1);
      assertThat(rows.get(1).get("user_id")).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("identityEnabled against the real state file")
  class IdentityEnabled {

    @Test
    void true_when_the_real_state_file_lists_core() {
      assertThat(secrets.identityEnabled()).isTrue();
    }

    @Test
    void false_once_core_is_removed_from_state() throws IOException {
      writeRepoFile(".state.yml", stateWithoutCore());

      assertThat(secrets.identityEnabled()).isFalse();
    }
  }
}
