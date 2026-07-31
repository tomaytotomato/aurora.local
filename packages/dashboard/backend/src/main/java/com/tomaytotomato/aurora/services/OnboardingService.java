package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates the first-run wizard. Two ways this is "done":
 * <ol>
 *   <li>An admin user has been created — most auth-related endpoints unlock.</li>
 *   <li>The {@code onboarding.complete} setting is true — the SPA shows the
 *       normal app shell instead of the wizard.</li>
 * </ol>
 * These are separate so a user who creates an admin but abandons the wizard
 * still gets back into it on the next visit.
 */
@Service
public class OnboardingService {

  private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);
  private static final String KEY_COMPLETE  = "onboarding.complete";
  private static final String KEY_STEP      = "onboarding.step";
  private static final String KEY_DNS_MODE  = "onboarding.dns_mode";

  private static final java.util.Set<String> VALID_DNS_MODES =
      java.util.Set.of("adguard", "router", "mdns");

  private static final java.util.Set<String> VALID_STEPS = java.util.Set.of(
      "welcome", "admin", "domain", "packages", "secrets", "dns", "tls", "review", "done"
  );

  private final AdminUserRepo users;
  private final AuditEventRepo audit;
  private final SettingsRepo settings;
  private final AuthService auth;
  private final StateFileService stateFiles;
  private final PackagesService packages;
  private final AuroraProperties props;

  public OnboardingService(AdminUserRepo users, AuditEventRepo audit, SettingsRepo settings,
                           AuthService auth, StateFileService stateFiles,
                           PackagesService packages, AuroraProperties props) {
    this.users = users;
    this.audit = audit;
    this.settings = settings;
    this.auth = auth;
    this.stateFiles = stateFiles;
    this.packages = packages;
    this.props = props;
  }

  /** True when there is no admin user yet — onboarding endpoints are unauthenticated. */
  public boolean isBootstrapMode() {
    return users.count() == 0L;
  }

  public boolean isComplete() {
    return settings.get(KEY_COMPLETE).map("true"::equals).orElse(false);
  }

  public String currentStep() {
    return settings.get(KEY_STEP).orElse("welcome");
  }

  public java.util.Optional<String> dnsMode() {
    return settings.get(KEY_DNS_MODE);
  }

  /**
   * Full draft the SPA hydrates from on load. Never returns password hash or
   * any other secret; only what the wizard needs to prefill its forms.
   */
  public java.util.Map<String, Object> summary() {
    var state = stateFiles.readState();
    String adminUsername = users.findFirst()
        .map(com.tomaytotomato.aurora.domain.AdminUser::username)
        .orElse(null);
    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("complete", isComplete());
    m.put("bootstrap_mode", isBootstrapMode());
    m.put("step", currentStep());
    m.put("admin_username", adminUsername);
    m.put("domain", state.domain());
    m.put("enabled_packages", state.enabled() == null ? List.of() : state.enabled());
    m.put("dns_mode", dnsMode().orElse(null));
    return m;
  }

  /**
   * Apply a partial update to the wizard draft. Any {@code null} field is
   * left untouched. Requires an admin to exist and onboarding to not be
   * complete — same guard as the legacy POST field routes.
   *
   * <p>The {@code step} field is advisory: the client tells the server
   * "user is on step X" so a resume-from-another-device UX has a hint.
   * The server never rejects a step value; the client URL is the real cursor.
   */
  public java.util.Map<String, Object> patch(PatchDraft p) {
    guardMidOnboarding();
    if (p.domain() != null)          setDomain(p.domain());
    if (p.enabledPackages() != null) setEnabledPackages(p.enabledPackages());
    if (p.dnsMode() != null)         setDnsMode(p.dnsMode());
    if (p.step() != null)            setStepHint(p.step());
    return summary();
  }

  public void setDnsMode(String mode) {
    if (mode == null) throw new IllegalArgumentException("dns_mode required");
    String v = mode.trim().toLowerCase();
    if (!VALID_DNS_MODES.contains(v)) {
      throw new IllegalArgumentException("dns_mode must be one of: adguard, router, mdns");
    }
    settings.put(KEY_DNS_MODE, v);
    audit.record(null, "onboarding.dns.set", "dns:" + v, null);
    // Advance the server-side step cursor forward but never backward.
    if (rank(currentStep()) < rank("tls")) settings.put(KEY_STEP, "tls");
  }

  public void setStepHint(String step) {
    if (step == null || step.isBlank()) return;
    String s = step.trim().toLowerCase();
    if (!VALID_STEPS.contains(s)) return; // ignore junk hints silently
    settings.put(KEY_STEP, s);
  }

  /**
   * Guard mutations that require the admin to already exist and onboarding
   * not to be complete. Thrown as {@link IllegalStateException} so the
   * controller maps it to 409 CONFLICT.
   */
  public void guardMidOnboarding() {
    if (isBootstrapMode()) {
      throw new IllegalStateException("create an admin user first via POST /api/onboarding/admin");
    }
    if (isComplete()) {
      throw new IllegalStateException("onboarding already complete; use authenticated endpoints");
    }
  }

  private static int rank(String step) {
    return switch (step == null ? "" : step) {
      case "welcome"  -> 0;
      case "admin"    -> 1;
      case "domain"   -> 2;
      case "packages" -> 3;
      case "secrets"  -> 4;
      case "dns"      -> 5;
      case "tls"      -> 6;
      case "review"   -> 7;
      case "done"     -> 8;
      default          -> -1;
    };
  }

  /** Draft payload for {@link #patch(PatchDraft)}. Null fields are no-ops. */
  public record PatchDraft(
      String domain,
      List<String> enabledPackages,
      String dnsMode,
      String step
  ) {}

  private static String firstWord(String s) {
    if (s == null) return "";
    String t = s.trim();
    int i = t.indexOf(' ');
    return i < 0 ? t : t.substring(0, i);
  }

  // ------------------------------------------------------------------
  // Install
  // ------------------------------------------------------------------

  /**
   * Apply the wizard draft. In v0.1 this means:
   *   1. Ensure {@code core} is in the enabled set (it's required).
   *   2. Write .state.yml (already done by earlier PATCHes, this is idempotent).
   *   3. Report which packages have containers up vs. which need to be started.
   * It does <b>not</b> spawn containers itself — aurora's container image
   * does not carry the docker CLI or the compose plugin, so bringing new
   * services up is delegated to {@code scripts/up.sh} on the host.
   *
   * <p>Does not mark onboarding complete; the client calls {@code /complete}
   * separately once the user has read the summary on the Done screen.
   */
  public java.util.Map<String, Object> install() {
    guardMidOnboarding();

    var applied = new java.util.ArrayList<String>();

    // 1. Force core on. Anything relying on ".enabled contains 'core'" is
    // guaranteed to hold after this call.
    var state = stateFiles.readState();
    var enabled = new java.util.ArrayList<>(state.enabled() == null ? List.of() : state.enabled());
    if (!enabled.contains("core")) {
      enabled.add(0, "core");
      stateFiles.writeEnabled(enabled);
      applied.add("Added core to enabled_packages (was missing; core is required).");
    } else {
      applied.add("core is enabled.");
    }
    applied.add("Wrote .state.yml with " + enabled.size() + " package"
        + (enabled.size() == 1 ? "" : "s") + ".");
    applied.add("Wrote packages/core/.env DOMAIN=" + (state.domain() == null ? "aurora.local" : state.domain()) + ".");

    // 2. Diff enabled vs. running so the Done screen can tell the user
    // which packages still need `scripts/up.sh`.
    var toStart = packages.enabledNotRunning();
    var toStop  = packages.runningNotEnabled();

    audit.record(null, "onboarding.install", null,
        "{\"enabled\":" + toJsonArray(enabled)
         + ",\"to_start\":" + toJsonArray(toStart)
         + ",\"to_stop\":"  + toJsonArray(toStop) + "}");

    var out = new java.util.LinkedHashMap<String, Object>();
    out.put("applied", applied);
    out.put("packages_to_start", toStart);
    out.put("packages_to_stop", toStop);   // v0.1 never actually stops — informational
    out.put("host_command", "cd ~/aurora.local && ./scripts/up.sh");
    return out;
  }

  /**
   * Compute a preview of what "Install" would do. Read-only: reads the
   * enabled set from .state.yml, cross-references package manifests for
   * ports, and derives probable vhosts from port descriptions + the
   * naming convention (subdomain == container name).
   *
   * <p>vhost derivation is a best-effort surface — the authoritative list
   * lives in Caddy's rendered config. The Review screen frames it as "what
   * you should expect to see," not a guarantee.
   */
  public java.util.Map<String, Object> plan() {
    var state = stateFiles.readState();
    List<String> enabled = state.enabled() == null ? List.of() : state.enabled();
    String domain = state.domain();

    var enabledManifests = packages.list().stream()
        .filter(p -> enabled.contains(p.name()))
        .toList();

    // Ports: aggregate from every enabled manifest. TreeSet to sort + dedup.
    var ports = new java.util.TreeSet<Integer>();
    for (var pkg : enabledManifests) {
      for (var entry : pkg.ports()) {
        Object p = entry.get("port");
        if (p instanceof Number n) ports.add(n.intValue());
      }
    }

    // vhosts: convention is <service>.<domain> where <service> matches a
    // container_name in the package compose. We derive from port descriptions
    // because manifests don't list vhosts explicitly; single-word descriptions
    // ("Sonarr", "Radarr") are treated as subdomains, and generic infra labels
    // ("Caddy HTTP", "AdGuard DNS", etc.) are dropped.
    var vhosts = new java.util.TreeSet<String>();
    if (domain != null && !domain.isBlank()) {
      if (enabled.contains("core")) {
        vhosts.add(domain);
        vhosts.add("admin." + domain);
      }
      for (var pkg : enabledManifests) {
        if ("core".equals(pkg.name())) continue;
        for (var entry : pkg.ports()) {
          Object desc = entry.get("description");
          Object portNum = entry.get("port");
          Object proto = entry.get("proto");
          if (desc == null) continue;
          if (proto != null && !"tcp".equalsIgnoreCase(proto.toString())) continue;
          if (portNum instanceof Number n && NON_HTTP_PORTS.contains(n.intValue())) continue;
          String sub = firstWord(desc.toString()).toLowerCase();
          if (sub.isEmpty()) continue;
          if (sub.contains("http")) continue;               // "HTTP", "HTTPS" labels
          if (NON_HTTP_SUBDOMAINS.contains(sub)) continue;
          if (!sub.matches("[a-z0-9][a-z0-9-]*")) continue; // sanity
          vhosts.add(sub + "." + domain);
        }
      }
    }

    // Warnings: light static checks. Real posture engine ships in v0.2.
    var warnings = new java.util.ArrayList<String>();
    if (enabled.contains("media") && !enabled.contains("privacy")) {
      warnings.add("media selected without privacy — torrent traffic will not route through Gluetun VPN.");
    }
    String dns = dnsMode().orElse(null);
    if ("adguard".equals(dns) && !enabled.contains("privacy")) {
      warnings.add("DNS mode is 'adguard' but the privacy package (which provides AdGuard Home) is not selected.");
    }
    if (domain == null || domain.isBlank()) {
      warnings.add("Domain not set — vhosts cannot be rendered until you complete step 3.");
    }
    if (enabled.isEmpty()) {
      warnings.add("No packages selected. Core is required and will be forced on at install.");
    } else if (!enabled.contains("core")) {
      warnings.add("Core is not in the enabled set but is required — it will be added at install.");
    }

    var out = new java.util.LinkedHashMap<String, Object>();
    out.put("packages_to_enable", enabled);
    out.put("packages_to_disable", List.of());   // v0.1: no diff engine
    out.put("vhosts", new java.util.ArrayList<>(vhosts));
    out.put("ports", new java.util.ArrayList<>(ports));
    out.put("warnings", warnings);
    return out;
  }

  // Ports/descriptions that look like a subdomain but aren't fronted by
  // Caddy — they're L4/UDP/infra services with no HTTP UI. Keep the list
  // small and explicit; adding false positives is worse than missing one.
  private static final java.util.Set<String> NON_HTTP_SUBDOMAINS = java.util.Set.of(
      "caddy", "samba", "smb", "minidlna", "upnp", "ssdp",
      "dns", "dhcp", "gluetun", "vpn", "mqtt", "mosquitto"
  );
  private static final java.util.Set<Integer> NON_HTTP_PORTS = java.util.Set.of(
      53,   // DNS
      67, 68,   // DHCP
      137, 138, 139, 445, // NetBIOS / SMB
      1883, 8883,         // MQTT
      1900, 5353,         // SSDP / mDNS
      8200                // MiniDLNA
  );

  /**
   * Create the initial admin. Refuses if an admin already exists.
   * @return the newly-created id
   */
  public long createInitialAdmin(String username, String password, String tz) {
    if (!isBootstrapMode()) {
      throw new IllegalStateException("admin already exists; onboarding is closed");
    }
    if (username == null || username.isBlank()) throw new IllegalArgumentException("username required");
    if (password == null || password.length() < 12) throw new IllegalArgumentException("password must be at least 12 chars");
    String hash = auth.hash(password.toCharArray());
    long id = users.create(username.trim(), hash, tz == null || tz.isBlank() ? "UTC" : tz);
    audit.record(id, "onboarding.admin.create", "admin_user:" + id, null);
    settings.put(KEY_STEP, "domain");
    return id;
  }

  /** Update .state.yml + packages/core/.env's DOMAIN. */
  public void setDomain(String domain) {
    if (domain == null || domain.isBlank()) throw new IllegalArgumentException("domain required");
    stateFiles.writeDomain(domain.trim());
    upsertCoreEnvDomain(domain.trim());
    audit.record(null, "onboarding.domain.set", "domain:" + domain, null);
    // Advance the server-side step cursor forward but never backward.
    if (rank(currentStep()) < rank("packages")) settings.put(KEY_STEP, "packages");
  }

  public void setEnabledPackages(List<String> enabled) {
    stateFiles.writeEnabled(enabled == null ? List.of() : enabled);
    audit.record(null, "onboarding.packages.set", null,
        "{\"enabled\":" + toJsonArray(enabled) + "}");
    if (rank(currentStep()) < rank("secrets")) settings.put(KEY_STEP, "secrets");
  }

  public void markComplete() {
    settings.put(KEY_COMPLETE, "true");
    settings.put(KEY_STEP, "done");
    audit.record(null, "onboarding.complete", null, null);
  }

  // --- .env mutation (v0.1: only the DOMAIN key in packages/core/.env) ---

  private static final Pattern DOMAIN_LINE = Pattern.compile("^\\s*DOMAIN\\s*=.*$");

  private void upsertCoreEnvDomain(String domain) {
    Path env = Path.of(props.repoPath()).resolve("packages/core/.env");
    try {
      List<String> lines = Files.exists(env)
          ? new ArrayList<>(Files.readAllLines(env, StandardCharsets.UTF_8))
          : new ArrayList<>();
      boolean replaced = false;
      for (int i = 0; i < lines.size(); i++) {
        Matcher m = DOMAIN_LINE.matcher(lines.get(i));
        if (m.matches()) {
          lines.set(i, "DOMAIN=" + domain);
          replaced = true;
          break;
        }
      }
      if (!replaced) lines.add("DOMAIN=" + domain);
      String body = String.join("\n", lines) + "\n";
      Files.createDirectories(env.getParent());
      Files.writeString(env, body, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      try {
        Files.setPosixFilePermissions(env,
            java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                             java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
      } catch (UnsupportedOperationException | IOException ignore) { /* non-posix fs */ }
    } catch (IOException e) {
      throw new RuntimeException("failed to write " + env, e);
    }
  }

  private static String toJsonArray(List<String> xs) {
    if (xs == null || xs.isEmpty()) return "[]";
    var sb = new StringBuilder("[");
    for (int i = 0; i < xs.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(xs.get(i).replace("\"", "\\\"")).append('"');
    }
    return sb.append(']').toString();
  }
}
