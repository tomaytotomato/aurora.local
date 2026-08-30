package com.tomaytotomato.aurora.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

  public StalwartRegistrySeedService(StalwartProvisionService provision, StalwartMailClient mail) {
    this.provision = provision;
    this.mail = mail;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    Thread.ofVirtual().name("stalwart-registry-seed-startup").start(this::seedQuietly);
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
   * Run the full seed. Package-private so tests can drive it directly.
   * Never throws — logs and returns instead so the scheduler keeps ticking.
   */
  void seedQuietly() {
    String domain = provision.mailDomain();
    String hostname = "mail." + domain;
    try {
      if (!mail.reachable()) {
        log.debug("stalwart registry seed: JMAP not reachable yet, will retry");
        return;
      }
      // The domain has to exist before SystemSettings can reference it
      // (defaultDomain is Id<Domain>). StalwartProvisionService already
      // ensures the domain on its own schedule; do it here too so a boot
      // where this service wins the race still lands a working seed.
      if (!mail.domainExists(domain)) {
        log.debug("stalwart registry seed: domain {} does not exist yet, will retry", domain);
        return;
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
        log.info("stalwart registry seed: console tracer created; "
            + "`docker logs stalwart` will now carry INFO-level events");
      }
    } catch (Exception e) {
      // Everything below reachable() is best-effort. A slow Stalwart
      // that has not yet loaded permissions can return a transient
      // error; we come back on the next tick.
      log.debug("stalwart registry seed: seed pass failed, will retry: {}", e.getMessage());
    }
  }

  private void seedListener(String name, String protocol, String bind, boolean tlsImplicit) {
    boolean created = mail.ensureNetworkListener(name, protocol, bind, tlsImplicit);
    if (created) {
      log.info("stalwart registry seed: created listener {} ({} on {}, tlsImplicit={})",
          name, protocol, bind, tlsImplicit);
    }
  }

  /** The six listener names Aurora manages. Used by tests. */
  static final List<String> MANAGED_LISTENER_NAMES = List.of(
      "smtp", "submission", "submissions", "imap", "imaps", "sieve");

  /** Only for tests: the set for {@link #MANAGED_LISTENER_NAMES}. */
  static final Set<String> MANAGED_LISTENER_NAMES_SET = Set.copyOf(MANAGED_LISTENER_NAMES);
}
