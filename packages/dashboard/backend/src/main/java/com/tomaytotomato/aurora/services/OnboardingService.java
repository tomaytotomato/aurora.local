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
  private static final String KEY_COMPLETE = "onboarding.complete";
  private static final String KEY_STEP     = "onboarding.step";

  private final AdminUserRepo users;
  private final AuditEventRepo audit;
  private final SettingsRepo settings;
  private final AuthService auth;
  private final StateFileService stateFiles;
  private final AuroraProperties props;

  public OnboardingService(AdminUserRepo users, AuditEventRepo audit, SettingsRepo settings,
                           AuthService auth, StateFileService stateFiles, AuroraProperties props) {
    this.users = users;
    this.audit = audit;
    this.settings = settings;
    this.auth = auth;
    this.stateFiles = stateFiles;
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
    settings.put(KEY_STEP, "packages");
  }

  public void setEnabledPackages(List<String> enabled) {
    stateFiles.writeEnabled(enabled == null ? List.of() : enabled);
    audit.record(null, "onboarding.packages.set", null,
        "{\"enabled\":" + toJsonArray(enabled) + "}");
    settings.put(KEY_STEP, "review");
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
