package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D iter-4 \u2014 {@link IdentitySecretsService}.
 *
 * <p>Hermetic: no Spring, no real dashboard, no Authelia. Every test
 * writes into a per-test {@link TempDir} repo skeleton with just the
 * files the service touches:
 * <ul>
 *   <li>{@code .state.yml} \u2014 minimal, only the {@code enabled:} field.</li>
 *   <li>{@code packages/identity/.env.example} \u2014 copied verbatim from
 *       the repo so comments + non-managed keys survive a mutation.</li>
 * </ul>
 *
 * <p>Covers:
 * <ul>
 *   <li>Skip bootstrap when identity is disabled.</li>
 *   <li>Fresh generation when {@code .env} doesn't exist yet.</li>
 *   <li>Idempotence \u2014 second call is a no-op.</li>
 *   <li>Partial generation \u2014 only missing/empty keys get filled.</li>
 *   <li>Rotate replaces every key + writes an audit row with the acting user.</li>
 *   <li>Comments + non-managed keys preserved on mutation.</li>
 *   <li>File permissions: 0600 on POSIX filesystems.</li>
 *   <li>Secret shape: 64 lowercase hex chars = 32 raw bytes = Authelia's minimum.</li>
 * </ul>
 */
class IdentitySecretsServiceTests {

  @TempDir Path repoRoot;
  private StateFileService state;
  private AuditEventRepo audit;
  private IdentitySecretsService svc;

  @BeforeEach
  void setUp() throws IOException {
    state = Mockito.mock(StateFileService.class);
    audit = Mockito.mock(AuditEventRepo.class);
    AuroraProperties props = new AuroraProperties(
        repoRoot.toString(), "/proc",
        List.of(),
        new AuroraProperties.Docker("unix:///dev/null")
    );
    svc = new IdentitySecretsService(state, audit, props);

    // Seed a minimal .env.example so the template-copy path is exercised
    // the same way it will run on Bruce's box (where the real
    // .env.example ships with comments + SMTP keys).
    Path identityDir = repoRoot.resolve("packages/identity");
    Files.createDirectories(identityDir);
    Files.writeString(identityDir.resolve(".env.example"), String.join("\n",
        "# Copy to .env and fill in.",
        "TZ=Europe/London",
        "DOMAIN=aurora.local",
        "",
        "# ---- Secrets (REQUIRED) --------------------------------------------",
        "AUTHELIA_JWT_SECRET=",
        "AUTHELIA_SESSION_SECRET=",
        "AUTHELIA_STORAGE_ENCRYPTION_KEY=",
        "",
        "# ---- Optional SMTP -------------------------------------------------",
        "AUTHELIA_NOTIFIER_SMTP_HOST=",
        "AUTHELIA_NOTIFIER_SMTP_PORT=587",
        ""
    ), StandardCharsets.UTF_8);

    // Default: identity is enabled. Override per-test as needed.
    Mockito.when(state.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "identity"), List.of()
    ));
  }

  // \u2500\u2500\u2500 identityEnabled ────────────────────────────────────────────────

  @Test
  void identityEnabled_true_when_state_lists_identity() {
    assertThat(svc.identityEnabled()).isTrue();
  }

  @Test
  void identityEnabled_false_when_state_omits_identity() {
    Mockito.when(state.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "media"), List.of()
    ));
    assertThat(svc.identityEnabled()).isFalse();
  }

  @Test
  void identityEnabled_false_when_enabled_list_is_null() {
    Mockito.when(state.readState()).thenReturn(new RepoState(
        1, null, null, null, null, null
    ));
    assertThat(svc.identityEnabled()).isFalse();
  }

  // ─── ensureSecrets ─────────────────────────────────────────────────────

  @Test
  void ensureSecrets_creates_env_from_example_and_generates_all_three() throws IOException {
    assertThat(Files.exists(svc.envPath())).isFalse();

    Set<String> generated = svc.ensureSecrets();

    assertThat(generated).containsExactly(
        "AUTHELIA_JWT_SECRET",
        "AUTHELIA_SESSION_SECRET",
        "AUTHELIA_STORAGE_ENCRYPTION_KEY"
    );
    // File exists, every managed key has a 64-char hex value.
    assertThat(Files.exists(svc.envPath())).isTrue();
    for (String key : IdentitySecretsService.MANAGED_KEYS) {
      String v = readValue(svc.envPath(), key);
      assertThat(v).isNotNull().hasSize(64).matches("[0-9a-f]{64}");
    }
  }

  @Test
  void ensureSecrets_preserves_comments_and_non_managed_keys_from_example() throws IOException {
    svc.ensureSecrets();
    String body = Files.readString(svc.envPath(), StandardCharsets.UTF_8);
    // Comments survived.
    assertThat(body).contains("# Copy to .env and fill in.");
    assertThat(body).contains("# ---- Optional SMTP");
    // Non-managed keys survived with their example values.
    assertThat(body).contains("TZ=Europe/London");
    assertThat(body).contains("DOMAIN=aurora.local");
    assertThat(body).contains("AUTHELIA_NOTIFIER_SMTP_PORT=587");
  }

  @Test
  void ensureSecrets_is_idempotent_and_second_call_returns_empty() throws IOException {
    svc.ensureSecrets();
    String firstBody = Files.readString(svc.envPath(), StandardCharsets.UTF_8);

    Set<String> secondGenerated = svc.ensureSecrets();
    String secondBody = Files.readString(svc.envPath(), StandardCharsets.UTF_8);

    assertThat(secondGenerated).isEmpty();
    // Byte-identical — no rewrite happened.
    assertThat(secondBody).isEqualTo(firstBody);
  }

  @Test
  void ensureSecrets_only_fills_missing_keys_and_leaves_present_ones_alone() throws IOException {
    // Seed an .env with two of three keys already set. The third is blank.
    Path envPath = svc.envPath();
    Files.writeString(envPath, String.join("\n",
        "TZ=Europe/London",
        "AUTHELIA_JWT_SECRET=preserved-jwt-value",
        "AUTHELIA_SESSION_SECRET=preserved-session-value",
        "AUTHELIA_STORAGE_ENCRYPTION_KEY=",
        ""
    ), StandardCharsets.UTF_8);

    Set<String> generated = svc.ensureSecrets();

    assertThat(generated).containsExactly("AUTHELIA_STORAGE_ENCRYPTION_KEY");
    assertThat(readValue(envPath, "AUTHELIA_JWT_SECRET")).isEqualTo("preserved-jwt-value");
    assertThat(readValue(envPath, "AUTHELIA_SESSION_SECRET")).isEqualTo("preserved-session-value");
    assertThat(readValue(envPath, "AUTHELIA_STORAGE_ENCRYPTION_KEY")).hasSize(64);
  }

  @Test
  void ensureSecrets_records_audit_row_only_when_a_key_was_generated() throws IOException {
    svc.ensureSecrets();
    // First call: three keys generated, one audit row.
    ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> diff = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit).record(Mockito.isNull(), action.capture(),
        Mockito.eq("packages/identity/.env"), diff.capture());
    assertThat(action.getValue()).isEqualTo("identity.secrets.bootstrap");
    assertThat(diff.getValue()).contains("AUTHELIA_JWT_SECRET");
    assertThat(diff.getValue()).contains("AUTHELIA_SESSION_SECRET");
    assertThat(diff.getValue()).contains("AUTHELIA_STORAGE_ENCRYPTION_KEY");

    // Second call: idempotent, no more audit rows.
    svc.ensureSecrets();
    Mockito.verify(audit, Mockito.times(1)).record(Mockito.any(), Mockito.anyString(),
        Mockito.anyString(), Mockito.anyString());
  }

  // ─── rotateSecrets ─────────────────────────────────────────────────────

  @Test
  void rotateSecrets_regenerates_every_key_even_when_present() throws IOException {
    svc.ensureSecrets();
    String jwtBefore = readValue(svc.envPath(), "AUTHELIA_JWT_SECRET");
    String sessBefore = readValue(svc.envPath(), "AUTHELIA_SESSION_SECRET");
    String storBefore = readValue(svc.envPath(), "AUTHELIA_STORAGE_ENCRYPTION_KEY");

    Set<String> rotated = svc.rotateSecrets(42L);

    assertThat(rotated).containsExactlyInAnyOrder(
        "AUTHELIA_JWT_SECRET",
        "AUTHELIA_SESSION_SECRET",
        "AUTHELIA_STORAGE_ENCRYPTION_KEY"
    );
    assertThat(readValue(svc.envPath(), "AUTHELIA_JWT_SECRET")).isNotEqualTo(jwtBefore);
    assertThat(readValue(svc.envPath(), "AUTHELIA_SESSION_SECRET")).isNotEqualTo(sessBefore);
    assertThat(readValue(svc.envPath(), "AUTHELIA_STORAGE_ENCRYPTION_KEY")).isNotEqualTo(storBefore);
  }

  @Test
  void rotateSecrets_audit_row_carries_acting_user_id() throws IOException {
    svc.rotateSecrets(42L);

    ArgumentCaptor<Long> userId = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit).record(userId.capture(), action.capture(),
        Mockito.anyString(), Mockito.contains("rotated_keys"));
    assertThat(userId.getValue()).isEqualTo(42L);
    assertThat(action.getValue()).isEqualTo("identity.secrets.rotate");
  }

  @Test
  void rotateSecrets_never_reveals_secret_values_in_audit_diff() throws IOException {
    svc.rotateSecrets(42L);
    String jwtValue = readValue(svc.envPath(), "AUTHELIA_JWT_SECRET");
    // Real threat guard: even if a future refactor accidentally builds the
    // audit diff from the .env body, the actual hex secret must never
    // appear in what Mockito captured.
    ArgumentCaptor<String> diff = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit).record(Mockito.any(), Mockito.eq("identity.secrets.rotate"),
        Mockito.anyString(), diff.capture());
    assertThat(diff.getValue()).doesNotContain(jwtValue);
  }

  // ─── secret shape ──────────────────────────────────────────────────────

  @Test
  void generateSecret_produces_64_lowercase_hex_chars_which_is_32_bytes() {
    for (int i = 0; i < 8; i++) {
      String s = svc.generateSecret();
      assertThat(s).hasSize(64).matches("[0-9a-f]{64}");
    }
  }

  @Test
  void generateSecret_is_random_enough_that_no_two_match_in_practice() {
    var seen = new java.util.HashSet<String>();
    for (int i = 0; i < 100; i++) seen.add(svc.generateSecret());
    // 100 truly-random 32-byte values collide with negligible probability;
    // this asserts the RNG isn't wired to a constant seed.
    assertThat(seen).hasSize(100);
  }

  // ─── file permissions ──────────────────────────────────────────────────

  @Test
  void ensureSecrets_writes_env_with_owner_only_perms_on_posix() throws IOException {
    svc.ensureSecrets();
    try {
      var perms = Files.getPosixFilePermissions(svc.envPath());
      assertThat(perms).containsExactlyInAnyOrder(
          java.nio.file.attribute.PosixFilePermission.OWNER_READ,
          java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
      );
    } catch (UnsupportedOperationException e) {
      // Windows / non-POSIX host running the test — skip. Same
      // fall-through the production writer uses so we don't
      // artificially fail cross-platform runs.
    }
  }

  // ─── helpers ───────────────────────────────────────────────────────────

  private static String readValue(Path envPath, String key) throws IOException {
    List<String> lines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
    return IdentitySecretsService.readValue(lines, key);
  }
}
