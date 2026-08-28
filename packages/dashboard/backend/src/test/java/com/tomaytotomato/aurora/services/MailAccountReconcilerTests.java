package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.MailboxSummary;
import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every Aurora user has a mailbox — including the one the wizard creates.
 *
 * <p>The Users page provisioned a mailbox on create; the onboarding wizard
 * went through {@code AdminUserRepo} directly and did not. So the single
 * account guaranteed to exist on every box, the owner's, was the only one
 * without mail, and Stalwart looked empty on a fresh install.
 */
class MailAccountReconcilerTests {

  private AdminUserRepo users;
  private StateFileService stateFiles;
  private StalwartMailClient mail;
  private MailAccountReconciler svc;

  @BeforeEach
  void setUp() {
    users = mock(AdminUserRepo.class);
    stateFiles = mock(StateFileService.class);
    mail = mock(StalwartMailClient.class);
    svc = new MailAccountReconciler(users, stateFiles, mail, mock(AuditEventRepo.class));

    when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", "2026-08-28T00:00:00Z", List.of("core"), List.of()));
    when(mail.ensureDomain(anyString())).thenReturn(true);
    when(mail.listMailboxes()).thenReturn(List.of());
  }

  private static AdminUser user(long id, String name) {
    return new AdminUser(id, name, "$2a$12$hash-for-" + name, "UTC",
        "2026-08-28T00:00:00Z", Role.ADMIN);
  }

  @Test
  void givesTheWizardsAdminTheMailboxTheUsersPageWouldHaveGiven() {
    when(users.findAll()).thenReturn(List.of(user(1, "sarah")));

    assertThat(svc.reconcile("startup")).isEqualTo(1);

    verify(mail).createMailbox(eq("sarah"), eq("aurora.local"), eq("$2a$12$hash-for-sarah"));
  }

  @Test
  void copiesTheBcryptHashSoOnePasswordStillWorksForBoth() {
    // Aurora never stores plaintext, so healing an existing account can
    // only work if Stalwart verifies against the hash — which it does
    // (proven against v0.16.19). Anything else would mean a second
    // password the owner was never told about.
    when(users.findAll()).thenReturn(List.of(user(1, "sarah")));

    svc.reconcile("startup");

    verify(mail).createMailbox(anyString(), anyString(),
        org.mockito.ArgumentMatchers.startsWith("$2a$"));
  }

  @Test
  void isIdempotent_soTheFiveMinuteDriftGuardIsFree() {
    when(users.findAll()).thenReturn(List.of(user(1, "sarah")));
    when(mail.listMailboxes()).thenReturn(List.of(
        new MailboxSummary("m1", "sarah@aurora.local", 0L, null, "2026-08-28T00:00:00Z")));

    assertThat(svc.reconcile("schedule")).isZero();
    verify(mail, never()).createMailbox(anyString(), anyString(), anyString());
  }

  @Test
  void matchesExistingMailboxesCaseInsensitively() {
    when(users.findAll()).thenReturn(List.of(user(1, "Sarah")));
    when(mail.listMailboxes()).thenReturn(List.of(
        new MailboxSummary("m1", "SARAH@AURORA.LOCAL", 0L, null, "2026-08-28T00:00:00Z")));

    assertThat(svc.reconcile("schedule")).isZero();
  }

  @Test
  void waitsRatherThanFailingWhenTheMailDomainIsNotProvisionedYet() {
    // The domain is created asynchronously after boot, so on a fresh box
    // this runs before it exists. That is a "come back later", not an error.
    when(users.findAll()).thenReturn(List.of(user(1, "sarah")));
    when(mail.ensureDomain(anyString())).thenReturn(false);

    assertThat(svc.reconcile("startup")).isZero();
    verify(mail, never()).createMailbox(anyString(), anyString(), anyString());
  }

  @Test
  void oneBadUsernameDoesNotDenyEveryoneElseTheirMail() {
    when(users.findAll()).thenReturn(List.of(user(1, "sarah"), user(2, "bruce")));
    when(mail.createMailbox(eq("sarah"), anyString(), anyString()))
        .thenThrow(new StalwartMailClient.StalwartApiException("boom"));

    assertThat(svc.reconcile("startup")).isEqualTo(1);
    verify(mail, times(2)).createMailbox(anyString(), anyString(), anyString());
  }

  @Test
  void skipsUsernamesThatCannotBeAnEmailLocalPart() {
    // Mangling "sarah jones" into an address risks handing one person
    // another person's mail. Skipping is the safe answer.
    assertThat(MailAccountReconciler.localPartFor(user(1, "sarah jones"))).isNull();
    assertThat(MailAccountReconciler.localPartFor(user(1, "sarah.jones"))).isEqualTo("sarah.jones");
    assertThat(MailAccountReconciler.localPartFor(user(1, "Sarah"))).isEqualTo("sarah");
  }

  @Test
  void neverThrowsAtAUserWithNoMailServerRunning() {
    when(users.findAll()).thenReturn(List.of(user(1, "sarah")));
    when(mail.ensureDomain(anyString())).thenThrow(new RuntimeException("connection refused"));

    // The reconciler is called from boot and from a scheduler; a mail
    // server that is down must not take anything else with it.
    svc.onReady();
    svc.scheduled();
  }
}
