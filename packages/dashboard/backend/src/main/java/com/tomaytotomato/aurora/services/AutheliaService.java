package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.events.UserChangedEvent;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase D iter-3 \u2014 project Aurora's users table into Authelia's
 * on-disk {@code users_database.yml}.
 *
 * <p><b>Why on-disk projection instead of an Authelia API call?</b>
 * Authelia's file-based auth backend reads {@code users_database.yml}
 * with {@code watch: true} (see {@code packages/identity/authelia/
 * configuration.yml}) so a fresh write triggers an automatic reload
 * inside the container. This means Aurora doesn't need HTTP round-trips
 * to Authelia + doesn't need to know Authelia's internal auth surface;
 * it just owns the file. Same pattern as how {@link MdnsAliasService}
 * owns the avahi-publish subprocess fleet.
 *
 * <p><b>Path.</b> The compose file mounts
 * {@code data/identity/authelia:/data}, so the file's canonical host
 * path is
 * {@code {aurora.repo-path}/data/identity/authelia/users_database.yml}.
 * Same as the mDNS service, we resolve via {@link AuroraProperties}.
 *
 * <p><b>Group mapping.</b> Aurora's {@link Role} enum projects into
 * Authelia groups with cascading membership:
 *
 * <pre>
 *   ADMIN  \u2192 [admins, users, guests]
 *   USER   \u2192 [users, guests]
 *   GUEST  \u2192 [guests]
 * </pre>
 *
 * <p>This lets Authelia access-control rules say
 * {@code subject: group:users} to mean "authenticated user or above"
 * without repeating the group list. See {@code configuration.yml}
 * access_control rules for the consumer side (finalised in D4).
 *
 * <p><b>Atomic write.</b> Authelia's watcher fires on file change;
 * a partial write would be caught mid-parse and could 500 login
 * attempts for a beat. Renders to a sibling {@code .tmp} in the same
 * directory then {@link Files#move Files.move} with
 * {@code ATOMIC_MOVE + REPLACE_EXISTING}.
 *
 * <p><b>Reconcile cadence.</b> On {@link ApplicationReadyEvent} + on
 * every {@link UserChangedEvent} + every 5 minutes as a drift guard
 * (someone manually edited {@code users_database.yml} \u2014 shouldn't
 * happen but Sarah might paste a wrong hash and be locked out; the
 * drift guard fixes it on the next tick).
 *
 * <p><b>Runtime contract.</b> Requires
 * {@code {repo}/data/identity/authelia/} to be writable by Aurora's
 * UID (usually 1000). Missing directory is created at first write.
 * If the write ever fails, we log and skip \u2014 Authelia keeps the
 * previous version, so a transient disk error doesn't lock everyone
 * out.
 */
@Service
public class AutheliaService {

  private static final Logger log = LoggerFactory.getLogger(AutheliaService.class);
  private static final String USERS_DB_FILENAME = "users_database.yml";

  private final AdminUserRepo users;
  private final AuroraProperties props;

  /** Last successful write timestamp, for the /api/authelia/status surface. */
  private final AtomicReference<Instant> lastWriteAt = new AtomicReference<>(null);
  /** Last error, cleared on the next successful write. */
  private final AtomicReference<String> lastError = new AtomicReference<>(null);

  public AutheliaService(AdminUserRepo users, AuroraProperties props) {
    this.users = users;
    this.props = props;
  }

  // \u2500\u2500\u2500 lifecycle \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    log.info("authelia projector: reconciling users_database.yml on startup");
    reconcile(UserChangedEvent.STARTUP);
  }

  /** Drift guard \u2014 rebuilds the file if someone tampered with it. */
  @Scheduled(fixedDelay = 5 * 60_000L, initialDelay = 5 * 60_000L)
  public void scheduledReconcile() {
    try {
      reconcile(UserChangedEvent.RECONCILE);
    } catch (Exception e) {
      log.warn("authelia scheduled reconcile threw: {}", e.getMessage());
    }
  }

  @EventListener(UserChangedEvent.class)
  public void onUserChanged(UserChangedEvent ev) {
    reconcile(ev.reason());
  }

  // \u2500\u2500\u2500 public API \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /**
   * Idempotent: reads the current users, renders yaml, writes atomically.
   * Returns the number of users written so callers / tests can assert.
   *
   * <p>Never throws. A projection failure logs + sets {@link #lastError()}
   * and returns {@code -1}; the next scheduled reconcile retries. Aurora
   * must not crash at startup because Authelia's file can't be written
   * — that'd take the whole dashboard down over an SSO-side hiccup.
   */
  public synchronized int reconcile(String reason) {
    Path target = usersDbPath();
    try {
      List<AdminUser> all = users.findAll();
      Files.createDirectories(target.getParent());
      String yaml = renderYaml(all);
      atomicWrite(target, yaml);
      lastWriteAt.set(Instant.now());
      lastError.set(null);
      log.info("authelia projector wrote {} user{} to {} (reason={})",
          all.size(), all.size() == 1 ? "" : "s", target, reason);
      return all.size();
    } catch (Exception e) {
      // Catch broadly — boot ordering can leave the DB unmigrated when
      // ApplicationReadyEvent fires (Spring Boot 4 / sql.init timing),
      // and callers upstream (D8 controllers) shouldn't have to catch a
      // SQL exception on top of an IO one. lastError surfaces on the
      // /api/authelia/status endpoint in D8 so operators still see it.
      lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
      log.warn("authelia projector failed to write {} (reason={}): {}",
          target, reason, e.getMessage());
      return -1;
    }
  }

  public Instant lastWriteAt() { return lastWriteAt.get(); }
  public String lastError() { return lastError.get(); }
  public Path usersDbPath() {
    return Path.of(props.repoPath(), "data", "identity", "authelia", USERS_DB_FILENAME);
  }

  // \u2500\u2500\u2500 rendering \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  /** Package-private for tests. */
  static String renderYaml(List<AdminUser> users) {
    var root = new LinkedHashMap<String, Object>();
    var usersBlock = new LinkedHashMap<String, Object>();
    for (AdminUser u : users) {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("displayname", displayName(u.username()));
      entry.put("password", u.passwordHash());
      entry.put("email", u.username() + "@aurora.local");
      entry.put("groups", groupsFor(u.role()));
      usersBlock.put(u.username(), entry);
    }
    root.put("users", usersBlock);

    DumperOptions opts = new DumperOptions();
    opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    opts.setIndent(2);
    opts.setPrettyFlow(true);
    // Split into a docstring header + yaml body so an operator peeking
    // at the file understands why it's regenerating itself.
    String header = String.join("\n",
        "# Authelia users database \u2014 projected from Aurora's SQLite users table.",
        "# This file is REGENERATED automatically on every user change and every 5",
        "# minutes as a drift guard. Manual edits WILL be overwritten on the next",
        "# projection. Edit users via the Aurora dashboard (/users) or the",
        "# /api/users endpoint instead.",
        "#",
        "# Managed by com.tomaytotomato.aurora.services.AutheliaService.",
        ""
    );
    return header + new Yaml(opts).dump(root);
  }

  /** Package-private for tests \u2014 the role \u2192 groups cascade. */
  static List<String> groupsFor(Role role) {
    return switch (role) {
      case ADMIN -> List.of("admins", "users", "guests");
      case USER -> List.of("users", "guests");
      case GUEST -> List.of("guests");
    };
  }

  private static String displayName(String username) {
    if (username == null || username.isBlank()) return "";
    // Title-case the first character. Homelab convention.
    return Character.toUpperCase(username.charAt(0)) + username.substring(1);
  }

  // \u2500\u2500\u2500 atomic write \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  static void atomicWrite(Path target, String contents) throws IOException {
    Path parent = target.getParent();
    Path tmp = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
    try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
      w.write(contents);
    }
    try {
      // ATOMIC_MOVE + REPLACE_EXISTING so Authelia's inotify watch sees
      // a single rename event and never a truncated file.
      Files.move(tmp, target,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      // Best-effort cleanup so we don't leak *.tmp on the disk.
      try { Files.deleteIfExists(tmp); } catch (IOException ignore) { /* ignore */ }
      throw e;
    }
  }
}
