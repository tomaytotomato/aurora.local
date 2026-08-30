package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

  // ------------------------------------------------------------------
  // Cold-boot fast-retry (item 1b). What matters to pin:
  //   1. When Stalwart becomes reachable partway through the retry
  //      budget, the loop stops as soon as one pass succeeds. Otherwise
  //      the loop would keep retrying a healthy seed for no reason.
  //   2. When Stalwart never becomes reachable, the loop exits after
  //      the budget with no exception. A stuck loop would never let the
  //      steady-state reconcile take over.
  //   3. The steady-state @Scheduled reconcile() calls the seed exactly
  //      once per tick. Any accidental retry from that path would burn
  //      JMAP calls every 30 minutes for no reason.
  //   4. The sleep between attempts uses the injected Sleeper, not
  //      Thread.sleep, so tests run in milliseconds not minutes.
  // ------------------------------------------------------------------

  private static final class RecordingSleeper implements StalwartRegistrySeedService.Sleeper {
    final List<Duration> sleeps = new ArrayList<>();
    @Override
    public void sleep(Duration duration) {
      sleeps.add(duration);
    }
  }

  @Test
  void cold_boot_retry_stops_as_soon_as_one_pass_succeeds() {
    // Stalwart's cold-boot window: reachable() returns false for the
    // first 3 attempts, then true. The loop should call seedQuietly()
    // 4 times, sleep 3 times between them, then return once it sees
    // SUCCESS. It must NOT keep retrying a healthy seed.
    var mail = Mockito.mock(StalwartMailClient.class);
    AtomicInteger reachableCalls = new AtomicInteger();
    when(mail.reachable()).thenAnswer(inv -> reachableCalls.incrementAndGet() > 3);
    when(mail.domainExists("aurora.local")).thenReturn(true);
    var sleeper = new RecordingSleeper();
    var svc = new StalwartRegistrySeedService(
        provisionWithDomain("aurora.local"), mail, sleeper);

    svc.seedUntilReady();

    // 4 seedQuietly passes: 3 NOT_READY + 1 SUCCESS.
    assertEquals(4, reachableCalls.get(),
        "loop must stop as soon as SUCCESS is observed");
    // 3 sleeps between the 4 attempts. Never after the successful one.
    assertEquals(3, sleeper.sleeps.size());
    assertTrue(sleeper.sleeps.stream().allMatch(d ->
        d.equals(StalwartRegistrySeedService.COLD_BOOT_RETRY_INTERVAL)));
    // The successful pass ran the full seed exactly once.
    verify(mail, times(1))
        .ensureSystemSettings("mail.aurora.local", "aurora.local");
    verify(mail, times(1)).ensureConsoleTracer();
  }

  @Test
  void cold_boot_retry_gives_up_after_the_budget_without_throwing() {
    // Pathological case: Stalwart never comes up. The loop must exhaust
    // its budget (20 attempts) without throwing so the shutdown hook
    // can join the virtual thread and the steady-state reconcile can
    // take over.
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(false);
    var sleeper = new RecordingSleeper();
    var svc = new StalwartRegistrySeedService(
        provisionWithDomain("aurora.local"), mail, sleeper);

    svc.seedUntilReady();

    verify(mail, times(StalwartRegistrySeedService.COLD_BOOT_MAX_ATTEMPTS)).reachable();
    // One sleep BETWEEN each pair of attempts — no sleep after the
    // final (give-up) attempt, otherwise a shutdown-in-progress would
    // block on a pointless wait.
    assertEquals(
        StalwartRegistrySeedService.COLD_BOOT_MAX_ATTEMPTS - 1,
        sleeper.sleeps.size());
    verify(mail, never()).ensureSystemSettings(Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void cold_boot_retry_exits_cleanly_when_interrupted() {
    // Shutdown-in-flight: the sleep raises InterruptedException. The
    // loop must restore the interrupt flag and exit so the container's
    // graceful-shutdown hook can join the virtual thread.
    var mail = Mockito.mock(StalwartMailClient.class);
    when(mail.reachable()).thenReturn(false);
    StalwartRegistrySeedService.Sleeper interrupting = d -> {
      throw new InterruptedException("shutdown");
    };
    var svc = new StalwartRegistrySeedService(
        provisionWithDomain("aurora.local"), mail, interrupting);

    // Clear any lingering interrupt flag from earlier tests.
    assertFalse(Thread.interrupted());
    svc.seedUntilReady();
    boolean interrupted = Thread.interrupted();

    // Exactly one attempt, one sleep that threw, no further work.
    verify(mail, times(1)).reachable();
    assertTrue(interrupted, "interrupt flag must be re-raised so shutdown sees it");
  }

  @Test
  void scheduled_reconcile_runs_the_seed_exactly_once_per_tick() {
    // The @Scheduled path must not accidentally kick the cold-boot
    // retry loop — every 30 min it would burn 20 pointless JMAP calls
    // on a healthy box.
    var mail = reachableClientWithDomain();
    var svc = new StalwartRegistrySeedService(provisionWithDomain("aurora.local"), mail);

    svc.reconcile();

    verify(mail, times(1)).reachable();
    verify(mail, times(1))
        .ensureSystemSettings("mail.aurora.local", "aurora.local");
  }
}
