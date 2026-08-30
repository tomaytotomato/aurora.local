package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StalwartRegistrySeedService}: fills in the JMAP writes the
 * Stalwart setup wizard would have made (C27). What matters to pin:
 *
 * <ol>
 *   <li>The seed runs only after JMAP is reachable AND the domain is
 *       present. Otherwise the SystemSettings write would fail
 *       (defaultDomain is Id&lt;Domain&gt;), and the six listeners would
 *       apply against a server that has not finished booting.</li>
 *   <li>All six advertised listeners are written, and each with the
 *       right protocol/bind/tlsImplicit shape. Getting even one wrong
 *       reverts C27's blocker to still-broken.</li>
 *   <li>Failures are absorbed silently and retried on schedule; a
 *       transient Stalwart 5xx must never crash Aurora's own boot.</li>
 * </ol>
 */
class StalwartRegistrySeedServiceTests {

  private static StalwartProvisionService provisionWithDomain(String domain) {
    var p = Mockito.mock(StalwartProvisionService.class);
    when(p.mailDomain()).thenReturn(domain);
    return p;
  }

  private static StalwartMailClient reachableClientWithDomain() {
    var m = Mockito.mock(StalwartMailClient.class);
    when(m.reachable()).thenReturn(true);
    when(m.domainExists("aurora.local")).thenReturn(true);
    return m;
  }

  @Test
  void writes_hostname_and_domain_for_the_boxs_own_facts() {
    // mail.<domain> is the hostname convention every other Aurora
    // service already uses; asserting the exact value is what stops a
    // future rebrand from silently drifting.
    var mail = reachableClientWithDomain();
    var svc = new StalwartRegistrySeedService(provisionWithDomain("aurora.local"), mail);

    svc.seedQuietly();

    verify(mail).ensureSystemSettings("mail.aurora.local", "aurora.local");
  }

  @Test
  void creates_all_six_listeners_at_the_ports_compose_publishes() {
    // The set is deliberately compared verbatim: any drift here means a
    // port advertised by the compose file and the dashboard is silently
    // unlistened-on, which is exactly what C27 called out.
    var mail = reachableClientWithDomain();
    var svc = new StalwartRegistrySeedService(provisionWithDomain("aurora.local"), mail);

    svc.seedQuietly();

    verify(mail).ensureNetworkListener("smtp",        "smtp",        "[::]:25",   false);
    verify(mail).ensureNetworkListener("submission",  "smtp",        "[::]:587",  false);
    verify(mail).ensureNetworkListener("submissions", "smtp",        "[::]:465",  true);
    verify(mail).ensureNetworkListener("imap",        "imap",        "[::]:143",  false);
    verify(mail).ensureNetworkListener("imaps",       "imap",        "[::]:993",  true);
    verify(mail).ensureNetworkListener("sieve",       "manageSieve", "[::]:4190", false);
  }

  @Test
  void writes_a_console_tracer_so_docker_logs_stops_being_empty() {
    // Not writing this leaves `docker logs stalwart` completely empty
    // on a fresh box (verified live) — the operator has no telemetry
    // and no way to see the mail server misbehaving.
    var mail = reachableClientWithDomain();
    var svc = new StalwartRegistrySeedService(provisionWithDomain("aurora.local"), mail);

    svc.seedQuietly();

    verify(mail).ensureConsoleTracer();
  }

  @Test
  void does_nothing_when_jmap_is_not_reachable_yet() {
    // Startup race: Aurora and Stalwart boot in parallel and Aurora
    // wins about half the time. Trying to seed against a not-yet-up
    // server would spam the log and (worse) throw uncaught from the
    // virtual thread; the reachable() gate lets the schedule retry.
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(false);
    var svc = new StalwartRegistrySeedService(provisionWithDomain("aurora.local"), mail);

    svc.seedQuietly();

    verify(mail, never()).ensureSystemSettings(Mockito.anyString(), Mockito.anyString());
    verify(mail, never()).ensureNetworkListener(
        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    verify(mail, never()).ensureConsoleTracer();
  }

  @Test
  void waits_for_the_domain_to_exist_before_touching_system_settings() {
    // SystemSettings.defaultDomain is Id<Domain>; writing it before the
    // domain is provisioned would return an invalid-reference error and
    // (in isolation) be indistinguishable from a real failure. Cheaper
    // to just wait.
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(true);
    when(mail.domainExists("aurora.local")).thenReturn(false);
    var svc = new StalwartRegistrySeedService(provisionWithDomain("aurora.local"), mail);

    svc.seedQuietly();

    verify(mail, never()).ensureSystemSettings(Mockito.anyString(), Mockito.anyString());
    verify(mail, never()).ensureNetworkListener(
        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
  }

  @Test
  void never_throws_when_a_downstream_call_fails() {
    // Best-effort: a transient 5xx or a slow-to-load permission set on
    // Stalwart's side must not crash the scheduler thread.
    var mail = reachableClientWithDomain();
    when(mail.ensureSystemSettings(Mockito.anyString(), Mockito.anyString()))
        .thenThrow(new StalwartMailClient.StalwartApiException("boom"));
    var svc = new StalwartRegistrySeedService(provisionWithDomain("aurora.local"), mail);

    // No exception; the next scheduled tick tries again.
    svc.seedQuietly();

    verify(mail, times(1)).ensureSystemSettings(Mockito.anyString(), Mockito.anyString());
  }
}
