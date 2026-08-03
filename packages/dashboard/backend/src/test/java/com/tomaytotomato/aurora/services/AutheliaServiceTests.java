package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.events.UserChangedEvent;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D iter-3 \u2014 {@link AutheliaService} projector.
 *
 * <p>Hermetic: no Spring context, no real Authelia, no docker. The
 * projector only needs {@link AdminUserRepo} (mocked) + a filesystem
 * ({@link TempDir}). Every test writes to a per-test temp dir so
 * atomic-rename semantics are exercised for real.
 *
 * <p>Groups covered:
 * <ul>
 *   <li>Role \u2192 groups cascade (admin/users/guests membership).</li>
 *   <li>YAML shape: {@code users:} block, per-user entry with
 *       {@code displayname}, {@code password}, {@code email},
 *       {@code groups}.</li>
 *   <li>Atomic write: no {@code .tmp} left behind after success.</li>
 *   <li>Empty user set still produces valid YAML.</li>
 *   <li>Reconcile updates {@code lastWriteAt} + clears {@code lastError}.</li>
 *   <li>Idempotence: two reconciles in a row produce identical file
 *       bytes so Authelia's watcher doesn't reload noisily.</li>
 * </ul>
 */
class AutheliaServiceTests {

  @TempDir Path repoRoot;
  private AdminUserRepo repo;
  private AutheliaService svc;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(AdminUserRepo.class);
    AuroraProperties props = new AuroraProperties(
        repoRoot.toString(),
        "/proc",
        java.util.List.of(),
        new AuroraProperties.Docker("unix:///dev/null")
    );
    svc = new AutheliaService(repo, props);
  }

  // \u2500\u2500\u2500 renderYaml (pure function) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @Test
  void groupsFor_admin_cascades_to_all_tiers() {
    assertThat(AutheliaService.groupsFor(Role.ADMIN))
        .containsExactly("admins", "users", "guests");
  }

  @Test
  void groupsFor_user_cascades_to_users_and_guests() {
    assertThat(AutheliaService.groupsFor(Role.USER))
        .containsExactly("users", "guests");
  }

  @Test
  void groupsFor_guest_is_just_guests() {
    assertThat(AutheliaService.groupsFor(Role.GUEST))
        .containsExactly("guests");
  }

  @Test
  void renderYaml_produces_parseable_authelia_shape() {
    List<AdminUser> users = List.of(
        new AdminUser(1, "bruce", "$argon2id$hash-bruce", "UTC", "2026-01-01T00:00:00Z", Role.ADMIN),
        new AdminUser(2, "alice", "$argon2id$hash-alice", "Europe/London",
            "2026-02-01T00:00:00Z", Role.USER)
    );
    String yaml = AutheliaService.renderYaml(users);
    // Reparse via SnakeYAML \u2014 belt-and-braces: guarantees Authelia
    // won't choke.
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) new Yaml().load(yaml);
    assertThat(root).containsKey("users");

    @SuppressWarnings("unchecked")
    Map<String, Object> usersBlock = (Map<String, Object>) root.get("users");
    assertThat(usersBlock.keySet()).containsExactly("bruce", "alice");

    @SuppressWarnings("unchecked")
    Map<String, Object> bruce = (Map<String, Object>) usersBlock.get("bruce");
    assertThat(bruce.get("displayname")).isEqualTo("Bruce");
    assertThat(bruce.get("password")).isEqualTo("$argon2id$hash-bruce");
    assertThat(bruce.get("email")).isEqualTo("bruce@aurora.local");
    @SuppressWarnings("unchecked")
    List<String> bruceGroups = (List<String>) bruce.get("groups");
    assertThat(bruceGroups).containsExactly("admins", "users", "guests");

    @SuppressWarnings("unchecked")
    Map<String, Object> alice = (Map<String, Object>) usersBlock.get("alice");
    assertThat(alice.get("displayname")).isEqualTo("Alice");
    @SuppressWarnings("unchecked")
    List<String> aliceGroups = (List<String>) alice.get("groups");
    assertThat(aliceGroups).containsExactly("users", "guests");
  }

  @Test
  void renderYaml_carries_a_regenerated_banner_comment() {
    // Operators peeking at the file must be told not to hand-edit.
    // If the banner ever gets accidentally removed, a well-meaning
    // manual paste to Authelia's users_database.yml gets nuked on the
    // next reconcile and support tickets stack up.
    String yaml = AutheliaService.renderYaml(List.of());
    assertThat(yaml).contains("REGENERATED automatically");
    assertThat(yaml).contains("AutheliaService");
  }

  @Test
  void renderYaml_empty_user_set_is_still_valid_yaml() {
    String yaml = AutheliaService.renderYaml(List.of());
    Object root = new Yaml().load(yaml);
    assertThat(root).isNotNull();
    // Authelia tolerates an empty users: {} block (auth just fails
    // for everyone \u2014 fail-closed, correct behaviour).
    @SuppressWarnings("unchecked")
    Map<String, Object> asMap = (Map<String, Object>) root;
    assertThat(asMap).containsKey("users");
  }

  // \u2500\u2500\u2500 atomicWrite \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @Test
  void atomicWrite_leaves_no_tmp_file_on_success(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("users_database.yml");
    AutheliaService.atomicWrite(target, "hello: world\n");
    assertThat(Files.readString(target)).isEqualTo("hello: world\n");
    // No sibling *.tmp \u2014 the rename cleared it.
    try (var listing = Files.list(dir)) {
      long tmps = listing.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
      assertThat(tmps).isZero();
    }
  }

  @Test
  void atomicWrite_replaces_existing_target(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("users_database.yml");
    Files.writeString(target, "old-contents\n");
    AutheliaService.atomicWrite(target, "new-contents\n");
    assertThat(Files.readString(target)).isEqualTo("new-contents\n");
  }

  // \u2500\u2500\u2500 reconcile end-to-end \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

  @Test
  void reconcile_writes_yaml_to_repo_relative_path() throws IOException {
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(1, "bruce", "$argon2id$hash", "UTC", "2026-01-01T00:00:00Z", Role.ADMIN)
    ));

    int n = svc.reconcile(UserChangedEvent.STARTUP);
    assertThat(n).isEqualTo(1);

    Path expected = repoRoot.resolve("data/identity/authelia/users_database.yml");
    assertThat(expected).isRegularFile();
    String yaml = Files.readString(expected, StandardCharsets.UTF_8);
    assertThat(yaml).contains("bruce:");
    assertThat(yaml).contains("$argon2id$hash");
    assertThat(yaml).contains("admins");
  }

  @Test
  void reconcile_creates_parent_directories_on_first_run() throws IOException {
    Mockito.when(repo.findAll()).thenReturn(List.of());
    assertThat(repoRoot.resolve("data/identity/authelia")).doesNotExist();

    svc.reconcile(UserChangedEvent.STARTUP);
    assertThat(repoRoot.resolve("data/identity/authelia/users_database.yml")).exists();
  }

  @Test
  void reconcile_updates_lastWriteAt_and_clears_lastError() {
    Mockito.when(repo.findAll()).thenReturn(List.of());
    assertThat(svc.lastWriteAt()).isNull();
    assertThat(svc.lastError()).isNull();

    svc.reconcile(UserChangedEvent.STARTUP);
    assertThat(svc.lastWriteAt()).isNotNull();
    assertThat(svc.lastError()).isNull();
  }

  @Test
  void reconcile_is_idempotent() throws IOException {
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(1, "bruce", "$argon2id$hash", "UTC", "2026-01-01T00:00:00Z", Role.ADMIN)
    ));

    svc.reconcile(UserChangedEvent.STARTUP);
    String first = Files.readString(svc.usersDbPath(), StandardCharsets.UTF_8);

    svc.reconcile(UserChangedEvent.RECONCILE);
    String second = Files.readString(svc.usersDbPath(), StandardCharsets.UTF_8);

    // Byte-for-byte identical \u2014 Authelia's file watcher can dedupe
    // via mtime alone. If a future change accidentally introduces
    // non-determinism (e.g. a timestamp inside the yaml), this test
    // screams before Authelia starts flapping-reloading every 5 min.
    assertThat(second).isEqualTo(first);
  }

  @Test
  void reconcile_handles_write_failure_by_setting_lastError() throws IOException {
    // Point the props at a location we can't write to (a file, not a
    // directory) so Files.createDirectories() fails.
    Path blocker = repoRoot.resolve("blocker");
    Files.writeString(blocker, "not-a-dir");
    AuroraProperties badProps = new AuroraProperties(
        blocker.toString(),  // \u2192 blocker/data/identity/authelia can't be created
        "/proc",
        java.util.List.of(),
        new AuroraProperties.Docker("unix:///dev/null")
    );
    AutheliaService badSvc = new AutheliaService(repo, badProps);
    Mockito.when(repo.findAll()).thenReturn(List.of());

    int n = badSvc.reconcile(UserChangedEvent.STARTUP);
    assertThat(n).isEqualTo(-1); // \u2192 error sentinel
    assertThat(badSvc.lastError()).isNotNull();
    assertThat(badSvc.lastWriteAt()).isNull(); // never got a successful write
  }
}
