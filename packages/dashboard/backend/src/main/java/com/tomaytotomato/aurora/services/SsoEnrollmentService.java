package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Second-factor enrollment state, for the onboarding "Set up SSO" step.
 *
 * <p><b>Why this exists.</b> Every {@code *.DOMAIN} vhost is
 * {@code policy: two_factor} (see {@code packages/core/authelia/configuration.yml}),
 * so reaching any gated service requires a registered second factor. But
 * Authelia's registration link is delivered through its {@code notifier},
 * and on a LAN appliance with no mail server configured that notifier is
 * {@code filesystem} — it appends the link to
 * {@code data/authelia/notification.txt}, a file inside the Authelia
 * container that nobody will ever think to read.
 *
 * <p>The practical consequence on a fresh box is that services are not
 * merely awkward to sign into, they are <em>impossible</em> to sign into:
 * forward-auth redirects correctly to the portal, the portal asks for a
 * second factor, and the only way to enroll one is to know to run
 * {@code docker exec authelia cat /data/notification.txt}. This service
 * reads that file on the operator's behalf so the wizard can hand them
 * the link directly.
 *
 * <p><b>Read-only, by design.</b> Aurora never writes to Authelia's
 * database or notification file. Enrollment state is derived by counting
 * rows in Authelia's own SQLite; the file is only ever read and parsed.
 * That keeps the existing one-way projection contract intact
 * ({@link AutheliaService} writes {@code users_database.yml} and nothing
 * else), so there is no path by which Aurora can corrupt Authelia's
 * state.
 *
 * @see AutheliaService the one-way user projector
 */
@Service
public class SsoEnrollmentService {

  private static final Logger log = LoggerFactory.getLogger(SsoEnrollmentService.class);

  /** Authelia's filesystem-notifier output, relative to the repo root. */
  private static final String NOTIFICATION_FILE = "data/authelia/notification.txt";

  /** Authelia's own SQLite, relative to the repo root. */
  private static final String AUTHELIA_DB = "data/authelia/db.sqlite3";

  /**
   * Any http(s) URL in the notification body.
   *
   * <p>Authelia's filesystem notifier writes a human-readable block per
   * notification rather than a machine format, and the exact wording has
   * changed across releases. Rather than pin to a phrase that a future
   * upgrade will silently break, we take the last URL in the file: the
   * file is append-only, so the last one is the most recent request, and
   * every registration notification contains exactly one actionable link.
   */
  private static final Pattern URL = Pattern.compile("https?://\\S+");

  /**
   * Tables that mean "this user can complete a two_factor policy".
   *
   * <p>Duo is deliberately excluded: it requires an external service, is
   * not configured on an offline LAN box, and counting it would report a
   * usable factor that cannot actually be used here.
   */
  private static final List<String> FACTOR_TABLES =
      List.of("webauthn_credentials", "totp_configurations");

  private final AuroraProperties props;

  public SsoEnrollmentService(AuroraProperties props) {
    this.props = props;
  }

  /**
   * Snapshot of where the operator is in second-factor setup.
   *
   * @param enrolled       at least one usable second factor exists
   * @param factorCount    how many, across WebAuthn + TOTP
   * @param passkeyCount   WebAuthn credentials specifically, so the UI can
   *                       say "passkey" rather than the generic "second factor"
   * @param pendingUrl     most recent registration link, if one is waiting
   * @param pendingAt      when that link was observed (file mtime)
   * @param autheliaUp     whether Authelia's database was readable at all
   */
  public record EnrollmentStatus(
      boolean enrolled,
      int factorCount,
      int passkeyCount,
      String pendingUrl,
      String pendingAt,
      boolean autheliaUp
  ) {}

  /** Current enrollment state. Never throws — the wizard always needs an answer. */
  public EnrollmentStatus status() {
    int webauthn = countRows("webauthn_credentials");
    int totp = countRows("totp_configurations");

    // -1 is the "could not read" sentinel from countRows. Treat an
    // unreadable database as "Authelia is not up yet" rather than as
    // "zero factors enrolled" — during first launch the container may
    // not have created the schema, and telling the operator they have
    // no factors when we simply cannot see them would send them round
    // an enrollment loop they do not need.
    boolean up = webauthn >= 0 && totp >= 0;
    int count = up ? webauthn + totp : 0;

    var pending = pendingRegistration();

    return new EnrollmentStatus(
        count > 0,
        count,
        Math.max(webauthn, 0),
        pending.map(Pending::url).orElse(null),
        pending.map(p -> p.at().toString()).orElse(null),
        up
    );
  }

  private record Pending(String url, Instant at) {}

  /**
   * The most recent registration link Authelia has emitted, if any.
   *
   * <p>Returns empty when the notifier has never fired (a 0-byte file on
   * a fresh box) or when the file holds no URL at all.
   */
  private Optional<Pending> pendingRegistration() {
    Path p = repo().resolve(NOTIFICATION_FILE);
    if (!Files.isReadable(p)) return Optional.empty();
    try {
      String body = Files.readString(p);
      if (body.isBlank()) return Optional.empty();

      Matcher m = URL.matcher(body);
      String last = null;
      while (m.find()) last = m.group();
      if (last == null) return Optional.empty();

      // Trailing punctuation from prose wrapping ("…/register.>" or a
      // sentence-ending period) is not part of the URL.
      last = last.replaceAll("[.,;:>)\\]]+$", "");

      return Optional.of(new Pending(last, Files.getLastModifiedTime(p).toInstant()));
    } catch (IOException e) {
      // The file is written by the Authelia container as root while
      // Aurora runs as the invoking user, so a permissions mismatch is
      // a realistic failure. Log once at debug and report "nothing
      // pending" — the operator can still use the portal directly.
      log.debug("could not read {}: {}", p, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Row count for one of Authelia's factor tables.
   *
   * @return the count, or {@code -1} when the database or table could not
   *     be read (Authelia not yet started, schema not yet migrated)
   */
  private int countRows(String table) {
    // Table names cannot be bound as parameters, so this must be a
    // literal. FACTOR_TABLES is a private constant of hardcoded strings
    // and no caller supplies the value, so there is no injection path;
    // the guard below makes that explicit rather than implicit.
    if (!FACTOR_TABLES.contains(table)) {
      throw new IllegalArgumentException("not a known factor table: " + table);
    }

    Path db = repo().resolve(AUTHELIA_DB);
    if (!Files.isReadable(db)) return -1;

    String url = "jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro";
    try (Connection c = DriverManager.getConnection(url);
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("select count(*) from " + table)) {
      return rs.next() ? rs.getInt(1) : 0;
    } catch (SQLException e) {
      log.debug("could not count {}: {}", table, e.getMessage());
      return -1;
    }
  }

  private Path repo() {
    return Path.of(props.repoPath());
  }
}
