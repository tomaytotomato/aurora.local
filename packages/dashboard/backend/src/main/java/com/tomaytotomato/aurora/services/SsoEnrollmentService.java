package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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

  /** Authelia's own SQLite, relative to the repo root. Pre-Postgres boxes only. */
  private static final String AUTHELIA_DB = "data/authelia/db.sqlite3";

  /** The shared core Postgres instance Authelia's storage moved to. */
  private static final String CORE_DB_CONTAINER = "core-db";

  /** Authelia's database on that instance (packages/core/compose.yml). */
  private static final String AUTHELIA_DB_NAME = "authelia";

  /** Container name to shell into when the notifier file is unreadable from the host. */
  private static final String AUTHELIA_CONTAINER = "authelia";

  /** Same file, but path inside the Authelia container. */
  private static final String NOTIFICATION_FILE_IN_CONTAINER = "/data/notification.txt";

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
   * A standalone token that looks like a one-time code.
   *
   * <p>Authelia prints the OTP as a lone uppercase alphanumeric line
   * between two {@code ----} fences, e.g. {@code PHV9ZVAV}. Bounded 6-12
   * to avoid grabbing serial numbers or bearer fragments that happen to
   * be uppercase; the shape is stable across Authelia's currently-shipped
   * templates.
   */
  private static final Pattern OTP_LINE = Pattern.compile("^[A-Z0-9]{6,12}$");

  /** Line marker that opens each notification block. */
  private static final String ENTRY_MARKER = "Date: ";

  /** Prefixes that expose structured fields at the top of an entry. */
  private static final String RECIPIENT_PREFIX = "Recipient: ";
  private static final String SUBJECT_PREFIX = "Subject: ";

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
  private final DockerService docker;

  public SsoEnrollmentService(AuroraProperties props, DockerService docker) {
    this.props = props;
    this.docker = docker;
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

  /**
   * The notification file body, tried through two paths in order:
   *   1. Direct read from the bind-mounted host path. Fast when it
   *      works. Fails whenever Authelia writes the file as root and
   *      Aurora runs as an unprivileged UID (the default on a
   *      compose-up: Authelia has no {@code user:} override and
   *      writes mode 0600).
   *   2. {@code docker exec cat} into the Authelia container. Works
   *      regardless of host-side ownership, because {@code exec}
   *      inherits Authelia's root. Same primitive AutheliaCaService
   *      already uses for the Caddy root CA — different consumer,
   *      same permission story.
   *
   * <p>Returns empty when both fail (Authelia not running, docker socket
   * unreachable, file genuinely absent). The panel that renders these
   * turns an empty return into the "no notifications yet" empty state,
   * which is the right thing on a fresh box.
   */
  private Optional<String> readNotificationBody() {
    Path host = repo().resolve(NOTIFICATION_FILE);
    if (Files.isReadable(host)) {
      try {
        String body = Files.readString(host);
        if (!body.isBlank()) return Optional.of(body);
      } catch (java.io.IOException e) {
        log.debug("host-side read of {} failed, falling back to docker exec: {}", host, e.getMessage());
      }
    }
    // Fallback: read through the Authelia container. Same tolerance as
    // AutheliaCaService: an unrunnable exec (container stopped, docker
    // socket unreachable) is not an error we surface — the panel just
    // shows nothing.
    Optional<byte[]> viaExec = docker.readFileFromContainer(
        AUTHELIA_CONTAINER, NOTIFICATION_FILE_IN_CONTAINER);
    if (viaExec.isEmpty()) return Optional.empty();
    String body = new String(viaExec.get(), StandardCharsets.UTF_8);
    return body.isBlank() ? Optional.empty() : Optional.of(body);
  }

  /**
   * File-mtime for the notifier file, best effort.
   *
   * <p>Only reachable through the host-side path: docker’s exec API
   * does not surface file metadata. When the host-side path fails but
   * the exec path succeeds we return {@link Instant#now()} as a
   * proxy for the caller — the file was just observed to exist and
   * that is the only claim the caller (pending-URL surfacing) needs
   * to make.
   */
  private Optional<Instant> notificationMtime() {
    Path host = repo().resolve(NOTIFICATION_FILE);
    try {
      if (Files.isReadable(host)) {
        return Optional.of(Files.getLastModifiedTime(host).toInstant());
      }
    } catch (java.io.IOException ignored) {
      // fall through
    }
    return Optional.empty();
  }

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
   * One notification Authelia has emitted to its filesystem notifier.
   *
   * <p>The point of surfacing these in Aurora is that most of them
   * carry an actionable capability: a one-time code the operator has to
   * type into a browser prompt, or a link that binds an authenticator
   * to their account. On a LAN box with no mail server that capability
   * would otherwise vanish into a root-owned file inside a container.
   *
   * @param date       raw Authelia-formatted timestamp (kept verbatim
   *                   because Authelia's format has drifted across
   *                   releases and parsing it strictly would break the
   *                   panel on upgrade); UI formats client-side
   * @param recipient  the {@code {Name email}} bracket verbatim, so a
   *                   future Authelia change that adds fields does not
   *                   silently drop them
   * @param subject    Authelia's own subject line for the notification
   * @param otp        the one-time code, when this notification contains
   *                   one; null for password-reset links, enrollment
   *                   invitations, or any future notification type that
   *                   doesn't carry a lone alphanumeric token
   * @param urls       every URL in the body, in document order — usually
   *                   revoke + docs; kept as a list so the UI can show
   *                   the revoke link first without us having to guess
   *                   which is which
   * @param body       the notification's message body with prose intact,
   *                   for the "show details" toggle. Header lines
   *                   (Date/Recipient/Subject) are stripped so a raw view
   *                   in the UI does not repeat what the structured
   *                   fields already show.
   */
  public record Notification(
      String date,
      String recipient,
      String subject,
      String otp,
      List<String> urls,
      String body
  ) {}

  /**
   * The most recent notifications Authelia has written, newest first.
   *
   * <p>Bounded on purpose: the file is append-only and grows without a
   * rotation policy, and the UX is "the latest OTP you're waiting on",
   * not "forever, scroll back". Older entries are still in the raw file
   * for anyone who needs them.
   *
   * <p>Never throws — the panel that renders these needs an answer.
   * An unreadable or malformed file yields an empty list.
   *
   * @param limit maximum entries to return; caller-supplied so the same
   *              parser can drive both the Authelia detail panel (5) and
   *              a future "all notifications" audit surface
   */
  public List<Notification> notifications(int limit) {
    if (limit <= 0) return List.of();
    return readNotificationBody()
        .map(body -> parseNotifications(body, limit))
        .orElseGet(List::of);
  }

  /**
   * Split the notifier's append log into structured entries.
   *
   * <p>Entries are delimited by lines beginning with {@code "Date: "}.
   * That marker is Authelia's own, printed by every notification
   * template shipped since the notifier existed, so we anchor on it
   * rather than on the surrounding {@code ----} fences (which vary
   * between templates) or on blank lines (which appear inside bodies).
   */
  static List<Notification> parseNotifications(String content, int limit) {
    List<Notification> out = new ArrayList<>();
    String[] lines = content.split("\\R", -1);

    int i = 0;
    while (i < lines.length) {
      if (!lines[i].startsWith(ENTRY_MARKER)) { i++; continue; }
      int start = i;
      i++;
      while (i < lines.length && !lines[i].startsWith(ENTRY_MARKER)) i++;
      out.add(parseEntry(lines, start, i));
    }

    // Newest first. The file is append-only so newest is at the end;
    // reverse rather than sort, to keep entries with identical
    // timestamps in their original order (a burst of notifications
    // from a single action).
    Collections.reverse(out);
    if (out.size() > limit) return out.subList(0, limit);
    return out;
  }

  private static Notification parseEntry(String[] lines, int start, int endExclusive) {
    String date = lines[start].substring(ENTRY_MARKER.length()).trim();
    String recipient = null;
    String subject = null;
    int bodyStart = start + 1;

    // Recipient + Subject sit on the two lines immediately after Date
    // in every currently-shipped template. Loop rather than index so a
    // future template that adds another header (or reorders them) still
    // parses; anything unrecognised is treated as the first body line.
    while (bodyStart < endExclusive) {
      String line = lines[bodyStart];
      if (line.startsWith(RECIPIENT_PREFIX)) {
        recipient = line.substring(RECIPIENT_PREFIX.length()).trim();
        bodyStart++;
      } else if (line.startsWith(SUBJECT_PREFIX)) {
        subject = line.substring(SUBJECT_PREFIX.length()).trim();
        bodyStart++;
      } else {
        break;
      }
    }

    StringBuilder body = new StringBuilder();
    String otp = null;
    List<String> urls = new ArrayList<>();
    for (int j = bodyStart; j < endExclusive; j++) {
      String line = lines[j];
      if (body.length() > 0) body.append('\n');
      body.append(line);
      String trimmed = line.trim();
      if (otp == null && OTP_LINE.matcher(trimmed).matches()) otp = trimmed;
      Matcher m = URL.matcher(line);
      while (m.find()) {
        // Same trailing-punctuation strip pendingRegistration() does,
        // for the same reason: prose wrapping puts a full stop or a
        // closing angle bracket right up against the URL.
        urls.add(m.group().replaceAll("[.,;:>)\\]]+$", ""));
      }
    }

    return new Notification(
        date,
        recipient == null ? "" : recipient,
        subject == null ? "" : subject,
        otp,
        List.copyOf(urls),
        body.toString().strip()
    );
  }

  /**
   * The most recent registration link Authelia has emitted, if any.
   *
   * <p>Returns empty when the notifier has never fired (a 0-byte file on
   * a fresh box) or when the file holds no URL at all.
   */
  private Optional<Pending> pendingRegistration() {
    return readNotificationBody().flatMap(body -> {
      Matcher m = URL.matcher(body);
      String last = null;
      while (m.find()) last = m.group();
      if (last == null) return Optional.empty();

      // Trailing punctuation from prose wrapping ("…/register.>" or a
      // sentence-ending period) is not part of the URL.
      last = last.replaceAll("[.,;:>)\\]]+$", "");

      // mtime is best-effort: when we read via docker exec there is no
      // stat. now() is honest — the file exists as of this call — and
      // the caller only uses it to decide whether to show "pending".
      Instant at = notificationMtime().orElseGet(Instant::now);
      return Optional.of(new Pending(last, at));
    });
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

    int viaPostgres = countRowsInCoreDb(table);
    if (viaPostgres >= 0) return viaPostgres;
    return countRowsInSqlite(table);
  }

  /**
   * Count in the shared core-db, where Authelia's storage lives since the
   * Postgres migration.
   *
   * <p>This is the whole reason the SSO step said "Waiting for the SSO
   * service to finish starting" forever on a freshly-installed box: the
   * only lookup was {@code data/authelia/db.sqlite3}, a file that no
   * longer exists, so {@code autheliaUp} was permanently false and the
   * enrolment link never appeared — on the one screen whose job is to stop
   * every gated app being unopenable.
   *
   * <p>Read through {@code docker exec core-db psql}, as the in-container
   * superuser: no JDBC driver, no second copy of the password, and it
   * cannot reach anything the docker socket did not already reach.
   * Returns -1 when the answer is unknown (container down, psql missing,
   * schema not created yet), which the caller reads as "cannot see
   * Authelia" rather than "no factors".
   */
  private int countRowsInCoreDb(String table) {
    var out = docker.execCapture(CORE_DB_CONTAINER,
        "psql", "-U", "postgres", "-d", AUTHELIA_DB_NAME, "-tAc",
        "select count(*) from " + table);
    if (out.isEmpty()) return -1;
    String text = new String(out.get().stdout(), StandardCharsets.UTF_8).trim();
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      log.debug("unexpected psql output counting {}: {}", table, text);
      return -1;
    }
  }

  /** Pre-Postgres boxes, and the unit tests that stage a SQLite file. */
  private int countRowsInSqlite(String table) {
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
