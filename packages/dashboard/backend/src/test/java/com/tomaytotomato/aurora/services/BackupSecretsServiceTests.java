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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-time seeding of {@code KOPIA_PASSWORD} in
 * {@code packages/backup/.env}.
 *
 * <p>Bug context: caught during the 27 Aug 2026 QA sweep. The backup
 * package's kopia container was restart-looping every three seconds
 * with {@code listen address not allowed for insecure server without
 * password}. The .env file existed but {@code KOPIA_PASSWORD=} was
 * blank. Bruce would have had to know to run {@code rotate-secrets.sh
 * --apply} to fix this, and that same script would happily rotate an
 * existing password on an already-configured repository \u2014 which is
 * the wrong behaviour for a kopia encryption password.
 */
class BackupSecretsServiceTests {

  @TempDir
  Path tmp;

  private BackupSecretsService svc;
  private AuroraProperties props;
  private AuditEventRepo audit;

  @BeforeEach
  void setUp() {
    props = Mockito.mock(AuroraProperties.class);
    Mockito.when(props.repoPath()).thenReturn(tmp.toString());
    audit = Mockito.mock(AuditEventRepo.class);
    svc = new BackupSecretsService(props, audit);
  }

  private Path envPath() throws Exception {
    Path pkg = tmp.resolve("packages/backup");
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

  @Test
  void missing_env_file_is_a_no_op() throws Exception {
    // Nothing to seed; onReady's file-existence guard covers this,
    // but ensureSecret itself would just throw NoSuchFileException
    // if callers skipped the guard. Belt-and-braces: onReady calls
    // ensureSecret only when the file exists, and we assert audit
    // is untouched in that case.
    svc.onReady();
    Mockito.verifyNoInteractions(audit);
    assertThat(Files.exists(tmp.resolve("packages/backup/.env"))).isFalse();
  }

  @Test
  void empty_KOPIA_PASSWORD_is_seeded_and_audited() throws Exception {
    Files.writeString(envPath(), String.join("\n",
        "# comment",
        "TZ=Europe/London",
        "KOPIA_UI_USER=admin",
        "KOPIA_UI_PASSWORD=changeme",
        "KOPIA_PASSWORD=",
        ""));

    boolean wrote = svc.ensureSecret();

    assertThat(wrote).isTrue();
    String v = valueFor("KOPIA_PASSWORD");
    assertThat(v).hasSize(64); // 32 bytes -> 64 hex chars
    assertThat(v).matches("[0-9a-f]+");

    ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit).record(Mockito.isNull(), action.capture(),
        Mockito.eq("packages/backup/.env"), Mockito.anyString());
    assertThat(action.getValue()).isEqualTo("backup.secrets.bootstrap");
  }

  @Test
  void populated_KOPIA_PASSWORD_is_left_alone() throws Exception {
    // The whole reason this service exists: kopia's repository
    // encryption key must never be rotated once the repo has been
    // created. A populated value has to survive every boot.
    String pinned = "0123456789abcdef0123456789abcdef";
    Files.writeString(envPath(), "KOPIA_PASSWORD=" + pinned + "\n");

    boolean wrote = svc.ensureSecret();

    assertThat(wrote).isFalse();
    assertThat(valueFor("KOPIA_PASSWORD")).isEqualTo(pinned);
    Mockito.verifyNoInteractions(audit);
  }

  @Test
  void ensureSecret_is_idempotent_across_calls() throws Exception {
    Files.writeString(envPath(), "KOPIA_PASSWORD=\n");

    assertThat(svc.ensureSecret()).isTrue();
    String firstValue = valueFor("KOPIA_PASSWORD");
    assertThat(svc.ensureSecret()).isFalse();
    // Second call must not have replaced the first value.
    assertThat(valueFor("KOPIA_PASSWORD")).isEqualTo(firstValue);
    // Audit still fires exactly once.
    Mockito.verify(audit, Mockito.times(1)).record(any(), any(), any(), any());
  }

  @Test
  void whitespace_only_KOPIA_PASSWORD_is_treated_as_empty() throws Exception {
    // Some editors auto-strip trailing spaces on save, but not always.
    // A password of purely whitespace is meaningless to kopia anyway
    // \u2014 it still refuses to start.
    Files.writeString(envPath(), "KOPIA_PASSWORD=   \n");
    assertThat(svc.ensureSecret()).isTrue();
    assertThat(valueFor("KOPIA_PASSWORD")).hasSize(64);
  }

  @Test
  void KOPIA_UI_PASSWORD_is_never_touched() throws Exception {
    // The service's scope is exactly one key. Changing the UI password
    // is a nuisance-not-catastrophe operation and belongs in
    // rotate-secrets.sh where the intent is explicit.
    Files.writeString(envPath(), String.join("\n",
        "KOPIA_UI_PASSWORD=changeme",
        "KOPIA_PASSWORD=",
        ""));

    svc.ensureSecret();

    assertThat(valueFor("KOPIA_UI_PASSWORD")).isEqualTo("changeme");
  }

  @Test
  void preserves_comments_and_other_lines_verbatim() throws Exception {
    // Operator-authored comments in .env are how future-Bruce
    // remembers what a value meant. Rewriting the file must keep
    // them.
    Files.writeString(envPath(), String.join("\n",
        "# do not lose this file",
        "TZ=Europe/London",
        "",
        "# encryption key \u2014 backups depend on it",
        "KOPIA_PASSWORD=",
        "",
        "# UI creds",
        "KOPIA_UI_USER=admin",
        ""));

    svc.ensureSecret();

    String body = readEnv();
    assertThat(body).contains("# do not lose this file");
    assertThat(body).contains("# encryption key");
    assertThat(body).contains("# UI creds");
    assertThat(body).contains("TZ=Europe/London");
    assertThat(body).contains("KOPIA_UI_USER=admin");
  }

  private static <T> T any() { return Mockito.any(); }
}
