package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.RepoState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StalwartProvisionService}: auto-creates the box's mail domain,
 * derived from {@code .state.yml}, and never throws.
 */
class StalwartProvisionServiceTests {

  private static StateFileService stateWithDomain(String domain) {
    StateFileService s = Mockito.mock(StateFileService.class);
    when(s.readState()).thenReturn(new RepoState(1, "host", domain, null, List.of(), List.of()));
    return s;
  }

  @Test
  void ensures_the_boxs_own_domain() {
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(true);
    var svc = new StalwartProvisionService(stateWithDomain("aurora.local"), mail);

    svc.ensureDomainQuietly();

    verify(mail).ensureDomain("aurora.local");
  }

  @Test
  void falls_back_to_the_default_domain_when_state_has_none() {
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(true);
    var svc = new StalwartProvisionService(stateWithDomain(null), mail);

    svc.ensureDomainQuietly();

    verify(mail).ensureDomain(StalwartProvisionService.DEFAULT_DOMAIN);
  }

  @Test
  void does_nothing_when_stalwart_is_not_reachable_yet() {
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(false);
    var svc = new StalwartProvisionService(stateWithDomain("aurora.local"), mail);

    svc.ensureDomainQuietly();

    verify(mail, never()).ensureDomain(Mockito.anyString());
  }

  @Test
  void never_throws_when_the_client_fails() {
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(true);
    when(mail.ensureDomain(Mockito.anyString()))
        .thenThrow(new StalwartMailClient.StalwartApiException("boom"));
    var svc = new StalwartProvisionService(stateWithDomain("aurora.local"), mail);

    // Must not propagate — provisioning is best-effort, retried on schedule.
    svc.ensureDomainQuietly();

    verify(mail, times(1)).ensureDomain("aurora.local");
  }
}
