package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boot-time seeding + explicit rotation of {@code STALWART_ADMIN_SECRET}
 * in {@code packages/core/.env}.
 *
 * <p>The seeding path exists because a fresh box that skips
 * {@code rotate-secrets.sh --apply} boots with Stalwart's compose
 * fallback ({@code aurora-change-me}) \u2014 the same every-attacker-knows-it
 * default {@link StalwartAdminService} flags as
 * {@link StalwartAdminService.Source#DEFAULT}. The rotation path
 * powers the reveal panel's Save button; the two share the same file
 * mutation shape so a change to one keeps the audit + comment-preserving
 * contract intact for the other.
 */
class StalwartSecretsServiceTests {

  @TempDir
  Path tmp;

  private StalwartSecretsService svc;
  private AuroraProperties props;
  private AuditEventRepo audit;

  @BeforeEach
  void setUp() {
    props = Mockito.mock(AuroraProperties.class);
    Mockito.when(props.repoPath()).thenReturn(tmp.toString());
    audit = Mockito.mock(AuditEventRepo.class);
    svc = new StalwartSecretsService(props, audit);
  }

  private Path envPath() throws Exception {
    Path pkg = tmp.resolve("packages/core");
    Files.createDirectories(pkg);
    return pkg.resolve(".env");
  }

  private String readEnv() throws Exception {
    return Files.readString(envPath());
  }

  private String valueFor(String key) throws Exception {
    for (String line : Files.readAllLines(envPath())) {
      if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
    }
    return null;
  }

  // \u2500\u2500\u2500 boot-time seeding \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @Test
  void missing_env_file_is_a_no_op() throws Exception {
    // A repo that has never had core rendered yet. onReady's
    // existence guard short-circuits before ensureSecret runs, so the
    // audit log stays clean and no file is manufactured.
    svc.onReady();
    Mockito.verifyNoInteractions(audit);
    assertThat(Files.exists(tmp.resolve("packages/core/.env"))).isFalse();
  }

  @Test
  void empty_STALWART_ADMIN_SECRET_is_seeded_and_audited() throws Exception {
    Files.writeString(envPath(), String.join("\n",
        "# core package env",
        "TZ=Europe/London",
        "DOMAIN=aurora.local",
        "STALWART_ADMIN_SECRET=",
        ""));

    boolean wrote = svc.ensureSecret();

    assertThat(wrote).isTrue();
    String v = valueFor("STALWART_ADMIN_SECRET");
    assertThat(v).hasSize(64); // 32 bytes -> 64 hex chars
    assertThat(v).matches("[0-9a-f]+");

    ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit).record(Mockito.isNull(), action.capture(),
        Mockito.eq("packages/core/.env"), Mockito.anyString());
    assertThat(action.getValue()).isEqualTo("stalwart.secrets.bootstrap");
  }

  @Test
  void populated_STALWART_ADMIN_SECRET_is_left_alone() throws Exception {
    // Compose interpolates env at container-create time. Silently
    // overwriting a populated value would leave the reveal panel
    // showing something the live container has never heard of \u2014
    // exactly the confusion the panel exists to prevent.
    String pinned = "0123456789abcdef0123456789abcdef";
    Files.writeString(envPath(), "STALWART_ADMIN_SECRET=" + pinned + "\n");

    boolean wrote = svc.ensureSecret();

    assertThat(wrote).isFalse();
    assertThat(valueFor("STALWART_ADMIN_SECRET")).isEqualTo(pinned);
    Mockito.verifyNoInteractions(audit);
  }

  @Test
  void ensureSecret_is_idempotent_across_calls() throws Exception {
    Files.writeString(envPath(), "STALWART_ADMIN_SECRET=\n");

    assertThat(svc.ensureSecret()).isTrue();
    String firstValue = valueFor("STALWART_ADMIN_SECRET");
    assertThat(svc.ensureSecret()).isFalse();
    // Second call must not have replaced the first value.
    assertThat(valueFor("STALWART_ADMIN_SECRET")).isEqualTo(firstValue);
    // Audit still fires exactly once.
    Mockito.verify(audit, Mockito.times(1)).record(any(), any(), any(), any());
  }

  @Test
  void whitespace_only_STALWART_ADMIN_SECRET_is_treated_as_empty() throws Exception {
    Files.writeString(envPath(), "STALWART_ADMIN_SECRET=   \n");
    assertThat(svc.ensureSecret()).isTrue();
    assertThat(valueFor("STALWART_ADMIN_SECRET")).hasSize(64);
  }

  @Test
  void preserves_comments_and_other_lines_verbatim_on_seed() throws Exception {
    // Operator-authored comments in .env are how future-Bruce
    // remembers what a value meant. Rewriting the file must keep them,
    // and every non-owned key must survive untouched.
    Files.writeString(envPath(), String.join("\n",
        "# packages/core/.env \u2014 do not lose",
        "TZ=Europe/London",
        "DOMAIN=aurora.local",
        "",
        "# recovery admin for mail-admin console",
        "STALWART_ADMIN_SECRET=",
        "",
        "AUTHELIA_JWT_SECRET=abc-do-not-touch-me",
        ""));

    svc.ensureSecret();

    String body = readEnv();
    assertThat(body).contains("# packages/core/.env");
    assertThat(body).contains("# recovery admin for mail-admin console");
    assertThat(body).contains("TZ=Europe/London");
    assertThat(body).contains("DOMAIN=aurora.local");
    // The one key we do NOT own must survive verbatim.
    assertThat(valueFor("AUTHELIA_JWT_SECRET")).isEqualTo("abc-do-not-touch-me");
  }

  // \u2500\u2500\u2500 rotation \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @Test
  void writeSecret_persists_new_value_and_audits_with_acting_user() throws Exception {
    Files.writeString(envPath(), String.join("\n",
        "STALWART_ADMIN_SECRET=old-populated-value",
        "AUTHELIA_JWT_SECRET=keep-me",
        ""));

    svc.writeSecret("brand-new-strong-value", 7L);

    assertThat(valueFor("STALWART_ADMIN_SECRET")).isEqualTo("brand-new-strong-value");
    // Non-owned keys must be preserved verbatim.
    assertThat(valueFor("AUTHELIA_JWT_SECRET")).isEqualTo("keep-me");

    ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> user = ArgumentCaptor.forClass(Long.class);
    Mockito.verify(audit).record(user.capture(), action.capture(),
        Mockito.eq("packages/core/.env"), Mockito.anyString());
    assertThat(user.getValue()).isEqualTo(7L);
    assertThat(action.getValue()).isEqualTo("stalwart.admin-secret.rotate");
  }

  @Test
  void writeSecret_creates_file_when_missing() throws Exception {
    // Operator hit Save on the reveal panel before the wizard had
    // written .env. Explicit action = do the thing they asked for.
    // packages/core/ may not exist yet, so we create the tree.
    svc.writeSecret("brand-new-strong-value", 42L);

    assertThat(valueFor("STALWART_ADMIN_SECRET")).isEqualTo("brand-new-strong-value");
    Mockito.verify(audit).record(Mockito.eq(42L),
        Mockito.eq("stalwart.admin-secret.rotate"),
        Mockito.eq("packages/core/.env"), Mockito.anyString());
  }

  @Test
  void writeSecret_rejects_too_short_value() throws Exception {
    Files.writeString(envPath(), "STALWART_ADMIN_SECRET=old-populated-value\n");

    assertThatThrownBy(() -> svc.writeSecret("short", 1L))
        .isInstanceOf(IllegalArgumentException.class);

    // Old value must survive.
    assertThat(valueFor("STALWART_ADMIN_SECRET")).isEqualTo("old-populated-value");
    Mockito.verifyNoInteractions(audit);
  }

  @Test
  void writeSecret_rejects_null_value() throws Exception {
    assertThatThrownBy(() -> svc.writeSecret(null, 1L))
        .isInstanceOf(IllegalArgumentException.class);
    Mockito.verifyNoInteractions(audit);
  }

  @Test
  void writeSecret_preserves_comments_and_other_keys() throws Exception {
    Files.writeString(envPath(), String.join("\n",
        "# packages/core/.env",
        "TZ=Europe/London",
        "",
        "# recovery admin",
        "STALWART_ADMIN_SECRET=old-populated-value",
        "",
        "# authelia",
        "AUTHELIA_JWT_SECRET=untouched",
        ""));

    svc.writeSecret("brand-new-strong-value", 1L);

    String body = readEnv();
    assertThat(body).contains("# packages/core/.env");
    assertThat(body).contains("# recovery admin");
    assertThat(body).contains("# authelia");
    assertThat(body).contains("TZ=Europe/London");
    assertThat(valueFor("STALWART_ADMIN_SECRET")).isEqualTo("brand-new-strong-value");
    assertThat(valueFor("AUTHELIA_JWT_SECRET")).isEqualTo("untouched");
  }

  private static <T> T any() { return Mockito.any(); }
}
