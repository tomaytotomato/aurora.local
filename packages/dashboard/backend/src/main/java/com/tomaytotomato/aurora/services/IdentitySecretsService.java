package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase D iter-4 \u2014 manage the three Authelia secrets in
 * {@code packages/identity/.env}.
 *
 * <p>Authelia refuses to start if any of these are missing or shorter
 * than 32 bytes, and rotating them invalidates every session cookie in
 * flight. Aurora owns them:
 *
 * <ul>
 *   <li>{@code AUTHELIA_JWT_SECRET} \u2014 signs the identity-verification
 *       JWTs (2FA enrolment, password reset).</li>
 *   <li>{@code AUTHELIA_SESSION_SECRET} \u2014 signs session cookies used
 *       across every {@code *.aurora.local} vhost.</li>
 *   <li>{@code AUTHELIA_STORAGE_ENCRYPTION_KEY} \u2014 encrypts stored TOTP
 *       secrets + WebAuthn credentials at rest in Authelia's SQLite.</li>
 * </ul>
 *
 * <p><b>Bootstrap policy.</b> On {@link ApplicationReadyEvent}, if the
 * identity package is enabled in {@code .state.yml}, call
 * {@link #ensureSecrets()} \u2014 idempotent, only generates keys that are
 * missing or empty. When identity is disabled we skip entirely
 * (generating secrets nobody will use just clutters {@code .env}).
 *
 * <p><b>Rotation policy.</b> {@link #rotateSecrets(Long)} regenerates
 * every secret. Sessions in flight all invalidate; users get bounced
 * to the Authelia login page next request. Audit row records which
 * keys were rotated and by whom; the secret values themselves never
 * touch the audit log.
 *
 * <p><b>File shape.</b> Preserves the {@code .env.example} layout so
 * an operator peeking at {@code packages/identity/.env} still sees the
 * comments about SMTP + TZ. Only the three secret lines are mutated.
 * Written with {@code 0600} where the filesystem supports POSIX perms.
 *
 * <p><b>Threat model.</b> Whoever can read {@code packages/identity/.env}
 * can impersonate any Authelia session cookie. Aurora runs as the
 * invoking user + docker group; only that user has repo-root rw. Aurora
 * never surfaces these values via API, and audit rows only reveal
 * which keys were rotated + when + by whom.
 */
@Service
public class IdentitySecretsService {

  private static final Logger log = LoggerFactory.getLogger(IdentitySecretsService.class);

  /** Package name matched against {@code .state.yml}'s enabled[] list. */
  static final String IDENTITY_PACKAGE = "identity";

  /** Every secret Authelia mandates. Order matters for stable audit-log JSON. */
  static final List<String> MANAGED_KEYS = List.of(
      "AUTHELIA_JWT_SECRET",
      "AUTHELIA_SESSION_SECRET",
      "AUTHELIA_STORAGE_ENCRYPTION_KEY"
  );

  /** Bytes of entropy per secret. Authelia's minimum is 32 (256-bit). */
  static final int SECRET_BYTES = 32;

  private static final Pattern KEY_LINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");

  private final StateFileService state;
  private final AuditEventRepo audit;
  private final AuroraProperties props;
  private final SecureRandom rng;

  public IdentitySecretsService(StateFileService state, AuditEventRepo audit, AuroraProperties props) {
    this.state = state;
    this.audit = audit;
    this.props = props;
    this.rng = new SecureRandom();
  }

  // \u2500\u2500\u2500 lifecycle \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    try {
      if (!identityEnabled()) {
        log.debug("identity secrets: skipping bootstrap \u2014 identity package not enabled");
        return;
      }
      log.info("identity secrets: ensuring bootstrap on startup");
      ensureSecrets();
    } catch (Exception e) {
      // Same fail-closed pattern as AutheliaService \u2014 don't crash the
      // dashboard because we couldn't touch one .env file at boot.
      log.warn("identity secrets bootstrap failed at startup: {}", e.getMessage());
    }
  }

  // \u2500\u2500\u2500 public API \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /**
   * Idempotent: generates only the keys that are missing or empty.
   * Returns the set of keys we actually wrote (empty when the file was
   * already complete).
   *
   * <p>Creates {@code packages/identity/.env} from {@code .env.example}
   * (or from a synthesised default) when the file doesn't exist. Preserves
   * comments + non-managed keys verbatim.
   *
   * <p>Records an audit row when at least one key was generated; skips
   * the audit row when the file was already complete.
   */
  public synchronized Set<String> ensureSecrets() throws IOException {
    Path envPath = envPath();
    List<String> lines = readOrTemplate(envPath);

    Set<String> generated = new LinkedHashSet<>();
    for (String key : MANAGED_KEYS) {
      String existing = readValue(lines, key);
      if (existing == null || existing.isBlank()) {
        upsertLine(lines, key, generateSecret());
        generated.add(key);
      }
    }

    if (!generated.isEmpty()) {
      writeEnv(envPath, lines);
      audit.record(null, "identity.secrets.bootstrap",
          "packages/identity/.env",
          "{\"generated_keys\":" + jsonKeyArray(generated) + "}");
      log.info("identity secrets: generated {} key{} on bootstrap ({})",
          generated.size(), generated.size() == 1 ? "" : "s", generated);
    }
    return generated;
  }

  /**
   * Rotate every managed key. Invalidates every Authelia session and
   * every stored TOTP secret \u2014 real intent required. Records an audit
   * row with the rotated key names + acting user id.
   */
  public synchronized Set<String> rotateSecrets(Long actingUserId) throws IOException {
    Path envPath = envPath();
    List<String> lines = readOrTemplate(envPath);

    Set<String> rotated = new LinkedHashSet<>(MANAGED_KEYS);
    for (String key : MANAGED_KEYS) {
      upsertLine(lines, key, generateSecret());
    }
    writeEnv(envPath, lines);
    audit.record(actingUserId, "identity.secrets.rotate",
        "packages/identity/.env",
        "{\"rotated_keys\":" + jsonKeyArray(rotated) + "}");
    log.info("identity secrets: rotated {} keys (acting user {})", rotated.size(), actingUserId);
    return rotated;
  }

  /**
   * Phase D iter-12 (D11). Blank the listed env keys in a package's
   * {@code .env} so a service can run internal-auth-less behind
   * Authelia's forward-auth gate. Idempotent — keys already empty are
   * skipped (no rewrite, no audit row).
   *
   * <p>Preserves comments + any other keys verbatim (same .env file
   * mutation pattern as {@link #ensureSecrets()}). Only rewrites the
   * file when at least one key was actually changed — a returning
   * SSO-on wizard doesn't churn the audit log.
   *
   * <p>Silently no-op when the package has no {@code .env} on disk
   * (e.g. a package that ships with no runtime secrets to worry about).
   *
   * @return the set of keys we actually blanked (possibly empty).
   */
  public synchronized Set<String> neutraliseServiceEnv(String packageName,
                                                       List<String> keysToClear,
                                                       Long actingUserId) throws IOException {
    if (packageName == null || packageName.isBlank() || keysToClear == null || keysToClear.isEmpty()) {
      return Set.of();
    }
    Path envPath = Path.of(props.repoPath(), "packages", packageName, ".env");
    if (!Files.isRegularFile(envPath)) return Set.of();

    List<String> lines = new ArrayList<>(Files.readAllLines(envPath, StandardCharsets.UTF_8));
    var cleared = new LinkedHashSet<String>();
    for (String key : keysToClear) {
      String existing = readValue(lines, key);
      if (existing == null || existing.isEmpty()) continue; // already blank
      upsertLine(lines, key, "");
      cleared.add(key);
    }
    if (cleared.isEmpty()) return Set.of();

    Path parent = envPath.getParent();
    if (parent != null) Files.createDirectories(parent);
    String body = String.join("\n", lines) + "\n";
    Files.writeString(envPath, body, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    try {
      Files.setPosixFilePermissions(envPath,
          Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                 java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException ignore) { /* non-posix fs */ }

    audit.record(actingUserId, "sso.env.neutralise",
        "packages/" + packageName + "/.env",
        "{\"cleared_keys\":" + jsonKeyArray(cleared) + "}");
    log.info("sso: neutralised {} key{} in packages/{}/.env (→ authelia-only auth)",
        cleared.size(), cleared.size() == 1 ? "" : "s", packageName);
    return cleared;
  }

  /** True when the identity package appears in {@code .state.yml}'s enabled[]. */
  public boolean identityEnabled() {
    var repo = state.readState();
    return repo.enabled() != null && repo.enabled().contains(IDENTITY_PACKAGE);
  }

  public Path envPath() {
    return Path.of(props.repoPath(), "packages", IDENTITY_PACKAGE, ".env");
  }

  Path envExamplePath() {
    return Path.of(props.repoPath(), "packages", IDENTITY_PACKAGE, ".env.example");
  }

  // \u2500\u2500\u2500 file I/O \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  private List<String> readOrTemplate(Path envPath) throws IOException {
    if (Files.isRegularFile(envPath)) {
      return new ArrayList<>(Files.readAllLines(envPath, StandardCharsets.UTF_8));
    }
    Path example = envExamplePath();
    if (Files.isRegularFile(example)) {
      // Copy the example so comments + optional keys survive.
      return new ArrayList<>(Files.readAllLines(example, StandardCharsets.UTF_8));
    }
    // Synth minimal template so a fresh install with no .env.example
    // still gets a well-formed file. Keeps the same key order as
    // MANAGED_KEYS for stable diffs.
    List<String> lines = new ArrayList<>();
    lines.add("# packages/identity/.env \u2014 managed by IdentitySecretsService");
    lines.add("TZ=Europe/London");
    lines.add("DOMAIN=aurora.local");
    for (String key : MANAGED_KEYS) lines.add(key + "=");
    return lines;
  }

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

  /** Strip a trailing {@code # comment} that lives on the same line as a value. */
  private static String stripInlineComment(String s) {
    int hash = s.indexOf('#');
    return hash < 0 ? s : s.substring(0, hash);
  }

  private void writeEnv(Path envPath, List<String> lines) throws IOException {
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

  // \u2500\u2500\u2500 secret generation \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /** Hex-encoded 32-byte random string \u2014 matches {@code openssl rand -hex 32}. */
  String generateSecret() {
    byte[] buf = new byte[SECRET_BYTES];
    rng.nextBytes(buf);
    var sb = new StringBuilder(buf.length * 2);
    for (byte b : buf) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static String jsonKeyArray(Set<String> keys) {
    var sb = new StringBuilder("[");
    boolean first = true;
    for (String k : keys) {
      if (!first) sb.append(',');
      sb.append('"').append(k).append('"');
      first = false;
    }
    return sb.append(']').toString();
  }
}
