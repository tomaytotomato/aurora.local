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
 * Fills in the {@code KOPIA_PASSWORD} in {@code packages/backup/.env}
 * on first bring-up when the value is empty.
 *
 * <p><b>Why this exists.</b> Kopia refuses to expose its REST API on a
 * non-loopback bind without a password ("insecure server on non-loopback
 * network bind: 0.0.0.0"). The compose file publishes the UI on 0.0.0.0
 * so Caddy can reverse-proxy it, so an empty {@code KOPIA_PASSWORD}
 * means the container restart-loops forever the moment the backup
 * package is enabled. On the QA sweep box this was hiding behind an
 * even worse bug (Aurora was reporting the package as "running" while
 * the container was dying every three seconds); with that bug fixed
 * the empty password itself is now the operator's first-run problem.
 *
 * <p>{@code rotate-secrets.sh --apply} would happily fill an empty
 * value in {@code .env}, but two things make that the wrong seam:
 * <ol>
 *   <li>The operator has to know to run it. The Aurora dashboard
 *       operator does not, and there is no obvious hint on the box.</li>
 *   <li>Kopia's encryption password is <b>not</b> a rotatable secret
 *       once the repository exists \u2014 rotating it would strand every
 *       backup ever taken. So we must never <em>replace</em> an
 *       existing password; only seed one when the field is blank.</li>
 * </ol>
 *
 * <p><b>What this service does and doesn't do.</b>
 * <ul>
 *   <li>On {@link ApplicationReadyEvent}, if
 *       {@code packages/backup/.env} exists and
 *       {@code KOPIA_PASSWORD=} is empty, generate a 32-byte hex
 *       password and write it back. Audit the change.</li>
 *   <li>Never touch an already-populated password: matches the
 *       {@code .env.example} comment ("NEVER change this after
 *       {@code kopia repository create}; if you lose it, backups are
 *       gone").</li>
 *   <li>Never manufacture a {@code .env} from scratch: on a real box
 *       {@code scripts/up.sh} creates it from {@code .env.example}
 *       before this ever runs, and doing so at boot would pollute
 *       integration-test fixtures that ship a synthetic repo without
 *       backup enabled.</li>
 *   <li>Never rotate {@code KOPIA_UI_PASSWORD} \u2014 that one is a
 *       nuisance to change but does not destroy data. Leave it to
 *       {@code rotate-secrets.sh} where the intent is explicit.</li>
 * </ul>
 *
 * <p><b>No REST surface.</b> This service exposes no HTTP endpoint.
 * The whole story is a boot-time backstop for a first-run footgun.
 */
@Service
public class BackupSecretsService {

  private static final Logger log = LoggerFactory.getLogger(BackupSecretsService.class);

  static final String BACKUP_PACKAGE = "backup";

  /** The one key we own. UI password and repo URL are left to the operator. */
  static final String KOPIA_PASSWORD = "KOPIA_PASSWORD";

  static final int SECRET_BYTES = 32;

  private static final Pattern KEY_LINE =
      Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");

  private final AuroraProperties props;
  private final AuditEventRepo audit;
  private final SecureRandom rng;

  public BackupSecretsService(AuroraProperties props, AuditEventRepo audit) {
    this.props = props;
    this.audit = audit;
    this.rng = new SecureRandom();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    try {
      if (!Files.exists(envPath())) {
        // A real box has scripts/up.sh in front of us and it copies
        // .env.example -> .env before starting the dashboard container.
        // If .env is still missing here, either the backup package has
        // never been enabled (fine, nothing to do) or the operator is
        // running Aurora out of a synthetic repo fixture (also fine).
        log.debug("backup secrets: packages/backup/.env not present yet, skipping boot seed");
        return;
      }
      ensureSecret();
    } catch (Exception e) {
      // Same fail-closed pattern as IdentitySecretsService: never crash
      // the dashboard because we could not touch one .env file at boot.
      log.warn("backup secrets bootstrap failed at startup: {}", e.getMessage());
    }
  }

  /**
   * Idempotent: writes {@code KOPIA_PASSWORD} only when the current
   * value is missing or blank. Returns true when a value was written.
   *
   * <p>Package-private for tests. Synchronized because {@link #onReady()}
   * and any future manual trigger could race on the same file.
   */
  synchronized boolean ensureSecret() throws IOException {
    Path envPath = envPath();
    List<String> lines = new ArrayList<>(Files.readAllLines(envPath, StandardCharsets.UTF_8));

    String existing = readValue(lines, KOPIA_PASSWORD);
    if (existing != null && !existing.isBlank()) {
      // Do not touch. A populated password may be the encryption key
      // for a repository that already exists on the box; replacing it
      // would render every prior backup unrecoverable.
      return false;
    }

    upsertLine(lines, KOPIA_PASSWORD, generateSecret());
    writeEnv(envPath, lines);
    audit.record(null, "backup.secrets.bootstrap",
        "packages/backup/.env",
        "{\"generated_keys\":[\"" + KOPIA_PASSWORD + "\"]}");
    log.info("backup secrets: generated {} on bootstrap", KOPIA_PASSWORD);
    return true;
  }

  Path envPath() {
    return Path.of(props.repoPath(), "packages", BACKUP_PACKAGE, ".env");
  }

  // ─── file I/O (shape mirrors IdentitySecretsService) ──────────────

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

  /** Hex-encoded 32-byte random string — matches {@code openssl rand -hex 32}. */
  String generateSecret() {
    byte[] buf = new byte[SECRET_BYTES];
    rng.nextBytes(buf);
    var sb = new StringBuilder(buf.length * 2);
    for (byte b : buf) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}
