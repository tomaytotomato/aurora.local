package com.tomaytotomato.aurora.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Aurora's Stalwart wizard, in code (C27).
 *
 * <p>The problem this closes was recorded on the journey worksheet as
 * C27: on a fresh box, SMTP :25 accepts <code>MAIL FROM/RCPT TO/DATA</code>
 * with a 250 all the way through, and then the message is discarded —
 * the row count in Stalwart's Postgres store does not move, IMAP folders
 * stay empty, ports :143 and :587 refuse connections, and
 * <code>docker logs stalwart</code> is completely empty.
 *
 * <p><b>Why.</b> Stalwart v0.16 stores its configuration inside the
 * datastore, not on disk: {@code config.json} carries only the datastore
 * pointer, and the setup wizard is what writes every other object
 * (hostname, listeners, delivery, tracers) into the registry as JMAP
 * objects. Aurora seeds {@code config.json} on first boot (see
 * {@code render_stalwart_config}) so no wizard ever runs, which is
 * exactly the "no terminal, no wizard" story Aurora wants — but that
 * means Aurora inherits the wizard's job of writing the initial
 * registry objects, too.
 *
 * <p>This service is the wizard-equivalent. On {@link ApplicationReadyEvent}
 * and on a slow schedule (drift reconciliation), for the box's own
 * {@code $DOMAIN}:
 *
 * <ol>
 *   <li>{@link StalwartMailClient#reachable()} — wait until JMAP answers.
 *       Stalwart's own boot is asynchronous with Aurora's, and we must
 *       never crash-loop the dashboard because Stalwart is still coming
 *       up.</li>
 *   <li>Seed the {@link StalwartMailClient#ensureSystemSettings default hostname and domain}
 *       ({@code mail.$DOMAIN}, {@code $DOMAIN}) so outbound greetings and
 *       reports use the box's identity instead of Stalwart's baked-in
 *       defaults.</li>
 *   <li>Seed six {@link StalwartMailClient#ensureNetworkListener listeners}
 *       matching the ports {@code packages/core/compose.yml} publishes:
 *       SMTP :25, IMAP-STARTTLS :143, submission-SSL :465,
 *       submission-STARTTLS :587, IMAPS :993, ManageSieve :4190. Before
 *       this, only :25/:993/:4190 answered; the two submission ports and
 *       plain-IMAP were advertised in the dashboard's "connect a mail
 *       client" card but refused connections.</li>
 *   <li>Seed a Console {@link StalwartMailClient#ensureConsoleTracer tracer}
 *       so the empty {@code docker logs stalwart} problem stops being a
 *       silent failure surface.</li>
 * </ol>
 *
 * <p><b>Idempotency.</b> Each step first checks whether the object is
 * already correct and skips the write when it is. A rebuild that changes
 * a listener's bind address re-applies cleanly; a boot with everything
 * already right is silent. Modelled on
 * {@link StalwartProvisionService#ensureDomainQuietly()}.
 *
 * <p><b>Failure semantics.</b> Fail-closed: a JMAP call that fails is
 * logged at DEBUG (INFO on transitions) and retried on the next tick.
 * Never fatal to Aurora's own boot. Same shape as
 * {@link StalwartProvisionService}.
 */
@Service
public class StalwartRegistrySeedService {

  private static final Logger log = LoggerFactory.getLogger(StalwartRegistrySeedService.class);

  private final StalwartProvisionService provision;
  private final StalwartMailClient mail;
  /**
   * Test seam. The real sleeper delegates to {@link Thread#sleep(long)};
   * tests hand in a fake so the cold-boot retry loop can be driven at
   * whatever pace they want without wall-clock waits. Same shape as
   * {@code AutheliaMailProvisionService}'s {@code SubmissionProbe}.
   */
  private final Sleeper sleeper;

  public StalwartRegistrySeedService(StalwartProvisionService provision, StalwartMailClient mail) {
    this(provision, mail, defaultSleeper());
  }

  /** Package-private test seam: hand in a fake sleeper. */
  StalwartRegistrySeedService(StalwartProvisionService provision, StalwartMailClient mail,
                              Sleeper sleeper) {
    this.provision = provision;
    this.mail = mail;
    this.sleeper = sleeper;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    Thread.ofVirtual().name("stalwart-registry-seed-startup").start(this::seedUntilReady);
  }

  /**
   * Slower than {@link StalwartProvisionService}'s 10-minute domain
   * reconcile: the registry seed does not change once it is right, so
   * the reconcile is a drift-guard, not a load-bearing loop.
   */
  @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT5M")
  public void reconcile() {
    seedQuietly();
  }

  /**
   * Run the full seed once. Package-private so tests can drive it directly.
   * Never throws — logs and returns a {@link SeedResult} instead so both
   * the cold-boot retry loop and the scheduled reconcile keep ticking.
   */
  SeedResult seedQuietly() {
    String domain = provision.mailDomain();
    String hostname = "mail." + domain;
    try {
      if (!mail.reachable()) {
        log.debug("stalwart registry seed: JMAP not reachable yet, will retry");
        return SeedResult.NOT_READY;
      }
      // The domain has to exist before SystemSettings can reference it
      // (defaultDomain is Id<Domain>). StalwartProvisionService already
      // ensures the domain on its own schedule; do it here too so a boot
      // where this service wins the race still lands a working seed.
      if (!mail.domainExists(domain)) {
        log.debug("stalwart registry seed: domain {} does not exist yet, will retry", domain);
        return SeedResult.NOT_READY;
      }

      boolean settingsChanged = mail.ensureSystemSettings(hostname, domain);
      if (settingsChanged) {
        log.info("stalwart registry seed: system settings now defaultHostname={}, defaultDomain={}",
            hostname, domain);
      }

      // The six listeners we advertise. Ports come from
      // packages/core/compose.yml; changing them there without changing
      // them here would silently open a port with no listener behind it,
      // which is what C27 was.
      //
      // Names match Stalwart v0.16's own default names verified live:
      // smtp, submission, submissions, imap, imaps, sieve. Aurora keys
      // idempotency off the name, so using different names here would
      // duplicate the wizard's defaults and cause port fights.
      //
      // Bind uses [::]:port because Stalwart's own defaults do (and the
      // idempotency check compares against what the wizard writes).
      // [::] on a dual-stack container binds both v4 and v6.
      seedListener("smtp",        "smtp",        "[::]:25",   false);
      seedListener("submission",  "smtp",        "[::]:587",  false);
      seedListener("submissions", "smtp",        "[::]:465",  true);
      seedListener("imap",        "imap",        "[::]:143",  false);
      seedListener("imaps",       "imap",        "[::]:993",  true);
      seedListener("sieve",       "manageSieve", "[::]:4190", false);

      boolean tracerCreated = mail.ensureConsoleTracer();
      if (tracerCreated) {
        log.info("stalwart registry seed: stdout tracer created; "
            + "`docker logs stalwart` will now carry INFO-level events");
      }
      return SeedResult.SUCCESS;
    } catch (Exception e) {
      // Everything below reachable() is best-effort. A slow Stalwart
      // that has not yet loaded permissions can return a transient
      // error; we come back on the next tick.
      log.debug("stalwart registry seed: seed pass failed, will retry: {}", e.getMessage());
      return SeedResult.PARTIAL_FAILURE;
    }
  }

  /**
   * Cold-boot fast-retry cadence. The steady-state {@link #reconcile()}
   * runs every 30 minutes with a 5-minute initial delay, which on a
   * fresh box is far too long a wait: Stalwart's own boot (postgres
   * schema, permission table, listener bind) settles in ~90 s, so the
   * first useful seed pass lands in the first two minutes. Before this
   * loop, the startup thread would try once, hit {@code NOT_READY}, and
   * hand the box back to a 5-minute silence. Every consumer that gates
   * on submission :587 being bound (Authelia, see item 1) then waited
   * that long too.
   *
   * <p>Retry every {@link #COLD_BOOT_RETRY_INTERVAL} for at most
   * {@link #COLD_BOOT_MAX_ATTEMPTS} passes, stop as soon as one pass
   * returns {@link SeedResult#SUCCESS}. If the budget is exhausted the
   * steady-state reconcile still picks it up on its own schedule — this
   * loop is a nice-to-have that shortens cold boot, not the correctness
   * path.
   *
   * <p>Package-private so tests can drive it with a fake
   * {@link Sleeper}.
   */
  void seedUntilReady() {
    for (int attempt = 1; attempt <= COLD_BOOT_MAX_ATTEMPTS; attempt++) {
      SeedResult result = seedQuietly();
      if (result == SeedResult.SUCCESS) {
        // A successful pass is idempotent — the reconcile can still run
        // later to catch drift. No log line on success: the individual
        // ensure* helpers already log on transitions.
        return;
      }
      if (attempt == COLD_BOOT_MAX_ATTEMPTS) {
        // Do not log INFO per attempt (spam). One WARN on give-up, and
        // point the operator at the next thing that will happen.
        log.warn("stalwart registry seed: cold-boot fast-retry gave up after {} attempts "
            + "({}); steady-state reconcile will pick it up in ~5 min",
            COLD_BOOT_MAX_ATTEMPTS,
            Duration.ofMillis(COLD_BOOT_RETRY_INTERVAL.toMillis() * COLD_BOOT_MAX_ATTEMPTS));
        return;
      }
      try {
        sleeper.sleep(COLD_BOOT_RETRY_INTERVAL);
      } catch (InterruptedException e) {
        // Restore the interrupt flag so a shutdown hook that joined the
        // virtual thread sees it, then exit the loop cleanly.
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void seedListener(String name, String protocol, String bind, boolean tlsImplicit) {
    boolean created = mail.ensureNetworkListener(name, protocol, bind, tlsImplicit);
    if (created) {
      log.info("stalwart registry seed: created listener {} ({} on {}, tlsImplicit={})",
          name, protocol, bind, tlsImplicit);
    }
  }

  /**
   * Outcome of a single {@link #seedQuietly()} pass. Drives the
   * cold-boot retry loop: keep retrying on anything other than
   * {@link #SUCCESS}. The steady-state reconcile ignores the value.
   */
  enum SeedResult {
    /** Preconditions not met yet (JMAP not up, or domain not present). */
    NOT_READY,
    /** A JMAP write or read threw. Transient; try again. */
    PARTIAL_FAILURE,
    /** All six listeners, settings and tracer applied without incident. */
    SUCCESS
  }

  /**
   * Test seam. See {@link #sleeper}.
   */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  /** Real sleeper: delegates to {@link Thread#sleep(long)}. */
  static Sleeper defaultSleeper() {
    return duration -> Thread.sleep(duration.toMillis());
  }

  /**
   * Cold-boot retry cadence: every 30 s. Stalwart's cold boot settles
   * within ~90 s, so anything less would race the schema/permission
   * load; anything more starves the consumer (Authelia) for no reason.
   */
  static final Duration COLD_BOOT_RETRY_INTERVAL = Duration.ofSeconds(30);

  /**
   * Maximum number of cold-boot passes before giving up and letting the
   * steady-state {@link #reconcile()} take over. 20 attempts * 30 s
   * gives a 10-minute cold-boot budget — long enough to survive a slow
   * Postgres schema apply, short enough that the steady-state 5-minute
   * initialDelay lands soon after.
   */
  static final int COLD_BOOT_MAX_ATTEMPTS = 20;

  /** The six listener names Aurora manages. Used by tests. */
  static final List<String> MANAGED_LISTENER_NAMES = List.of(
      "smtp", "submission", "submissions", "imap", "imaps", "sieve");

  /** Only for tests: the set for {@link #MANAGED_LISTENER_NAMES}. */
  static final Set<String> MANAGED_LISTENER_NAMES_SET = Set.copyOf(MANAGED_LISTENER_NAMES);
}
