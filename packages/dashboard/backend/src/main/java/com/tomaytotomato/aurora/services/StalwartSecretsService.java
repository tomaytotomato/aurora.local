package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Fills in {@code STALWART_ADMIN_SECRET} in {@code packages/core/.env}
 * on first bring-up when the value is empty, and owns rotation writes
 * for the reveal-panel PUT endpoint.
 *
 * <p><b>Why boot-seed at all.</b> The Stalwart compose file declares
 * {@code ${STALWART_ADMIN_SECRET:-aurora-change-me}}, so a fresh box
 * with a blank .env still boots \u2014 running with the same
 * every-attacker-knows-it fallback that {@link StalwartAdminService}
 * flags as {@link StalwartAdminService.Source#DEFAULT}. Operators end
 * up either (a) never rotating and living with the fallback or (b)
 * shelling in to run {@code rotate-secrets.sh --apply}. Neither is
 * something an Aurora dashboard operator should have to know about.
 * This service closes the first-run gap the same way
 * {@link BackupSecretsService} closes it for kopia: on boot, if the
 * value is blank, generate a real one and audit the change.
 *
 * <p><b>Never rotate a populated value.</b> Compose interpolates the
 * env at container-create time. A live Stalwart container carries the
 * secret it was created with; silently overwriting a populated .env
 * value here would leave the operator's Reveal panel showing a new
 * secret that the running container has never heard of, and the
 * mail-admin console would keep taking the old one until somebody
 * ran {@code docker compose up -d --force-recreate stalwart}. So the
 * boot path is strictly seed-when-blank, and rotation is an explicit
 * operator action through {@link #writeSecret(String, Long)} \u2014 which
 * takes the same care and reminds the operator about the recreate step
 * on the frontend.
 *
 * <p><b>Fail-closed.</b> An unwritable or malformed .env logs a
 * warning and does not crash the dashboard \u2014 same fail-mode as
 * {@link IdentitySecretsService} and {@link BackupSecretsService}.
 */
@Service
public class StalwartSecretsService {

  private static final Logger log = LoggerFactory.getLogger(StalwartSecretsService.class);

  static final String CORE_PACKAGE = "core";

  /** The one key we own here. Everything else in packages/core/.env belongs to somebody else. */
  static final String STALWART_ADMIN_SECRET = "STALWART_ADMIN_SECRET";

  /** Matches {@code IdentitySecretsService}: hex-encoded 32-byte value. */
  static final int SECRET_BYTES = 32;

  /** Product-level floor. Same 12-char minimum the change-password endpoint enforces. */
  static final int MIN_SECRET_LENGTH = 12;

  private static final Pattern KEY_LINE =
      Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");

  private final AuroraProperties props;
  private final AuditEventRepo audit;
  private final SecureRandom rng;

  public StalwartSecretsService(AuroraProperties props, AuditEventRepo audit) {
    this.props = props;
    this.audit = audit;
    this.rng = new SecureRandom();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    try {
      if (!Files.exists(envPath())) {
        // Real boxes have scripts/up.sh in front of us: it copies
        // .env.example -> .env before the dashboard container starts.
        // If .env is still missing here, the operator is running the
        // dashboard against a synthetic repo fixture (integration
        // tests do this) or has never enabled core yet. Either way,
        // nothing to seed.
        log.debug("stalwart secrets: packages/core/.env not present yet, skipping boot seed");
        return;
      }
      ensureSecret();
    } catch (Exception e) {
      // Same fail-closed pattern as BackupSecretsService: never crash
      // the dashboard because we could not touch one .env file at boot.
      log.warn("stalwart secrets bootstrap failed at startup: {}", e.getMessage());
    }
  }

  /**
   * Idempotent: writes {@code STALWART_ADMIN_SECRET} only when the
   * current value is missing or blank. Returns true when a value was
   * written.
   *
   * <p>Package-private for tests. Synchronized because {@link #onReady()}
   * could race a future manual trigger on the same file.
   */
  synchronized boolean ensureSecret() throws IOException {
    Path envPath = envPath();
    List<String> lines = new ArrayList<>(Files.readAllLines(envPath, StandardCharsets.UTF_8));

    String existing = readValue(lines, STALWART_ADMIN_SECRET);
    if (existing != null && !existing.isBlank()) {
      // Do not touch. The running container was created with this
      // value; replacing it here would just make the Reveal panel lie.
      return false;
    }

    upsertLine(lines, STALWART_ADMIN_SECRET, generateSecret());
    writeEnv(envPath, lines);
    audit.record(null, "stalwart.secrets.bootstrap",
        "packages/core/.env",
        "{\"generated_keys\":[\"" + STALWART_ADMIN_SECRET + "\"]}");
    log.info("stalwart secrets: generated {} on bootstrap", STALWART_ADMIN_SECRET);
    return true;
  }

  /**
   * Rotate {@code STALWART_ADMIN_SECRET} to a caller-supplied value.
   * Powers {@code PUT /api/services/stalwart/admin-secret}.
   *
   * <p>Preserves comments + every other key in {@code packages/core/.env}
   * verbatim \u2014 same file-mutation pattern as
   * {@link IdentitySecretsService#ensureSecrets()}. When the file does
   * not exist yet, we create it with just this one line: the operator
   * has explicitly clicked Save on the Reveal panel, so writing what
   * they asked for is the right thing to do even on a bare box.
   *
   * <p>Audits as {@code stalwart.admin-secret.rotate} with the acting
   * user id. The plaintext value is never included in the audit row.
   *
   * @throws IllegalArgumentException when the value is null or shorter
   *   than {@link #MIN_SECRET_LENGTH}. The controller wraps this as a
   *   400.
   */
  public synchronized void writeSecret(String newValue, Long actingUserId) throws IOException {
    if (newValue == null || newValue.length() < MIN_SECRET_LENGTH) {
      throw new IllegalArgumentException(
          "recovery-admin password must be at least " + MIN_SECRET_LENGTH + " characters");
    }
    Path envPath = envPath();
    List<String> lines = Files.exists(envPath)
        ? new ArrayList<>(Files.readAllLines(envPath, StandardCharsets.UTF_8))
        : new ArrayList<>();

    upsertLine(lines, STALWART_ADMIN_SECRET, newValue);
    writeEnv(envPath, lines);
    audit.record(actingUserId, "stalwart.admin-secret.rotate",
        "packages/core/.env",
        "{\"rotated_keys\":[\"" + STALWART_ADMIN_SECRET + "\"]}");
    log.info("stalwart secrets: rotated {} (acting user {})",
        STALWART_ADMIN_SECRET, actingUserId);
  }

  Path envPath() {
    return Path.of(props.repoPath(), "packages", CORE_PACKAGE, ".env");
  }

  // \u2500\u2500\u2500 file I/O (shape mirrors BackupSecretsService) \u2500\u2500\u2500\u2500\u2500\u2500

  static String readValue(List<String> lines, String key) {
    for (String line : lines) {
      Matcher m = KEY_LINE.matcher(line);
      if (m.matches() && key.equals(m.group(1))) {
        int eq = line.indexOf('=');
        return eq < 0 ? "" : stripInlineComment(line.substring(eq + 1)).trim();
      }
    }
    return null;
  }

  static void upsertLine(List<String> lines, String key, String value) {
    String replacement = key + "=" + value;
    for (int i = 0; i < lines.size(); i++) {
      Matcher m = KEY_LINE.matcher(lines.get(i));
      if (m.matches() && key.equals(m.group(1))) {
        lines.set(i, replacement);
        return;
      }
    }
    lines.add(replacement);
  }

  private static String stripInlineComment(String s) {
    int hash = s.indexOf('#');
    return hash < 0 ? s : s.substring(0, hash);
  }

  private static void writeEnv(Path envPath, List<String> lines) throws IOException {
    Files.createDirectories(envPath.getParent());
    String body = String.join("\n", lines) + "\n";
    Files.writeString(envPath, body, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    try {
      Files.setPosixFilePermissions(envPath,
          Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                 java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException ignore) { /* non-posix fs */ }
  }

  /** Hex-encoded 32-byte random string \u2014 matches {@code openssl rand -hex 32}. */
  String generateSecret() {
    byte[] buf = new byte[SECRET_BYTES];
    rng.nextBytes(buf);
    var sb = new StringBuilder(buf.length * 2);
    for (byte b : buf) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}
