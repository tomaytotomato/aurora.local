package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.MailboxSummary;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.events.UserChangedEvent;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every person with an Aurora account has a mailbox on this box.
 *
 * <p><b>The gap this closes.</b> Adding a user from the Users page created
 * a mailbox — there is a comment in {@code UsersController} calling it "the
 * story": one credential for signing in and for mail. But the first admin,
 * created by the onboarding wizard, went through a different code path
 * ({@code OnboardingService.createInitialAdmin} → {@code AdminUserRepo}
 * directly) and got none. So the one account guaranteed to exist on every
 * box — the owner's — was the only account without mail, and a fresh box
 * showed an empty mailbox list. Exactly backwards.
 *
 * <p><b>Why a reconcile rather than one more call at creation.</b> Two
 * reasons. The mail domain is provisioned asynchronously
 * ({@link StalwartProvisionService}) and on a fresh box may not exist for
 * a minute after the wizard's admin step, so a single attempt at creation
 * is a race. And boxes that already exist — installed before this — have
 * an owner with no mailbox, which only a reconcile can heal.
 *
 * <p><b>How it heals an account whose password we do not know.</b> Aurora
 * stores bcrypt hashes, never plaintext. Stalwart accepts a bcrypt hash as
 * the credential secret and verifies the plaintext against it (proven on
 * v0.16.19: create with {@code $2a$12$…}, authenticate with the original
 * plaintext → 200; with the hash → 401). So the hash is copied across, and
 * "one password for your box and your mail" stays true for accounts healed
 * long after they were created.
 */
@Service
public class MailAccountReconciler {

  private static final Logger log = LoggerFactory.getLogger(MailAccountReconciler.class);

  static final String DEFAULT_DOMAIN = "aurora.local";

  /**
   * Addresses the box itself answers to, pointed at the owner's mailbox.
   *
   * <p>{@code admin@} is what a person writes to when they want whoever
   * runs this thing. {@code system@} is what Aurora itself uses for
   * diagnostics, alerts and anything it generates — so those land in a real
   * inbox the owner already reads, rather than in
   * {@code data/authelia/notification.txt} where they have been going.
   *
   * <p>Aliases rather than separate mailboxes on purpose: a second mailbox
   * means a second password to manage and an inbox nobody opens, and it
   * would break "one password for your box and your mail".
   */
  static final List<String> OWNER_ALIASES = List.of("admin", "system");

  private final AdminUserRepo users;
  private final StateFileService stateFiles;
  private final StalwartMailClient mail;
  private final AuditEventRepo audit;

  public MailAccountReconciler(AdminUserRepo users, StateFileService stateFiles,
                               StalwartMailClient mail, AuditEventRepo audit) {
    this.users = users;
    this.stateFiles = stateFiles;
    this.mail = mail;
    this.audit = audit;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    // Off the event thread: Stalwart may still be starting, and a slow or
    // unreachable mail server must never delay or fail application boot.
    Thread.ofVirtual().name("mail-reconcile-startup").start(() -> reconcileQuietly("startup"));
  }

  /**
   * Drift guard, and the second chance for anything the event path missed
   * because the mail domain was not provisioned yet.
   */
  @Scheduled(fixedDelay = 5 * 60_000L, initialDelay = 90_000L)
  public void scheduled() {
    reconcileQuietly("schedule");
  }

  @EventListener(UserChangedEvent.class)
  public void onUserChanged(UserChangedEvent ev) {
    // A new user should have mail immediately, not in five minutes.
    Thread.ofVirtual().name("mail-reconcile-user").start(() -> reconcileQuietly(ev.reason()));
  }

  /**
   * Idempotent: give every Aurora user a mailbox they do not already have.
   * Returns how many were created. Never throws — mail being unreachable
   * is a normal transient state on a booting box, not an error worth
   * failing anything else for.
   */
  public synchronized int reconcile(String reason) {
    List<AdminUser> all = users.findAll();
    if (all.isEmpty()) return 0;

    String domain = domain();
    if (!mail.ensureDomain(domain)) {
      log.debug("mail reconcile: domain {} not ready yet (reason={})", domain, reason);
      return 0;
    }

    List<MailboxSummary> existing = mail.listMailboxes();
    int created = 0;
    for (AdminUser u : all) {
      String address = addressFor(u, domain);
      if (address == null) continue;
      if (hasMailbox(existing, address)) continue;
      try {
        // The bcrypt hash, not a plaintext password: see the class javadoc.
        mail.createMailbox(localPartFor(u), domain, u.passwordHash());
        audit.record(u.id(), "mail.mailbox.provisioned", address, null);
        log.info("mail reconcile: created {} for aurora user {} (reason={})",
            address, u.username(), reason);
        created++;
      } catch (Exception e) {
        // One failure must not stop the others: a single malformed username
        // should not deny everyone else their mail.
        log.warn("mail reconcile: could not create {}: {}", address, e.toString());
      }
    }

    // Re-read only if we changed anything; otherwise reuse the list.
    ensureOwnerAliases(created > 0 ? mail.listMailboxes() : existing, all, domain);
    return created;
  }

  /**
   * Point {@code admin@} and {@code system@} at the owner's mailbox.
   *
   * <p>The owner is the first account created on the box — the one the
   * wizard made. Aliases are only ever written when they are actually
   * wrong, because {@link StalwartMailClient#setAliases} replaces the whole
   * map and this runs every five minutes.
   */
  void ensureOwnerAliases(List<MailboxSummary> existing, List<AdminUser> all, String domain) {
    AdminUser owner = all.stream()
        .filter(u -> Role.ADMIN.equals(u.role()))
        .findFirst()
        .orElse(all.isEmpty() ? null : all.get(0));
    if (owner == null) return;

    String ownerLocal = localPartFor(owner);
    if (ownerLocal == null) return;
    var mailbox = find(existing, ownerLocal + "@" + domain);
    if (mailbox.isEmpty()) return;

    // An owner literally called "admin" already answers at admin@; adding
    // it as an alias of itself is a collision, not a courtesy.
    List<String> wanted = OWNER_ALIASES.stream()
        .filter(a -> !a.equalsIgnoreCase(ownerLocal))
        .toList();
    if (wanted.isEmpty()) return;

    try {
      List<String> current = mail.aliasesOf(mailbox.get().id());
      if (current.containsAll(wanted) && wanted.containsAll(current)) return;

      mail.setAliases(mailbox.get().id(), wanted, domain);
      audit.record(owner.id(), "mail.aliases.set", String.join(",", wanted), null);
      log.info("mail reconcile: {} now also answer at {}", ownerLocal + "@" + domain,
          wanted.stream().map(a -> a + "@" + domain).toList());
    } catch (Exception e) {
      // A collision (someone already owns admin@ as their own address) or a
      // mail server hiccup. Neither is worth failing the whole reconcile.
      log.warn("mail reconcile: could not set aliases on {}: {}", ownerLocal, e.toString());
    }
  }

  private void reconcileQuietly(String reason) {
    try {
      reconcile(reason);
    } catch (Exception e) {
      log.debug("mail reconcile ({}) skipped: {}", reason, e.toString());
    }
  }

  String domain() {
    try {
      String d = stateFiles.readState().domain();
      return d == null || d.isBlank() ? DEFAULT_DOMAIN : d;
    } catch (Exception e) {
      return DEFAULT_DOMAIN;
    }
  }

  /**
   * Mail local parts are narrower than Aurora usernames. Anything that
   * cannot be one is skipped rather than mangled into a different person's
   * address.
   */
  static String localPartFor(AdminUser u) {
    if (u == null || u.username() == null) return null;
    String name = u.username().trim().toLowerCase(Locale.ROOT);
    return name.matches("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?") ? name : null;
  }

  static String addressFor(AdminUser u, String domain) {
    String local = localPartFor(u);
    return local == null ? null : local + "@" + domain;
  }

  private static boolean hasMailbox(List<MailboxSummary> existing, String address) {
    return existing.stream().anyMatch(m -> address.equalsIgnoreCase(m.address()));
  }

  /** The mailbox for a given address, if it exists. */
  Optional<MailboxSummary> find(List<MailboxSummary> existing, String address) {
    return existing.stream().filter(m -> address.equalsIgnoreCase(m.address())).findFirst();
  }
}
