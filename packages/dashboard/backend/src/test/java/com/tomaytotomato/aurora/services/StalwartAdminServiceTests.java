package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only Stalwart recovery-admin surface.
 *
 * <p>The reveal panel on /apps/core/services/stalwart calls
 * {@link StalwartAdminService#currentCredential()} on every render, so
 * the exact question "what does the panel say right now" reduces to
 * these assertions.
 */
class StalwartAdminServiceTests {

  @TempDir
  Path tmp;

  private StalwartAdminService svc;

  @BeforeEach
  void setUp() {
    AuroraProperties props = Mockito.mock(AuroraProperties.class);
    Mockito.when(props.repoPath()).thenReturn(tmp.toString());
    svc = new StalwartAdminService(props);
  }

  private Path envPath() throws Exception {
    Path pkg = tmp.resolve("packages/core");
    Files.createDirectories(pkg);
    return pkg.resolve(".env");
  }

  @Test
  void missing_env_falls_back_to_the_compose_default() {
    // No packages/core/.env at all: the container is running with the
    // compose file's {STALWART_ADMIN_SECRET:-aurora-change-me} default,
    // and the panel has to say so.
    var cred = svc.currentCredential();
    assertThat(cred.username()).isEqualTo("admin");
    assertThat(cred.secret()).isEqualTo("aurora-change-me");
    assertThat(cred.source()).isEqualTo(StalwartAdminService.Source.DEFAULT);
  }

  @Test
  void blank_env_value_is_treated_as_default() throws Exception {
    // Same story as missing: an empty right-hand-side means compose is
    // still substituting the fallback. The panel should not lie about
    // that just because the operator committed a placeholder line.
    Files.writeString(envPath(),
        "TZ=Europe/London\nSTALWART_ADMIN_SECRET=\nDOMAIN=aurora.local\n");
    var cred = svc.currentCredential();
    assertThat(cred.source()).isEqualTo(StalwartAdminService.Source.DEFAULT);
    assertThat(cred.secret()).isEqualTo("aurora-change-me");
  }

  @Test
  void whitespace_only_value_is_treated_as_default() throws Exception {
    // Editors that trim on save vary. A key set to purely whitespace
    // still evaluates to blank on the compose side, so the same "the
    // default is what actually runs" contract holds.
    Files.writeString(envPath(),
        "STALWART_ADMIN_SECRET=   \n");
    var cred = svc.currentCredential();
    assertThat(cred.source()).isEqualTo(StalwartAdminService.Source.DEFAULT);
    assertThat(cred.secret()).isEqualTo("aurora-change-me");
  }

  @Test
  void real_value_is_reported_verbatim_and_flagged_ENV() throws Exception {
    Files.writeString(envPath(),
        "STALWART_ADMIN_SECRET=abc123-a-real-strong-value\n");
    var cred = svc.currentCredential();
    assertThat(cred.username()).isEqualTo("admin");
    assertThat(cred.secret()).isEqualTo("abc123-a-real-strong-value");
    assertThat(cred.source()).isEqualTo(StalwartAdminService.Source.ENV);
  }

  @Test
  void inline_comment_after_value_is_stripped() throws Exception {
    // The example .env encourages inline comments; a `foo=bar # note`
    // value has to be read as bar, not "bar # note". Same shape
    // IdentitySecretsService's parser uses so the two agree on
    // what "the value" is.
    Files.writeString(envPath(),
        "STALWART_ADMIN_SECRET=abc-strong # rotate me before v1\n");
    var cred = svc.currentCredential();
    assertThat(cred.secret()).isEqualTo("abc-strong");
    assertThat(cred.source()).isEqualTo(StalwartAdminService.Source.ENV);
  }

  @Test
  void other_keys_are_not_confused_with_ours() throws Exception {
    // A key that starts with STALWART_ADMIN_SECRET but is a different
    // name entirely (paranoid pin) must not be picked up.
    Files.writeString(envPath(), String.join("\n",
        "STALWART_ADMIN_SECRETS_LIST=one,two,three",
        "MAIL_HOSTNAME=mail.aurora.local",
        ""));
    var cred = svc.currentCredential();
    assertThat(cred.source()).isEqualTo(StalwartAdminService.Source.DEFAULT);
  }

  @Test
  void currentCredential_never_throws_on_broken_env() throws Exception {
    // The reveal panel calls this on every render, sometimes before
    // the wizard has created the file. A parse error must not blank
    // the whole detail page. Simulate by making the parent path a
    // regular file so open-as-file fails.
    var pkg = tmp.resolve("packages/core");
    Files.createDirectories(pkg);
    // .env is a directory here, not a file: readAllLines throws.
    Files.createDirectory(pkg.resolve(".env"));
    var cred = svc.currentCredential();
    assertThat(cred.source()).isEqualTo(StalwartAdminService.Source.DEFAULT);
    assertThat(cred.secret()).isEqualTo("aurora-change-me");
  }
}
