package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.RepoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Ensures the box's mail domain exists in Stalwart, so a fresh install has
 * a working mail substrate with no operator setup — the last mile of
 * "no wizard on a fresh box" (docs/CORE_SHARED_SERVICES_PLAN.md).
 *
 * <p>Stalwart boots configured (its datastore is seeded to point at
 * core-db, so no setup wizard) but its mail database starts empty: no
 * domain, so no SMTP/IMAP account can exist yet. The domain is not
 * per-operator data — it is exactly the box's own {@code domain} from
 * {@code .state.yml} ({@code aurora.local}). So Aurora can and should
 * create it automatically; only the first human mailbox needs a decision
 * (a password), which is a separate explicit action.
 *
 * <p><b>Boot + reconcile.</b> On {@link ApplicationReadyEvent} and on a
 * slow schedule, ensure the domain exists. Fire-and-forget and
 * fail-closed: the mail domain not being provisioned yet (Stalwart still
 * starting, network blip) must never crash the dashboard or block boot —
 * it just tries again on the next tick. Idempotent:
 * {@link StalwartMailClient#ensureDomain} treats "already exists" as
 * success.
 *
 * <p><b>Why not create a mailbox too?</b> A mailbox needs a password, and
 * inventing one silently is worse than asking: the operator can't receive
 * mail at an address whose password they were never shown. Domain
 * provisioning is safe to automate; mailbox creation is a one-click
 * dashboard action that returns the generated password once (see
 * {@code StalwartController}).
 */
@Service
public class StalwartProvisionService {

  private static final Logger log = LoggerFactory.getLogger(StalwartProvisionService.class);

  static final String DEFAULT_DOMAIN = "aurora.local";

  private final StateFileService stateFiles;
  private final StalwartMailClient mail;

  public StalwartProvisionService(StateFileService stateFiles, StalwartMailClient mail) {
    this.stateFiles = stateFiles;
    this.mail = mail;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    // Give Stalwart a moment after boot; do the actual work off the event
    // thread so a slow/unreachable Stalwart never delays app startup.
    Thread.ofVirtual().name("stalwart-provision-startup").start(this::ensureDomainQuietly);
  }

  @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
  public void reconcile() {
    ensureDomainQuietly();
  }

  /**
   * Ensure the box's mail domain exists. Never throws — a failure is
   * logged and retried on the next tick. Package-private for tests.
   */
  void ensureDomainQuietly() {
    String domain = mailDomain();
    try {
      if (!mail.reachable()) {
        log.debug("stalwart provision: JMAP not reachable yet, will retry");
        return;
      }
      // ensureDomain now answers "does the domain exist", so work out
      // whether this call is what created it before asking.
      boolean existedBefore = mail.domainExists(domain);
      boolean created = mail.ensureDomain(domain) && !existedBefore;
      if (created) {
        log.info("stalwart provision: mail domain {} is now configured", domain);
      }
    } catch (Exception e) {
      log.debug("stalwart provision: could not ensure domain {} yet: {}", domain, e.getMessage());
    }
  }

  /** The box's own domain from .state.yml, or the default. */
  public String mailDomain() {
    RepoState state = stateFiles.readState();
    String d = state == null ? null : state.domain();
    return d == null || d.isBlank() ? DEFAULT_DOMAIN : d;
  }
}
