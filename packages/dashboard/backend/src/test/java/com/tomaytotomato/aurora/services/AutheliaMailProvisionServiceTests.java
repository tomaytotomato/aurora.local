package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.MailboxSummary;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AutheliaMailProvisionService}: writes the SMTP env block into
 * {@code packages/core/.env} and provisions the {@code authelia@$DOMAIN}
 * mailbox on the box's Stalwart so Authelia's password-reset and
 * 2FA-enrolment links land in a real inbox rather than in
 * {@code data/authelia/notification.txt} (C26).
 *
 * <p>What is pinned:
 *
 * <ol>
 *   <li>Idempotency \u2014 a second pass on an already-provisioned .env is a
 *       silent no-op. Otherwise the 30-minute reconcile would keep
 *       rotating the SMTP password and Authelia would keep breaking.</li>
 *   <li>The ordering gate \u2014 no writes until JMAP is reachable AND the
 *       mail domain exists. Otherwise the mailbox-create call fails and
 *       we would leave a half-configured .env behind.</li>
 *   <li>Existing-mailbox recovery \u2014 the mailbox is reset rather than
 *       silently accepted, because Stalwart only stores hashes and Aurora
 *       cannot recover a previously-set password.</li>
 *   <li>The five env keys we write match compose's shell-passthrough
 *       list. A drift there would silently drop one of them (an env var
 *       Compose does not forward is not in the container's env).</li>
 * </ol>
 */
class AutheliaMailProvisionServiceTests {

  @TempDir
  Path repo;

  private Path envPath;
  private AuroraProperties props;
  private StalwartProvisionService provision;
  private StalwartMailClient mail;
  private AuditEventRepo audit;
  private AutheliaMailProvisionService svc;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(repo.resolve("packages").resolve("core"));
    envPath = repo.resolve("packages").resolve("core").resolve(".env");
    // A .env with commented-out AUTHELIA_NOTIFIER_SMTP_* placeholders,
    // matching the real .env.example. The service must uncomment them
    // in place, not append a second line.
    Files.writeString(envPath, String.join("\n",
        "STALWART_DB_PASSWORD=abc",
        "# AUTHELIA_NOTIFIER_SMTP_ADDRESS=",
        "# AUTHELIA_NOTIFIER_SMTP_USERNAME=",
        "# AUTHELIA_NOTIFIER_SMTP_PASSWORD=",
        "# AUTHELIA_NOTIFIER_SMTP_SENDER=authelia@aurora.local",
        "") + "\n", StandardCharsets.UTF_8);

    props = new AuroraProperties(repo.toString(), "/proc", List.of(),
        new AuroraProperties.Docker("unix:///dev/null"));
    provision = Mockito.mock(StalwartProvisionService.class);
    when(provision.mailDomain()).thenReturn("aurora.local");
    mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(true);
    when(mail.domainExists("aurora.local")).thenReturn(true);
    when(mail.listMailboxes()).thenReturn(List.of());
    audit = Mockito.mock(AuditEventRepo.class);
    svc = new AutheliaMailProvisionService(provision, mail, props, audit);
  }

  @Test
  void writes_all_five_smtp_env_keys_and_creates_the_mailbox_on_first_run() throws IOException {
    svc.provisionQuietly();

    String written = Files.readString(envPath, StandardCharsets.UTF_8);
    for (String key : AutheliaMailProvisionService.MANAGED_KEYS) {
      // Present as "KEY=..." (not "# KEY=..." — the commented-out
      // placeholder was replaced in place).
      assertThat(written)
          .as("env should carry %s uncommented", key)
          .containsPattern("(?m)^" + java.util.regex.Pattern.quote(key) + "=.+$");
    }
    // The host points at the aurora_net container, not localhost, and
    // the sender uses the box's own domain.
    assertThat(written).contains("AUTHELIA_NOTIFIER_SMTP_ADDRESS=submission://stalwart:587");
    assertThat(written).contains("AUTHELIA_NOTIFIER_SMTP_SENDER=authelia@aurora.local");
    assertThat(written).contains("AUTHELIA_NOTIFIER_SMTP_USERNAME=authelia@aurora.local");

    // Original comment-and-value structure preserved: STALWART_DB_PASSWORD
    // did not move and the .env file did not double any keys.
    assertThat(written.split("(?m)^AUTHELIA_NOTIFIER_SMTP_ADDRESS=", -1)).hasSize(2);
    assertThat(written).contains("STALWART_DB_PASSWORD=abc");

    // Mailbox was created (not reset), and audit row records the
    // sender address but NOT the password.
    verify(mail).createMailbox(eq("authelia"), eq("aurora.local"), anyString());
    verify(mail, never()).resetMailboxPassword(anyString(), anyString());
    verify(audit).record(any(), eq("authelia.mail.provision"),
        eq("packages/core/.env"),
        eq("{\"sender\":\"authelia@aurora.local\"}"));
  }

  @Test
  void a_second_run_on_a_provisioned_env_is_a_silent_no_op() throws IOException {
    svc.provisionQuietly();
    String firstPass = Files.readString(envPath, StandardCharsets.UTF_8);

    Mockito.clearInvocations(mail, audit);
    svc.provisionQuietly();
    String secondPass = Files.readString(envPath, StandardCharsets.UTF_8);

    // No rewrite: the file bytes are identical, which also means the
    // SMTP password did NOT rotate. That is the whole point of the
    // idempotency gate \u2014 the reconcile would otherwise churn the
    // password and break Authelia between rebuilds.
    assertThat(secondPass).isEqualTo(firstPass);
    verify(mail, never()).createMailbox(anyString(), anyString(), anyString());
    verify(mail, never()).resetMailboxPassword(anyString(), anyString());
    verify(audit, never()).record(any(), anyString(), anyString(), anyString());
  }

  @Test
  void an_existing_authelia_mailbox_gets_reset_rather_than_recreated() throws IOException {
    // Existing box: someone or something already created authelia@... but
    // Aurora does not know the password (Stalwart stores hashes only).
    // The recovery is a reset, not a delete+create, because delete throws
    // away any other state a future Stalwart might attach to the account.
    when(mail.listMailboxes()).thenReturn(List.of(
        new MailboxSummary("acc-1", "authelia@aurora.local", 0L, null, "2026-08-01T00:00:00Z")));

    svc.provisionQuietly();

    verify(mail).resetMailboxPassword(eq("acc-1"), anyString());
    verify(mail, never()).createMailbox(anyString(), anyString(), anyString());
  }

  @Test
  void does_nothing_when_jmap_is_not_reachable_yet() throws IOException {
    // Startup race: Stalwart is still coming up. Retrying is safe; leaving
    // a half-written .env behind is not (Authelia would fail to start
    // with an SMTP host that answers nothing).
    when(mail.reachable()).thenReturn(false);

    svc.provisionQuietly();

    String written = Files.readString(envPath, StandardCharsets.UTF_8);
    assertThat(written).doesNotContain("AUTHELIA_NOTIFIER_SMTP_ADDRESS=submission");
    verify(mail, never()).createMailbox(anyString(), anyString(), anyString());
    verify(audit, never()).record(any(), anyString(), anyString(), anyString());
  }

  @Test
  void does_nothing_when_the_domain_is_missing_from_stalwart() throws IOException {
    // The mailbox-create call requires the domain to exist first.
    // StalwartProvisionService provisions it asynchronously, so on a
    // fresh box we may see reachable=true but domainExists=false for a
    // short window \u2014 that has to be a retry, not a failure.
    when(mail.domainExists("aurora.local")).thenReturn(false);

    svc.provisionQuietly();

    String written = Files.readString(envPath, StandardCharsets.UTF_8);
    assertThat(written).doesNotContain("AUTHELIA_NOTIFIER_SMTP_ADDRESS=submission");
    verify(mail, never()).createMailbox(anyString(), anyString(), anyString());
  }

  @Test
  void never_throws_when_the_client_fails() {
    // A transient JMAP error must not crash the scheduler; the reconcile
    // will try again on the next tick.
    when(mail.createMailbox(anyString(), anyString(), anyString()))
        .thenThrow(new StalwartMailClient.StalwartApiException("boom"));

    // No exception even though createMailbox blew up.
    svc.provisionQuietly();
  }

  @Test
  void does_nothing_when_the_env_file_is_absent() throws IOException {
    // Integration-test fixtures skip creating packages/core/.env
    // sometimes; the service must not manufacture one from thin air
    // (that would pollute the fixture with a strong password and an
    // audit row for a non-existent box).
    Files.delete(envPath);

    svc.provisionQuietly();

    assertThat(Files.exists(envPath)).isFalse();
    verify(mail, never()).createMailbox(anyString(), anyString(), anyString());
  }
}
