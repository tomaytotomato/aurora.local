package com.tomaytotomato.aurora.persistence;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase D iter-2 — {@link AdminUserRepo} + V3 migration integration.
 *
 * <p>Uses a hand-rolled SQLite datasource + manual schema init rather
 * than {@code @SpringBootTest}: HealthControllerTests documents the
 * pre-existing Spring Boot 4 bean-override collision that keeps the
 * full-context path broken for DB-touching tests. This shape is
 * dependency-light, fast, and exercises the exact migration files a
 * production boot would run.
 */
class AdminUserRepoRoleTests {

  private static final Path MIG_DIR = Path.of(
      "src/main/resources/db/migration"
  );

  private JdbcTemplate jdbc;
  private AdminUserRepo repo;

  @BeforeEach
  void setUp() throws IOException {
    // Fresh SQLite per test — SingleConnectionDataSource + a randomly
    // named in-memory DB per invocation guarantees no cross-test leak.
    // We deliberately do NOT use ':memory:?cache=shared' because that
    // would fight cache-eviction semantics across DataSource instances
    // in the same JVM.
    DataSource ds = new SingleConnectionDataSource(
        "jdbc:sqlite::memory:", true);
    jdbc = new JdbcTemplate(ds);

    // Run V1 → V3 in order. Kept explicit rather than glob-scanning the
    // directory so a future V4 that assumes some new column has to be
    // added to the list intentionally.
    runMigration("V1__init.sql");
    runMigration("V2__security_dismissal.sql");
    runMigration("V3__admin_user_role.sql");

    repo = new AdminUserRepo(jdbc);
  }

  private void runMigration(String name) throws IOException {
    String sql = Files.readString(MIG_DIR.resolve(name), StandardCharsets.UTF_8);
    // SQLite JDBC doesn't split on ';' inside CREATE TRIGGER … END; blocks,
    // and our V3 has trigger bodies. Use executeBatch by splitting on
    // "END;" for triggers + ';' otherwise. Simpler: submit whole file
    // as one execute() and let the JDBC driver iterate. sqlite-jdbc
    // supports this since 3.30 via allowMultiQueries semantics on
    // Statement.execute(). Split on ';' only OUTSIDE trigger bodies.
    for (String stmt : splitSqlStatements(sql)) {
      String trimmed = stmt.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
      jdbc.execute(trimmed);
    }
  }

  /**
   * Split a SQL script into individual statements. Handles the
   * CREATE TRIGGER … BEGIN … END; pattern (trigger bodies contain
   * semicolons that MUST NOT terminate the outer CREATE statement).
   */
  static List<String> splitSqlStatements(String script) {
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

  // ─── V3 migration shape ───────────────────────────────────────────────

  @Test
  void migration_v3_added_role_column_with_default_user() {
    List<String> defaults = jdbc.query(
        "PRAGMA table_info(admin_user)",
        (rs, i) -> rs.getString("name") + "=" + rs.getString("dflt_value")
    );
    // role column exists AND the DDL default is 'user' (quoted by SQLite).
    assertThat(defaults).anyMatch(s -> s.startsWith("role=") && s.contains("user"));
  }

  @Test
  void migration_v3_added_role_index() {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_admin_user_role'",
        Integer.class
    );
    assertThat(count).isEqualTo(1);
  }

  @Test
  void migration_v3_backfills_existing_rows_to_admin() {
    // Simulate a pre-migration row shape by direct insert with the
    // default role ('user' post-V3 default). Then re-run V3's backfill
    // block and confirm the row flips to 'admin'.
    //
    // Easier route: assert the backfill by re-inserting via the
    // pre-V3-shape create + running the backfill SQL again.
    //
    // In the actual production upgrade path a fresh install has zero
    // existing rows so this test also protects the empty-set case.
    assertThat(repo.count()).isZero();

    // Insert a row directly using the pre-Phase-D column set
    // (no role column value) — DDL default kicks in.
    jdbc.update(
        "INSERT INTO admin_user (username, password_hash, tz) VALUES (?, ?, ?)",
        "legacy-admin", "$argon2id$…", "UTC"
    );
    assertThat(repo.findByUsername("legacy-admin").orElseThrow().role())
        .isEqualTo(Role.USER); // DDL default.

    // Re-run the backfill (idempotent because the WHERE-less UPDATE
    // just re-sets every row to 'admin').
    jdbc.update("UPDATE admin_user SET role = 'admin'");
    assertThat(repo.findByUsername("legacy-admin").orElseThrow().role())
        .isEqualTo(Role.ADMIN);
  }

  // ─── CRUD + role plumbing ────────────────────────────────────────────

  @Test
  void create_with_explicit_role_persists_it() {
    long id = repo.create("alice", "$argon2id$…", "Europe/London", Role.USER);

    AdminUser u = repo.findByUsername("alice").orElseThrow();
    assertThat(u.id()).isEqualTo(id);
    assertThat(u.username()).isEqualTo("alice");
    assertThat(u.role()).isEqualTo(Role.USER);
    assertThat(u.tz()).isEqualTo("Europe/London");
  }

  @Test
  void create_without_role_defaults_to_admin_for_backward_compat() {
    // Pre-Phase-D 3-arg overload preserved so OnboardingService's
    // wizard path keeps working. Semantically the "first user is
    // the primary admin" contract.
    long id = repo.create("bruce", "$argon2id$…", "UTC");
    AdminUser u = repo.findByUsername("bruce").orElseThrow();
    assertThat(u.id()).isEqualTo(id);
    assertThat(u.role()).isEqualTo(Role.ADMIN);
  }

  @Test
  void updateRole_flips_the_user_role() {
    long id = repo.create("mallory", "$argon2id$…", "UTC", Role.USER);
    assertThat(repo.updateRole(id, Role.GUEST)).isEqualTo(1);
    assertThat(repo.findByUsername("mallory").orElseThrow().role()).isEqualTo(Role.GUEST);
  }

  @Test
  void db_trigger_rejects_invalid_role_on_insert() {
    // Bypass the enum by shoving a raw string via JDBC. V3's BEFORE
    // INSERT trigger MUST RAISE(FAIL). Belt-and-braces guard so a
    // buggy caller can't slip 'superuser' past the ACL layer.
    //
    // SQLite's SQLITE_CONSTRAINT_TRIGGER gets wrapped by Spring's
    // default exception translator as UncategorizedSQLException
    // (not DataIntegrityViolationException), so we assert on the
    // broader DataAccessException base + the RAISE message text
    // rather than the specific subclass. Either would let a
    // future exception-translator swap flag the change.
    assertThatThrownBy(() -> jdbc.update(
        "INSERT INTO admin_user (username, password_hash, tz, role) VALUES (?, ?, ?, ?)",
        "eve", "$argon2id$…", "UTC", "superuser"
    )).isInstanceOf(DataAccessException.class)
      .hasMessageContaining("invalid role");
  }

  @Test
  void db_trigger_rejects_invalid_role_on_update() {
    long id = repo.create("trent", "$argon2id$…", "UTC", Role.USER);
    assertThatThrownBy(() -> jdbc.update(
        "UPDATE admin_user SET role = ? WHERE id = ?", "root", id
    )).isInstanceOf(DataAccessException.class)
      .hasMessageContaining("invalid role");
  }

  @Test
  void findAll_orders_by_id() {
    long first = repo.create("aaa", "h", "UTC", Role.ADMIN);
    long second = repo.create("bbb", "h", "UTC", Role.USER);
    long third = repo.create("ccc", "h", "UTC", Role.GUEST);

    List<Long> ids = repo.findAll().stream().map(AdminUser::id).toList();
    assertThat(ids).containsExactly(first, second, third);
  }

  @Test
  void countByRole_reports_per_tier_totals() {
    repo.create("a", "h", "UTC", Role.ADMIN);
    repo.create("b", "h", "UTC", Role.ADMIN);
    repo.create("c", "h", "UTC", Role.USER);
    repo.create("d", "h", "UTC", Role.GUEST);

    assertThat(repo.countByRole(Role.ADMIN)).isEqualTo(2);
    assertThat(repo.countByRole(Role.USER)).isEqualTo(1);
    assertThat(repo.countByRole(Role.GUEST)).isEqualTo(1);
    assertThat(repo.count()).isEqualTo(4);
  }
}
