package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aurora's own alerts go to a real inbox, not a text file (C26).
 *
 * <p>Authelia sends every password-reset link, 2FA-enrolment code and
 * account-locked notice via a "notifier". Out of the box it uses a
 * filesystem notifier that appends to
 * {@code data/authelia/notification.txt}, which is exactly the kind of
 * terminal affordance ESSENCE calls out as a defect: the operator would
 * have to SSH in and cat a file every time a household member forgets
 * their password. Aurora already runs a full mail server on this box
 * (Stalwart, in the {@code core} package), and the owner already has a
 * mailbox, so a working local mail path is right there.
 *
 * <p>This service closes that loop. On boot (and on a slow schedule for
 * drift), for the box's own {@code $DOMAIN}:
 *
 * <ol>
 *   <li>If {@code packages/core/.env} already carries a working set of
 *       {@code AUTHELIA_NOTIFIER_SMTP_*} entries, do nothing. Idempotent.
 *   </li>
 *   <li>Otherwise generate a strong SMTP password, ensure an
 *       {@code authelia@$DOMAIN} mailbox exists (create if missing, reset
 *       to the generated password so the .env and the server agree), and
 *       write the five {@code AUTHELIA_NOTIFIER_SMTP_*} keys into
 *       {@code packages/core/.env}.</li>
 *   <li>Record an audit row keyed {@code authelia.mail.provision} with
 *       the sender address (never the password) so a rebuild that
 *       provisions is visible in the log.</li>
 * </ol>
 *
 * <p>The Authelia container has to be recreated by the next
 * {@code up.sh} to pick up the new env; nothing in this service pokes
 * docker or restarts anything. Same contract every other .env writer
 * follows (see {@link IdentitySecretsService#ensureSecrets()}).
 *
 * <p><b>Why a dedicated mailbox instead of the owner's own credentials.
 * </b> The owner's password rotates, and Authelia would break every time
 * they used the "Change password" screen. {@code authelia@$DOMAIN} is a
 * service account: its password lives in {@code packages/core/.env},
 * next to every other secret Aurora owns, and rotates with a deliberate
 * rebuild rather than as a side effect of a human sign-in change.
 *
 * <p><b>Where the mail lands.</b> The five env keys set
 * {@code AUTHELIA_NOTIFIER_SMTP_SENDER=authelia@$DOMAIN}; the
 * corresponding {@code MailAccountReconciler} already routes
 * {@code system@$DOMAIN} to the owner's inbox as an alias. Authelia's
 * templates address delivery to whichever user is resetting, so
 * "Aurora's own alerts to system@" (the C26 phrasing) is a special case
 * of this: the alerts arrive by sending from {@code authelia@} to
 * {@code system@}, which the alias delivers to the owner. No routing
 * gymnastics.
 */
@Service
public class AutheliaMailProvisionService {

  private static final Logger log = LoggerFactory.getLogger(AutheliaMailProvisionService.class);

  /** The mailbox local-part Authelia sends as. Also its SMTP AUTH username. */
  static final String AUTHELIA_LOCAL_PART = "authelia";

  /** Keys we own in {@code packages/core/.env}. Order stable for audit JSON. */
  static final List<String> MANAGED_KEYS = List.of(
      "AUTHELIA_NOTIFIER_SMTP_HOST",
      "AUTHELIA_NOTIFIER_SMTP_PORT",
      "AUTHELIA_NOTIFIER_SMTP_USERNAME",
      "AUTHELIA_NOTIFIER_SMTP_PASSWORD",
      "AUTHELIA_NOTIFIER_SMTP_SENDER"
  );

  /**
   * SMTP host on aurora_net. Compose exposes stalwart on 587 with
   * submission STARTTLS; the container name resolves inside the docker
   * bridge, which is where Authelia lives too.
   */
  static final String STALWART_HOST = "stalwart";
  static final String STALWART_SUBMISSION_PORT = "587";

  private static final Pattern KEY_LINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");

  private final StalwartProvisionService provision;
  private final StalwartMailClient mail;
  private final AuroraProperties props;
  private final AuditEventRepo audit;
  private final SecureRandom rng;

  public AutheliaMailProvisionService(StalwartProvisionService provision,
                                      StalwartMailClient mail,
                                      AuroraProperties props,
                                      AuditEventRepo audit) {
    this.provision = provision;
    this.mail = mail;
    this.props = props;
    this.audit = audit;
    this.rng = new SecureRandom();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    Thread.ofVirtual().name("authelia-mail-provision-startup").start(this::provisionQuietly);
  }

  @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT7M")
  public void reconcile() {
    provisionQuietly();
  }

  /**
   * Do the provision. Package-private so tests can drive it directly.
   * Never throws \u2014 same fail-closed shape as everything else that
   * touches Stalwart from Aurora's own boot.
   */
  void provisionQuietly() {
    try {
      Path envPath = envPath();
      if (!Files.isRegularFile(envPath)) {
        // scripts/up.sh creates packages/core/.env from .env.example before
        // the dashboard container starts, so a real box always has it. In
        // an integration-test fixture that skipped that, we deliberately
        // do nothing rather than manufacture one from thin air.
        log.debug("authelia mail provision: packages/core/.env not present yet, skipping");
        return;
      }
      List<String> lines = new ArrayList<>(Files.readAllLines(envPath, StandardCharsets.UTF_8));

      // Idempotent gate: once every managed key is set, we are done. The
      // reconcile can still detect a mailbox that has gone missing by
      // querying Stalwart directly, but for the .env-writing half a
      // fully-provisioned box is a no-op that produces no log line.
      if (allKeysPresent(lines)) {
        log.debug("authelia mail provision: env already carries the SMTP block");
        return;
      }

      if (!mail.reachable()) {
        log.debug("authelia mail provision: JMAP not reachable yet, will retry");
        return;
      }
      String domain = provision.mailDomain();
      if (!mail.domainExists(domain)) {
        log.debug("authelia mail provision: domain {} does not exist yet, will retry", domain);
        return;
      }

      String password = generatePassword();
      // Delete-then-create if the mailbox exists but its password is
      // unknown to us. Stalwart only stores hashes, so the value in
      // .env has to be the source of truth; if they disagree, Authelia
      // will fail to send. Aurora never sees the plaintext of a
      // previously-set mailbox, so the recovery move is to reset.
      var existingId = findMailboxId(AUTHELIA_LOCAL_PART, domain);
      if (existingId != null) {
        mail.resetMailboxPassword(existingId, password);
        log.info("authelia mail provision: reset password for existing authelia@{}", domain);
      } else {
        mail.createMailbox(AUTHELIA_LOCAL_PART, domain, password);
        log.info("authelia mail provision: created authelia@{}", domain);
      }

      String sender = AUTHELIA_LOCAL_PART + "@" + domain;
      upsertLine(lines, "AUTHELIA_NOTIFIER_SMTP_HOST", STALWART_HOST);
      upsertLine(lines, "AUTHELIA_NOTIFIER_SMTP_PORT", STALWART_SUBMISSION_PORT);
      upsertLine(lines, "AUTHELIA_NOTIFIER_SMTP_USERNAME", sender);
      upsertLine(lines, "AUTHELIA_NOTIFIER_SMTP_PASSWORD", password);
      upsertLine(lines, "AUTHELIA_NOTIFIER_SMTP_SENDER", sender);

      writeEnv(envPath, lines);
      audit.record(null, "authelia.mail.provision",
          "packages/core/.env",
          "{\"sender\":\"" + sender + "\"}");
      log.info("authelia mail provision: wrote packages/core/.env; recreate authelia on next up.sh");
    } catch (Exception e) {
      // Same fail-closed pattern as every other startup service that
      // depends on Stalwart's own boot completing first.
      log.debug("authelia mail provision: pass failed, will retry: {}", e.getMessage());
    }
  }

  /** True iff every {@link #MANAGED_KEYS} entry is present and non-empty. */
  private boolean allKeysPresent(List<String> lines) {
    for (String key : MANAGED_KEYS) {
      String v = readValue(lines, key);
      if (v == null || v.isEmpty()) return false;
    }
    return true;
  }

  /** Id of the mailbox with the given local-part, or null. */
  private String findMailboxId(String localPart, String domain) {
    String needle = (localPart + "@" + domain).toLowerCase(Locale.ROOT);
    for (var m : mail.listMailboxes()) {
      String addr = m.address();
      if (addr != null && addr.toLowerCase(Locale.ROOT).equals(needle)) {
        return m.id();
      }
    }
    return null;
  }

  /** {@code packages/core/.env}. */
  private Path envPath() {
    return Path.of(props.repoPath(), "packages", "core", ".env");
  }

  /**
   * 32 bytes of entropy, hex-encoded. Long enough to survive Stalwart's
   * "password too weak" check with no shape choices to argue over.
   */
  private String generatePassword() {
    byte[] buf = new byte[32];
    rng.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }

  /** Value for {@code key}, or null when the key is absent. Copied from IdentitySecretsService. */
  private static String readValue(List<String> lines, String key) {
    for (String line : lines) {
      Matcher m = KEY_LINE.matcher(line);
      if (m.matches() && key.equals(m.group(1))) {
        String rhs = line.substring(line.indexOf('=') + 1);
        return unquote(rhs);
      }
    }
    return null;
  }

  /** Set or replace {@code key=value}, preserving order and comments. */
  private static void upsertLine(List<String> lines, String key, String value) {
    String rendered = key + "=" + value;
    for (int i = 0; i < lines.size(); i++) {
      Matcher m = KEY_LINE.matcher(lines.get(i));
      if (m.matches() && key.equals(m.group(1))) {
        lines.set(i, rendered);
        return;
      }
    }
    // Also replace a commented-out version so the .env.example scaffold
    // does not leave both #KEY=... and KEY=... in the file.
    Pattern commented = Pattern.compile("^\\s*#\\s*" + Pattern.quote(key) + "\\s*=.*$");
    for (int i = 0; i < lines.size(); i++) {
      if (commented.matcher(lines.get(i)).matches()) {
        lines.set(i, rendered);
        return;
      }
    }
    lines.add(rendered);
  }

  private static String unquote(String s) {
    if (s.length() >= 2
        && ((s.startsWith("\"") && s.endsWith("\""))
        || (s.startsWith("'") && s.endsWith("'")))) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  /**
   * Write the .env file atomically-ish (write to a temp file then move).
   * Kept simple: same shape as IdentitySecretsService.writeEnv, minus
   * the POSIX chmod (which the container-side JVM cannot always apply).
   */
  private static void writeEnv(Path envPath, List<String> lines) throws IOException {
    Path parent = envPath.getParent();
    Path tmp = Files.createTempFile(parent, ".env.", ".tmp");
    try {
      Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
      Files.move(tmp, envPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
