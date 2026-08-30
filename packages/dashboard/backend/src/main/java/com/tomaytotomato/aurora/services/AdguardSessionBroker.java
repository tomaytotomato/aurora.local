package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aurora's side of AdGuard's SSO story.
 *
 * <p><b>The problem.</b> AdGuard Home has its own admin login. Aurora
 * already provisions AdGuard's yaml with the owner's own bcrypt so
 * one password logs into both, but the operator still sees TWO login
 * screens: Authelia first, then AdGuard. When the manifest declares
 * {@code sso.protect: true}, Aurora would inject {@code import authelia}
 * into the vhost and the second login screen becomes the friction the
 * whole SSO story was supposed to remove.
 *
 * <p><b>The fix (Option 2 in the design discussion).</b> Aurora owns a
 * dedicated {@code aurora-broker} admin on AdGuard whose password Aurora
 * knows in plaintext (it generates it). On demand, Aurora logs in as
 * that broker and hands the resulting session cookie back to Caddy via
 * a small controller endpoint. Caddy stitches that cookie into the
 * Authelia-verified request before proxying to AdGuard, so the operator
 * sees one login (Authelia's) and never notices AdGuard has one of its
 * own. The human admin ({@code sarah} etc.) stays in the yaml so a
 * direct-to-LAN-IP recovery path still works.
 *
 * <p><b>What this service does.</b>
 *
 * <ol>
 *   <li>On {@link ApplicationReadyEvent}, if the {@code privacy} package
 *       is enabled: make sure {@code AdGuardHome.yaml} has an
 *       {@code aurora-broker} user with the bcrypt of the password
 *       Aurora stores in {@code packages/privacy/.env} as
 *       {@code ADGUARD_BROKER_PASSWORD}. Generates the password + hash
 *       if the env slot is empty. Never rotates once populated \u2014
 *       rotation is a deliberate operation, not a boot side effect.</li>
 *   <li>Yaml surgery, not overwrite. Adds ONE {@code users:} entry;
 *       leaves everything else in the file (the human admin, blocklists,
 *       rewrites, DNS config) untouched.</li>
 *   <li>Exposes {@link #currentSessionCookie()} which returns a valid
 *       {@code agh_session=...} cookie value. Logs the broker in via
 *       {@code POST /control/login} when there's no cached cookie or
 *       when the cached one has expired.</li>
 * </ol>
 *
 * <p><b>What this service does NOT do.</b>
 *
 * <ul>
 *   <li>Restart the AdGuard container. Adding a user to the yaml
 *       requires an AdGuard restart to take effect; Aurora writes the
 *       change and logs a warning that AdGuard needs a restart. On a
 *       fresh install {@link AdguardProvisionService} runs first and
 *       renders the yaml with the broker user already present, so no
 *       restart is needed on the happy path.</li>
 *   <li>Talk to Authelia. That layer sits in Caddy in front of
 *       {@code /api/apps/adguard/session-cookie}. This service only
 *       knows how to talk to AdGuard's own control API.</li>
 * </ul>
 */
@Service
public class AdguardSessionBroker {

  private static final Logger log = LoggerFactory.getLogger(AdguardSessionBroker.class);

  /** Broker username inside AdGuard. Never presented to end users. */
  static final String BROKER_USERNAME = "aurora-broker";

  /** Env key in {@code packages/privacy/.env}. */
  static final String BROKER_PASSWORD_ENV = "ADGUARD_BROKER_PASSWORD";

  /** How long we trust a cached cookie for before re-logging in. */
  static final Duration COOKIE_MAX_AGE = Duration.ofHours(23);

  private static final Pattern KEY_LINE = Pattern.compile(
      "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$"
  );

  /**
   * AdGuard's Set-Cookie header for a successful login carries
   * {@code agh_session=<hex>; Path=/; Expires=<+1yr>; HttpOnly; SameSite=Lax}.
   * We only need the {@code name=value} pair to hand back to Caddy;
   * everything else is response-side metadata.
   */
  private static final Pattern AGH_COOKIE = Pattern.compile(
      "(?i)agh_session=([A-Fa-f0-9]+)"
  );

  private final AuroraProperties props;
  private final AuditEventRepo audit;
  private final StateFileService stateFiles;
  private final CommandRunner commands;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final SecureRandom rng = new SecureRandom();
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .build();

  /**
   * AdGuard control API base URL as reachable inside aurora_net.
   * Overridable for tests / non-default topologies.
   */
  private final String adguardBase;

  private volatile String cachedCookie;
  private volatile Instant cachedAt = Instant.EPOCH;

  @Autowired
  public AdguardSessionBroker(AuroraProperties props, AuditEventRepo audit,
                              StateFileService stateFiles, CommandRunner commands) {
    this(props, audit, stateFiles, commands, "http://adguard:3000");
  }

  /** Constructor for tests: injectable base URL. */
  AdguardSessionBroker(AuroraProperties props, AuditEventRepo audit,
                       StateFileService stateFiles, CommandRunner commands,
                       String adguardBase) {
    this.props = props;
    this.audit = audit;
    this.stateFiles = stateFiles;
    this.commands = commands;
    this.adguardBase = adguardBase;
  }

  // \u2500\u2500\u2500 lifecycle \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    Thread.ofVirtual().name("adguard-broker-startup").start(this::ensureBrokerQuietly);
  }

  @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT5M")
  public void reconcile() {
    ensureBrokerQuietly();
  }

  /**
   * Ensure the broker admin exists on AdGuard's side (in the yaml) and
   * that Aurora knows its plaintext password (in the env). Package-private
   * for tests. Never throws.
   */
  synchronized void ensureBrokerQuietly() {
    try {
      var enabled = stateFiles.readState().enabled();
      if (enabled == null || !enabled.contains("privacy")) return;

      Path confPath = confPath();
      String yaml = readYamlViaDocker();
      if (yaml == null) {
        log.debug("adguard broker: could not read {} via docker exec; will retry", confPath);
        return;
      }
      boolean yamlHasBroker = yaml.contains("name: " + BROKER_USERNAME);

      Path envPath = envPath();
      if (!Files.isRegularFile(envPath)) {
        log.debug("adguard broker: packages/privacy/.env not present yet; will retry");
        return;
      }
      List<String> envLines = new java.util.ArrayList<>(
          Files.readAllLines(envPath, StandardCharsets.UTF_8));
      String existingPw = readValue(envLines, BROKER_PASSWORD_ENV);
      boolean envHasBroker = existingPw != null && !existingPw.isBlank();

      if (yamlHasBroker && envHasBroker) return; // idempotent no-op

      String password = envHasBroker ? existingPw : generatePassword();
      String hash = encoder.encode(password);

      if (!yamlHasBroker) {
        String updated = insertBrokerUser(yaml, BROKER_USERNAME, hash);
        if (!writeYamlViaDocker(updated)) {
          log.warn("adguard broker: could not write {} via docker exec; will retry", confPath);
          return;
        }
        log.warn("adguard broker: added {} to AdGuard's yaml; container needs a restart "
            + "to load the new credential", BROKER_USERNAME);
      }
      if (!envHasBroker) {
        upsertLine(envLines, BROKER_PASSWORD_ENV, password);
        Files.writeString(envPath, String.join("\n", envLines) + "\n",
            StandardCharsets.UTF_8);
        audit.record(null, "adguard.broker.provision",
            "packages/privacy/.env",
            "{\"user\":\"" + BROKER_USERNAME + "\"}");
        log.info("adguard broker: wrote {} to packages/privacy/.env",
            BROKER_PASSWORD_ENV);
      }
      // Invalidate the cache so the next lookup logs in with fresh creds.
      cachedCookie = null;
    } catch (Exception e) {
      log.debug("adguard broker: startup/reconcile skipped: {}", e.toString());
    }
  }

  // \u2500\u2500\u2500 public API \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /**
   * Current valid session cookie value ({@code agh_session=<hex>}), or
   * empty when the broker cannot log in \u2014 which is a real error state
   * the caller should surface as a 503 to Caddy, not paper over.
   */
  public Optional<String> currentSessionCookie() {
    String cached = cachedCookie;
    if (cached != null && Duration.between(cachedAt, Instant.now()).compareTo(COOKIE_MAX_AGE) < 0) {
      return Optional.of(cached);
    }
    return refreshCookie();
  }

  synchronized Optional<String> refreshCookie() {
    try {
      Path envPath = envPath();
      if (!Files.isRegularFile(envPath)) return Optional.empty();
      String password = readValue(Files.readAllLines(envPath, StandardCharsets.UTF_8),
          BROKER_PASSWORD_ENV);
      if (password == null || password.isBlank()) return Optional.empty();

      String body = "{\"name\":\"" + BROKER_USERNAME + "\",\"password\":"
          + mapper.writeValueAsString(password) + "}";
      HttpRequest req = HttpRequest.newBuilder(URI.create(adguardBase + "/control/login"))
          .timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.debug("adguard broker: login returned HTTP {}", resp.statusCode());
        return Optional.empty();
      }
      Optional<String> setCookie = resp.headers().firstValue("Set-Cookie");
      if (setCookie.isEmpty()) return Optional.empty();
      Matcher m = AGH_COOKIE.matcher(setCookie.get());
      if (!m.find()) return Optional.empty();
      String cookieValue = "agh_session=" + m.group(1);
      cachedCookie = cookieValue;
      cachedAt = Instant.now();
      return Optional.of(cookieValue);
    } catch (Exception e) {
      log.debug("adguard broker: cookie refresh failed: {}", e.toString());
      return Optional.empty();
    }
  }

  // \u2500\u2500\u2500 yaml surgery \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /**
   * Insert a second {@code users:} entry into an existing AdGuard yaml.
   * Deliberately dumb string-level surgery rather than full-fat yaml
   * round-tripping: the yaml is Aurora-authored ({@link AdguardProvisionService})
   * with a stable shape, we only need to add two lines, and a real yaml
   * parser would rewrite comments + whitespace the operator might have
   * added by hand. Package-private for tests.
   *
   * <p>Handles both starting shapes: a {@code users:} block ending on
   * the next top-level key, and (belt-and-braces) a completely absent
   * {@code users:} block \u2014 which is a corrupt AdGuard yaml but we'll
   * do our best rather than fail.
   */
  static String insertBrokerUser(String yaml, String username, String bcryptHash) {
    if (yaml.contains("name: " + username)) return yaml; // already there

    String toInsert = "  - name: " + username + "\n"
        + "    password: " + bcryptHash + "\n";

    if (!yaml.contains("users:")) {
      // No users block at all. Append one at the end.
      return yaml + (yaml.endsWith("\n") ? "" : "\n") + "users:\n" + toInsert;
    }
    // Find `users:` at start of a line, then walk forward until we hit
    // a line that is NOT indented (blank line, or new top-level key).
    var lines = new java.util.ArrayList<>(List.of(yaml.split("\n", -1)));
    int insertAt = -1;
    boolean inUsers = false;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (!inUsers && line.matches("^users:\\s*$")) {
        inUsers = true;
        continue;
      }
      if (inUsers) {
        // Stop before the first non-indented, non-empty line.
        if (!line.startsWith(" ") && !line.startsWith("\t") && !line.isBlank()) {
          insertAt = i;
          break;
        }
      }
    }
    if (insertAt < 0) {
      // users: is at the tail of the file; append.
      lines.add(toInsert.stripTrailing());
    } else {
      lines.add(insertAt, toInsert.stripTrailing());
    }
    return String.join("\n", lines);
  }

  // \u2500\u2500\u2500 helpers \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /**
   * Read AdGuard's yaml via {@code docker exec cat}. The file is
   * root-owned inside the container so a bind-mount path can't read
   * it directly from Aurora — same trick SystemController uses for
   * Caddy's root CA. Returns null when the container is unreachable
   * or the file is missing.
   */
  private String readYamlViaDocker() {
    try {
      var out = new StringBuilder();
      int exit = commands.stream(null, java.util.Map.of(),
          List.of("docker", "exec", "adguard",
              "cat", "/opt/adguardhome/conf/AdGuardHome.yaml"),
          line -> { out.append(line).append('\n'); });
      if (exit != 0) return null;
      return out.toString();
    } catch (Exception e) {
      log.debug("adguard broker: read failed: {}", e.toString());
      return null;
    }
  }

  /**
   * Write AdGuard's yaml back via a bash pipe through {@code docker
   * exec}. Uses base64 to avoid quoting the yaml payload through the
   * argv boundary, and writes to a temp file then {@code mv}s into
   * place so a concurrent AdGuard read never sees a half-written file.
   */
  private boolean writeYamlViaDocker(String yaml) {
    try {
      String b64 = java.util.Base64.getEncoder()
          .encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
      String script = "set -e; "
          + "echo '" + b64 + "' | base64 -d "
          + "> /opt/adguardhome/conf/AdGuardHome.yaml.aurora-tmp; "
          + "mv /opt/adguardhome/conf/AdGuardHome.yaml.aurora-tmp "
          + "/opt/adguardhome/conf/AdGuardHome.yaml";
      int exit = commands.stream(null, java.util.Map.of(),
          List.of("docker", "exec", "adguard", "sh", "-c", script),
          line -> log.debug("adguard broker write: {}", line));
      return exit == 0;
    } catch (Exception e) {
      log.debug("adguard broker: write failed: {}", e.toString());
      return false;
    }
  }

  private Path confPath() {
    return Path.of(props.repoPath(), AdguardProvisionService.CONF_RELATIVE);
  }

  private Path envPath() {
    return Path.of(props.repoPath(), "packages", "privacy", ".env");
  }

  private String generatePassword() {
    byte[] buf = new byte[32];
    rng.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }

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

  private static void upsertLine(List<String> lines, String key, String value) {
    String rendered = key + "=" + value;
    for (int i = 0; i < lines.size(); i++) {
      Matcher m = KEY_LINE.matcher(lines.get(i));
      if (m.matches() && key.equals(m.group(1))) {
        lines.set(i, rendered);
        return;
      }
    }
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
}
