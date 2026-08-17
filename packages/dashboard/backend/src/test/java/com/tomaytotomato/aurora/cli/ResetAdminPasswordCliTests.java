package com.tomaytotomato.aurora.cli;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the break-glass admin password reset against a real, on-disk
 * SQLite file in a temp directory — the same shape
 * {@code scripts/reset-admin-password.sh} points a real box's
 * {@code /data/aurora.db} at, just relocated.
 *
 * <p>Migration approach mirrors {@code AdminUserRepoRoleTests}: run the
 * real V1/V3 migration files rather than hand-writing DDL, so this stays
 * honest about the schema a production box actually has. Uses a file
 * (not {@code :memory:}) because {@link ResetAdminPasswordCli#openRepo}
 * opens a fresh {@link DriverManagerDataSource} per call — an in-memory
 * DB would go blank between the migration connection and the one the CLI
 * opens.
 */
class ResetAdminPasswordCliTests {

  private static final Path MIG_DIR = Path.of("src/main/resources/db/migration");

  /** Matches {@code AuthService.BCRYPT_COST} — asserted independently below. */
  private static final int BCRYPT_COST = 12;

  @TempDir
  Path tempDir;

  private String dbPath;
  private AdminUserRepo repo;

  @BeforeEach
  void setUp() throws IOException {
    dbPath = tempDir.resolve("aurora.db").toString();

    DriverManagerDataSource migrationDs = new DriverManagerDataSource();
    migrationDs.setDriverClassName("org.sqlite.JDBC");
    migrationDs.setUrl("jdbc:sqlite:" + dbPath);
    JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDs);
    runMigration(migrationJdbc, "V1__init.sql");
    runMigration(migrationJdbc, "V3__admin_user_role.sql");

    repo = ResetAdminPasswordCli.openRepo(dbPath);
  }

  private static void runMigration(JdbcTemplate jdbc, String name) throws IOException {
    String sql = Files.readString(MIG_DIR.resolve(name), StandardCharsets.UTF_8);
    for (String stmt : splitSqlStatements(sql)) {
      String trimmed = stmt.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
      jdbc.execute(trimmed);
    }
  }

  /** Copied from {@code AdminUserRepoRoleTests} — handles CREATE TRIGGER ... END; bodies. */
  private static List<String> splitSqlStatements(String script) {
    var out = new java.util.ArrayList<String>();
    var buf = new StringBuilder();
    boolean inTrigger = false;
    for (String line : script.split("\\R")) {
      String t = line.trim();
      if (t.startsWith("--")) continue;
      buf.append(line).append('\n');
      String upper = t.toUpperCase();
      if (upper.startsWith("CREATE TRIGGER")) inTrigger = true;
      if (inTrigger) {
        if (upper.endsWith("END;")) {
          out.add(buf.toString());
          buf.setLength(0);
          inTrigger = false;
        }
      } else if (t.endsWith(";")) {
        out.add(buf.toString());
        buf.setLength(0);
      }
    }
    if (!buf.toString().trim().isEmpty()) out.add(buf.toString());
    return out;
  }

  private static PrintStream out(ByteArrayOutputStream buf) {
    return new PrintStream(buf, true, StandardCharsets.UTF_8);
  }

  private static java.io.InputStream stdinOf(String line) {
    return new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  class HashCompatibility {

    @Test
    void reset_writes_a_hash_that_a_fresh_bcrypt_encoder_at_the_configured_cost_verifies() {
      repo.create("bruce", "old-hash-irrelevant", "UTC", Role.ADMIN);

      ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
      ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.reset(repo, "bruce",
          stdinOf("a-brand-new-password"), out(outBuf), out(errBuf));

      assertThat(code).isEqualTo(0);
      String hash = repo.findByUsername("bruce").orElseThrow().passwordHash();

      // Independent verification: a BCryptPasswordEncoder built here, not
      // borrowed from AuthService, at the exact cost AuthService uses
      // (BCRYPT_COST = 12). This is the proof that AuthService.verify()
      // will accept a password reset by this tool on a real login.
      assertThat(new BCryptPasswordEncoder(BCRYPT_COST).matches("a-brand-new-password", hash)).isTrue();

      // BCrypt encodes its own cost in the hash string ($2a$12$...) — a
      // belt-and-braces check that we didn't silently drift onto a
      // different cost.
      assertThat(hash).startsWith("$2a$12$");
    }

    @Test
    void reset_never_writes_the_plaintext_password_to_stdout_or_stderr() {
      repo.create("bruce", "old-hash", "UTC", Role.ADMIN);

      ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
      ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
      ResetAdminPasswordCli.reset(repo, "bruce",
          stdinOf("super-secret-recovery-password"), out(outBuf), out(errBuf));

      String stdout = outBuf.toString(StandardCharsets.UTF_8);
      String stderr = errBuf.toString(StandardCharsets.UTF_8);
      assertThat(stdout).doesNotContain("super-secret-recovery-password");
      assertThat(stderr).doesNotContain("super-secret-recovery-password");

      String hash = repo.findByUsername("bruce").orElseThrow().passwordHash();
      assertThat(stdout).doesNotContain(hash);
      assertThat(stderr).doesNotContain(hash);
    }
  }

  @Nested
  class ResetSubcommand {

    @Test
    void resets_the_named_users_password_and_leaves_role_untouched() {
      long id = repo.create("alice", "old-hash", "Europe/London", Role.USER);

      int code = ResetAdminPasswordCli.reset(repo, "alice",
          stdinOf("a-perfectly-fine-password"), out(new ByteArrayOutputStream()), out(new ByteArrayOutputStream()));

      assertThat(code).isEqualTo(0);
      AdminUser updated = repo.findByUsername("alice").orElseThrow();
      assertThat(updated.id()).isEqualTo(id);
      assertThat(updated.role()).isEqualTo(Role.USER); // unchanged — reset never touches role
      assertThat(updated.passwordHash()).isNotEqualTo("old-hash");
    }

    @Test
    void unknown_username_fails_clearly_without_creating_a_row() {
      ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.reset(repo, "ghost",
          stdinOf("whatever-password-here"), out(new ByteArrayOutputStream()), out(errBuf));

      assertThat(code).isEqualTo(2);
      assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("no such user").contains("list");
      assertThat(repo.count()).isZero();
    }

    @Test
    void short_password_is_rejected_before_touching_the_row() {
      repo.create("bruce", "old-hash", "UTC", Role.ADMIN);

      ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.reset(repo, "bruce",
          stdinOf("short"), out(new ByteArrayOutputStream()), out(errBuf));

      assertThat(code).isEqualTo(1);
      assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("at least 12");
      assertThat(repo.findByUsername("bruce").orElseThrow().passwordHash()).isEqualTo("old-hash");
    }
  }

  @Nested
  class ListSubcommand {

    @Test
    void lists_every_user_with_role_but_never_a_password_hash() {
      repo.create("bruce", "$2a$12$somehash", "UTC", Role.ADMIN);
      repo.create("guestuser", "$2a$12$otherhash", "UTC", Role.GUEST);

      ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.list(repo, out(outBuf));

      assertThat(code).isEqualTo(0);
      String printed = outBuf.toString(StandardCharsets.UTF_8);
      assertThat(printed).contains("bruce").contains("admin");
      assertThat(printed).contains("guestuser").contains("guest");
      assertThat(printed).doesNotContain("somehash").doesNotContain("otherhash");
    }

    @Test
    void empty_table_prints_a_helpful_message_instead_of_an_empty_list() {
      ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.list(repo, out(outBuf));

      assertThat(code).isEqualTo(0);
      assertThat(outBuf.toString(StandardCharsets.UTF_8)).contains("no users found");
    }

    @Test
    void multiple_admins_all_appear_so_an_operator_can_pick_which_one_to_reset() {
      repo.create("bruce", "h1", "UTC", Role.ADMIN);
      repo.create("backup-admin", "h2", "UTC", Role.ADMIN);

      ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
      ResetAdminPasswordCli.list(repo, out(outBuf));

      String printed = outBuf.toString(StandardCharsets.UTF_8);
      assertThat(printed).contains("bruce").contains("backup-admin");
    }
  }

  @Nested
  class FullDispatch {

    @Test
    void run_with_no_args_prints_usage_and_exits_nonzero() {
      ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.run(new String[0], dbPath,
          stdinOf(""), out(new ByteArrayOutputStream()), out(errBuf));

      assertThat(code).isEqualTo(1);
      assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("usage");
    }

    @Test
    void run_reset_end_to_end_through_the_dispatch_switch() {
      repo.create("bruce", "old-hash", "UTC", Role.ADMIN);

      ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.run(new String[] {"reset", "bruce"}, dbPath,
          stdinOf("a-new-recovery-password"), out(outBuf), out(new ByteArrayOutputStream()));

      assertThat(code).isEqualTo(0);
      assertThat(outBuf.toString(StandardCharsets.UTF_8)).contains("password reset for 'bruce'");
      assertThat(repo.findByUsername("bruce").orElseThrow().passwordHash()).isNotEqualTo("old-hash");
    }

    @Test
    void run_reset_without_a_username_is_a_usage_error() {
      ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.run(new String[] {"reset"}, dbPath,
          stdinOf(""), out(new ByteArrayOutputStream()), out(errBuf));

      assertThat(code).isEqualTo(1);
      assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("usage");
    }

    @Test
    void run_with_unknown_subcommand_prints_usage() {
      ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
      int code = ResetAdminPasswordCli.run(new String[] {"bogus"}, dbPath,
          stdinOf(""), out(new ByteArrayOutputStream()), out(errBuf));

      assertThat(code).isEqualTo(1);
      assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("usage");
    }
  }
}
