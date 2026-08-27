package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read-only accessor for Stalwart's recovery-admin secret.
 *
 * <p><b>Why this exists.</b> The Stalwart admin console (mail-admin
 * subdomain) sits behind Authelia and the operator still has to log in
 * with the recovery admin credential to run the first-run setup wizard
 * (add domain, create mailboxes, pick datastore). That credential is
 * declared in {@code packages/core/.env} as {@code STALWART_ADMIN_SECRET}
 * and passed through to the Stalwart container as
 * {@code STALWART_RECOVERY_ADMIN=admin:${STALWART_ADMIN_SECRET}}. Without
 * a way to see it in the dashboard, operators end up
 * {@code cat}-ing the {@code .env} file over ssh \u2014 which defeats the
 * point of having a dashboard.
 *
 * <p><b>Read-only, admin-only.</b> No mutating surface. Rotating the
 * secret is a real operation that requires recreating the Stalwart
 * container (compose interpolates the env at container-create time and
 * a live container keeps its old value forever) and belongs to
 * {@code rotate-secrets.sh}, not to the reveal panel. Everything here
 * just answers the question "what is the currently-configured value".
 *
 * <p><b>The default-value carve-out.</b> The compose file has
 * {@code ${STALWART_ADMIN_SECRET:-aurora-change-me}} so an empty .env
 * still boots a working container \u2014 that string is the value baked
 * into Stalwart. If .env is blank, we return the compose fallback
 * verbatim and flag it as such so the UI can tell the operator to
 * rotate. If .env carries a real value we return that.
 */
@Service
public class StalwartAdminService {

  private static final Logger log = LoggerFactory.getLogger(StalwartAdminService.class);

  static final String CORE_PACKAGE = "core";
  static final String KEY = "STALWART_ADMIN_SECRET";

  /**
   * The compose default from {@code packages/core/compose.yml}. Kept as
   * a constant here so a test can pin the two in lock-step \u2014 if the
   * compose file rotates the default, the dashboard has to as well or
   * the reveal panel would lie.
   */
  static final String COMPOSE_DEFAULT = "aurora-change-me";

  /** Container-side username Stalwart's recovery admin logs in as. */
  static final String RECOVERY_USERNAME = "admin";

  private static final Pattern KEY_LINE =
      Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");

  private final AuroraProperties props;

  public StalwartAdminService(AuroraProperties props) {
    this.props = props;
  }

  /**
   * Where the returned secret came from.
   *
   * <ul>
   *   <li>{@link #ENV} \u2014 a real value in {@code packages/core/.env}. The
   *       one to show as-is; nothing for the operator to do about it.</li>
   *   <li>{@link #DEFAULT} \u2014 the .env is blank / missing the key, so the
   *       container is running with the compose fallback
   *       ({@code aurora-change-me}). The UI should flag this
   *       prominently: it is a known-shape value on every box that
   *       skipped rotation, and every attacker on the LAN knows it.</li>
   * </ul>
   */
  public enum Source { ENV, DEFAULT }

  /**
   * Snapshot of the recovery-admin credential.
   *
   * @param username the fixed side of the credential ({@code admin})
   * @param secret   the plaintext password Stalwart is running with
   * @param source   whether that value came from operator-managed .env or the compose default
   */
  public record AdminCredential(String username, String secret, Source source) {}

  /**
   * Current effective credential. Never throws \u2014 an unreadable .env
   * falls back to the compose default (which is the value Stalwart is
   * actually running with in that state anyway).
   */
  public AdminCredential currentCredential() {
    Path env = envPath();
    if (!Files.isReadable(env)) {
      log.debug("stalwart admin secret: {} not readable, using compose default", env);
      return new AdminCredential(RECOVERY_USERNAME, COMPOSE_DEFAULT, Source.DEFAULT);
    }
    try {
      String value = readValue(Files.readAllLines(env, StandardCharsets.UTF_8), KEY);
      if (value == null || value.isBlank()) {
        return new AdminCredential(RECOVERY_USERNAME, COMPOSE_DEFAULT, Source.DEFAULT);
      }
      return new AdminCredential(RECOVERY_USERNAME, value, Source.ENV);
    } catch (IOException e) {
      log.warn("could not read {}: {}", env, e.getMessage());
      return new AdminCredential(RECOVERY_USERNAME, COMPOSE_DEFAULT, Source.DEFAULT);
    }
  }

  Path envPath() {
    return Path.of(props.repoPath(), "packages", CORE_PACKAGE, ".env");
  }

  /**
   * Extract the value for {@code key} from the given .env lines. Returns
   * null when the key is absent, empty string when it is present but
   * blank. Package-private for tests; shape mirrors the equivalent
   * helper in {@link IdentitySecretsService} \u2014 keeping them separate
   * because the two services own different files and I do not want a
   * change to one to silently shift the parse for the other.
   */
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

  private static String stripInlineComment(String s) {
    int hash = s.indexOf('#');
    return hash < 0 ? s : s.substring(0, hash);
  }
}
