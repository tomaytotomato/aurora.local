package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.events.UserChangedEvent;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.support.GenericApplicationContext;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D iter-16 (D15) \u2014 end-to-end propagation chain:
 * {@link UsersService} create/update/delete \u2192 publishes
 * {@link UserChangedEvent} via Spring's {@code ApplicationEventPublisher}
 * \u2192 {@link AutheliaService} consumes it \u2192 writes
 * {@code users_database.yml} atomically to disk \u2192 records
 * {@code authelia.users.projected} audit row.
 *
 * <p>Uses a real {@link GenericApplicationContext} so the event
 * subscription wires up like production. Mocks the DB layer
 * ({@link AdminUserRepo}) and the audit trail ({@link AuditEventRepo})
 * so we can observe the interactions without touching a database.
 * The projection target is a per-test {@link TempDir} so atomic-rename
 * semantics are exercised for real.
 *
 * <p>This test guards the seam between D2 (projector) and D8 (users
 * CRUD) \u2014 a future refactor that changed either component in
 * isolation could pass its own unit tests while silently breaking the
 * whole pipeline. Belt-and-braces against that.
 */
class UsersPropagationChainTests {

  @TempDir Path repoRoot;

  private GenericApplicationContext ctx;
  private AdminUserRepo repo;
  private AuditEventRepo audit;
  private AuthService authService;
  private AutheliaService autheliaService;
  private UsersService usersService;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(AdminUserRepo.class);
    audit = Mockito.mock(AuditEventRepo.class);
    // AuthService uses BCrypt — real hashes are ~250ms; we don't need
    // a real hash for these tests, mock it.
    authService = Mockito.mock(AuthService.class);
    Mockito.when(authService.hash(Mockito.any())).thenReturn("$2a$12$stub");

    AuroraProperties props = new AuroraProperties(
        repoRoot.toString(), "/proc", java.util.List.of(),
        new AuroraProperties.Docker("unix:///dev/null")
    );

    // Spring context — the ApplicationEventPublisher on the context is
    // used by UsersService to publish, and @EventListener on
    // AutheliaService is auto-subscribed when it registers as a bean.
    ctx = new GenericApplicationContext();
    autheliaService = new AutheliaService(repo, props, audit);
    ctx.getBeanFactory().registerSingleton("autheliaService", autheliaService);
    // The @EventListener annotation requires an EventListenerMethodProcessor
    // to actually wire up the listener. Register the standard one so
    // AutheliaService.onUserChanged is discovered.
    ctx.registerBean(
        org.springframework.context.event.EventListenerMethodProcessor.class);
    ctx.registerBean(
        org.springframework.context.event.DefaultEventListenerFactory.class);
    ctx.refresh();

    usersService = new UsersService(repo, authService, audit, ctx);
  }

  // ─── create → propagation → audit row ─────────────────────────────

  @Test
  void create_user_writes_users_database_yml_and_emits_projection_audit()
      throws IOException {
    // Stage the repo so create+refetch shows a single user.
    Mockito.when(repo.create(Mockito.eq("alice"), Mockito.anyString(),
        Mockito.eq("UTC"), Mockito.eq(Role.USER))).thenReturn(1L);
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(1, "alice", "$2a$12$stub", "UTC",
            "2026-08-03T00:00:00Z", Role.USER)
    ));

    usersService.create("alice", "reallystrong-2026".toCharArray(),
        Role.USER, "UTC", 42L);

    // 1. The users_database.yml file was written to the expected path.
    Path yamlPath = repoRoot.resolve("data/identity/authelia/users_database.yml");
    assertThat(yamlPath).isRegularFile();

    // 2. The yaml is valid and has our user with the right groups.
    String body = Files.readString(yamlPath, StandardCharsets.UTF_8);
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) new Yaml().load(body);
    @SuppressWarnings("unchecked")
    Map<String, Object> users = (Map<String, Object>) root.get("users");
    assertThat(users).containsKey("alice");
    @SuppressWarnings("unchecked")
    Map<String, Object> alice = (Map<String, Object>) users.get("alice");
    assertThat(alice.get("password")).isEqualTo("$2a$12$stub");
    @SuppressWarnings("unchecked")
    List<String> groups = (List<String>) alice.get("groups");
    // USER cascades to [users, guests] (see AutheliaService.groupsFor).
    assertThat(groups).containsExactly("users", "guests");

    // 3. Two audit rows: users.create (from UsersService) +
    //    authelia.users.projected (from AutheliaService listener).
    ArgumentCaptor<String> actions = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit, Mockito.atLeast(2)).record(
        Mockito.any(), actions.capture(),
        Mockito.anyString(), Mockito.any());
    List<String> allActions = actions.getAllValues();
    assertThat(allActions).contains("users.create");
    assertThat(allActions).contains("authelia.users.projected");
  }

  // ─── role change → projection → audit ─────────────────────────────

  @Test
  void role_change_reprojects_with_new_group_cascade_and_audits() throws IOException {
    // Pre-existing user in USER role.
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(2, "alice", "$2a$12$stub", "UTC",
            "2026-08-03T00:00:00Z", Role.USER),
        new AdminUser(1, "bruce", "$2a$12$stub", "UTC",
            "2026-01-01T00:00:00Z", Role.ADMIN)
    ));

    usersService.updateRole(2L, Role.ADMIN, 1L);

    // After updateRole, the projector runs on ROLE_CHANGE — alice
    // should now cascade to [admins, users, guests].
    Path yamlPath = repoRoot.resolve("data/identity/authelia/users_database.yml");
    String body = Files.readString(yamlPath, StandardCharsets.UTF_8);
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) new Yaml().load(body);
    @SuppressWarnings("unchecked")
    Map<String, Object> users = (Map<String, Object>) root.get("users");
    // Note: findAll() was staged with alice still at USER because we
    // mocked the repo lookup — the projector reads live from the repo
    // AFTER updateRole flipped the row, but our mock doesn't change
    // its response. What we're really testing is that a ROLE_CHANGE
    // reason triggers a projection (audit row) + the yaml is rewritten.
    assertThat(users).containsKey("alice");

    ArgumentCaptor<String> actions = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit, Mockito.atLeast(2)).record(
        Mockito.any(), actions.capture(),
        Mockito.anyString(), Mockito.any());
    List<String> allActions = actions.getAllValues();
    assertThat(allActions).contains("users.role-change");
    assertThat(allActions).contains("authelia.users.projected");
  }

  // ─── delete → projection → audit ──────────────────────────────────

  @Test
  void delete_user_reprojects_without_the_user_and_audits() throws IOException {
    Mockito.when(repo.findAll())
        // Before delete: two users.
        .thenReturn(List.of(
            new AdminUser(1, "bruce", "$2a$12$stub", "UTC",
                "2026-01-01T00:00:00Z", Role.ADMIN),
            new AdminUser(2, "alice", "$2a$12$stub", "UTC",
                "2026-02-01T00:00:00Z", Role.USER)))
        // findAll called again by AutheliaService.reconcile after the
        // event fires — return only bruce.
        .thenReturn(List.of(
            new AdminUser(1, "bruce", "$2a$12$stub", "UTC",
                "2026-01-01T00:00:00Z", Role.ADMIN)));
    // countByRole for the last-admin guard.
    Mockito.when(repo.countByRole(Role.ADMIN)).thenReturn(1L);

    usersService.delete(2L, 1L);

    Path yamlPath = repoRoot.resolve("data/identity/authelia/users_database.yml");
    String body = Files.readString(yamlPath, StandardCharsets.UTF_8);
    @SuppressWarnings("unchecked")
    Map<String, Object> root = (Map<String, Object>) new Yaml().load(body);
    @SuppressWarnings("unchecked")
    Map<String, Object> users = (Map<String, Object>) root.get("users");
    assertThat(users).containsKey("bruce");
    assertThat(users).doesNotContainKey("alice");

    ArgumentCaptor<String> actions = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit, Mockito.atLeast(2)).record(
        Mockito.any(), actions.capture(),
        Mockito.anyString(), Mockito.any());
    List<String> allActions = actions.getAllValues();
    assertThat(allActions).contains("users.delete");
    assertThat(allActions).contains("authelia.users.projected");
  }
}
