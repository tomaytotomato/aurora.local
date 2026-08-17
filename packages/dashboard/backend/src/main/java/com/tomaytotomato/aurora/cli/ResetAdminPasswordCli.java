package com.tomaytotomato.aurora.cli;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.services.AuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Break-glass admin account recovery.
 *
 * <p>Reached from {@link com.tomaytotomato.aurora.AuroraApplication#main}
 * when the first argument is {@code reset-admin-password} — see that
 * class for why the dispatch happens before {@code SpringApplication.run}.
 * That matters here: this class talks to the SQLite file directly with a
 * hand-rolled {@link DriverManagerDataSource} rather than the app's
 * Hikari-backed one, so it never starts the web server, never touches
 * docker.sock or D-Bus, and works whether the aurora container is the one
 * already running or a short-lived one started just for this.
 *
 * <p><b>Authorisation model:</b> there is no old-password check. Reaching
 * this class already requires either a shell on the box that can run
 * {@code docker exec}/{@code docker run} against the aurora image, or
 * being root on the host. Both of those already grant full control over
 * the container and its data — asking for the password you are trying to
 * recover in order to prove you're allowed to recover it would be
 * theatre, not security. The real access control is that this class is
 * never wired to a controller, so nothing on the HTTP side (dashboard,
 * API, onboarding wizard) can reach it.
 *
 * <p>It writes the password hash with the exact same {@link AuthService}
 * (same {@code BCryptPasswordEncoder} cost) and {@link AdminUserRepo}
 * (same SQL, same column mapping) the running app uses for login, so a
 * hash written here is guaranteed to verify against a normal login
 * attempt.
 *
 * <p>Never prints a password or a hash — only usernames, roles, ids, and
 * pass/fail outcomes. See {@code scripts/rotate-secrets.sh}'s "suggested
 * replacements" preview for the leak shape this deliberately avoids.
 */
public final class ResetAdminPasswordCli {

  static final int EXIT_OK = 0;
  static final int EXIT_USAGE = 1;
  static final int EXIT_NOT_FOUND = 2;
  static final int EXIT_DB_ERROR = 3;

  /** Mirrors {@code UsersService.validatePassword} so a break-glass reset can't set something the app itself would reject. */
  static final int MIN_PASSWORD_LENGTH = 12;

  private ResetAdminPasswordCli() {}

  public static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
    String dbPath = System.getenv().getOrDefault("AURORA_DB_PATH", "/data/aurora.db");
    return run(args, dbPath, stdin, out, err);
  }

  /**
   * Dispatch overload with an explicit DB path, split out from
   * {@link #run(String[], InputStream, PrintStream, PrintStream)} so
   * tests can point this at a scratch SQLite file instead of the real
   * {@code AURORA_DB_PATH} environment variable.
   */
  static int run(String[] args, String dbPath, InputStream stdin, PrintStream out, PrintStream err) {
    if (args.length == 0) {
      err.println(usage());
      return EXIT_USAGE;
    }

    // Anything below this point touches the SQLite file. A homelab box's
    // single-writer database can occasionally be mid-transaction with the
    // real app when this runs (see scripts/reset-admin-password.sh's
    // docker-exec path) — that surfaces as a locked-database error, not a
    // bug in this tool. Catch broadly so an operator gets one plain
    // sentence and a "try again" instead of a Java stack trace; the
    // exception message here is always driver/SQL text, never anything
    // derived from the password.
    try {
      AdminUserRepo repo = openRepo(dbPath);
      return switch (args[0]) {
        case "list" -> list(repo, out);
        case "reset" -> {
          if (args.length != 2 || args[1].isBlank()) {
            err.println("usage: reset-admin-password reset <username>   (new password read from stdin, one line)");
            yield EXIT_USAGE;
          }
          yield reset(repo, args[1], stdin, out, err);
        }
        default -> {
          err.println(usage());
          yield EXIT_USAGE;
        }
      };
    } catch (RuntimeException e) {
      err.println("error talking to " + dbPath + ": " + e.getMessage());
      err.println("if this says the database is locked, the running app was mid-request — wait a moment and try again");
      return EXIT_DB_ERROR;
    }
  }

  private static String usage() {
    return """
        usage: java -jar aurora.jar reset-admin-password <list|reset> [username]
          list            show every user: id, username, role, created \
        (no secrets printed) — use this when the username itself is forgotten
          reset <user>    set a new password for <user>; the new password \
        is read as a single line from stdin, never from an argument""";
  }

  /**
   * A minimal, dependency-free {@link AdminUserRepo} pointed straight at
   * the SQLite file. No connection pool (this process runs one command
   * and exits) and no schema migration (an existing box's DB already has
   * the tables; a box that never finished onboarding has no admin to
   * reset and {@link #list} says so plainly).
   */
  static AdminUserRepo openRepo(String dbPath) {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.sqlite.JDBC");
    ds.setUrl("jdbc:sqlite:" + dbPath);
    return new AdminUserRepo(new JdbcTemplate(ds));
  }

  static int list(AdminUserRepo repo, PrintStream out) {
    List<AdminUser> all = repo.findAll();
    if (all.isEmpty()) {
      out.println("no users found in admin_user — the onboarding wizard creates the first admin on next visit");
      return EXIT_OK;
    }
    out.printf("%-6s %-24s %-6s %s%n", "ID", "USERNAME", "ROLE", "CREATED");
    for (AdminUser u : all) {
      out.printf("%-6d %-24s %-6s %s%n", u.id(), u.username(), u.role().wireName(), u.createdAt());
    }
    return EXIT_OK;
  }

  static int reset(AdminUserRepo repo, String username, InputStream stdin, PrintStream out, PrintStream err) {
    Optional<AdminUser> existing = repo.findByUsername(username);
    if (existing.isEmpty()) {
      err.println("no such user: " + username + " — run the 'list' subcommand to see known usernames");
      return EXIT_NOT_FOUND;
    }

    char[] password = readOneLine(stdin);
    try {
      if (password.length < MIN_PASSWORD_LENGTH) {
        err.println("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        return EXIT_USAGE;
      }

      // AuthService needs an AdminUserRepo to construct (for authenticate());
      // hash() itself is side-effect-free and doesn't touch the repo, so
      // reusing the one we already opened is just convenience, not a
      // second connection.
      AuthService auth = new AuthService(repo);
      String hash = auth.hash(password); // clears password under the hood
      password = null; // NOSONAR - already cleared by hash(); drop the reference too

      repo.updatePasswordHash(existing.get().id(), hash);
      out.println("password reset for '" + username + "' (role=" + existing.get().role().wireName() + ")");
      return EXIT_OK;
    } finally {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }
  }

  private static char[] readOneLine(InputStream stdin) {
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
      String line = reader.readLine();
      return line == null ? new char[0] : line.toCharArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
