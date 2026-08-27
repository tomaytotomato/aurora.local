package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.events.UserChangedEvent;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.AuthService;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.UsersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase D iter-9 (D8) \u2014 {@link UsersController} + {@link UsersService}
 * tests. Focuses on the two things the Phase D guardrail requires:
 *
 * <ul>
 *   <li>Every mutation guarded by {@code role == ADMIN} at the controller
 *       (401 unauthenticated, 403 authenticated-not-admin).</li>
 *   <li>Invariant guards inside the service (last-admin can't be
 *       demoted or deleted).</li>
 * </ul>
 *
 * <p>Uses Mockito-mocked collaborators \u2014 the pre-existing
 * bean-override collision under {@code @SpringBootTest} keeps the
 * full-context path broken, so we test at the class level with a
 * standalone controller instance. Same shape as {@code HealthControllerTests}.
 */
class UsersControllerTests {

  private AdminUserRepo repo;
  private AuthService auth;
  private AuditEventRepo audit;
  private ApplicationEventPublisher events;
  private CurrentUserService currentUser;

  private UsersService svc;
  private UsersController ctrl;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(AdminUserRepo.class);
    audit = Mockito.mock(AuditEventRepo.class);
    events = Mockito.mock(ApplicationEventPublisher.class);
    currentUser = Mockito.mock(CurrentUserService.class);
    // AuthService uses BCrypt with cost 12 — real hashes are slow (~250ms)
    // so tests that don't check the hash use a mock instead. Where we
    // do need a real hash (verify), we spin up a real AuthService.
    auth = Mockito.mock(AuthService.class);
    Mockito.when(auth.hash(Mockito.any())).thenReturn("$2a$12$stub-hash");

    svc = new UsersService(repo, auth, audit, events);
    ctrl = new UsersController(svc, currentUser);
  }

  // ─── admin-role guard ──────────────────────────────────────────────────

  @Test
  void unauthenticated_read_returns_401() {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.empty());
    assertThatThrownBy(ctrl::list)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void non_admin_authenticated_read_returns_403() {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.USER));
    assertThatThrownBy(ctrl::list)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));

    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.GUEST));
    assertThatThrownBy(ctrl::list)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void admin_read_succeeds_and_returns_users() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(1, "bruce", "hash", "UTC", "2026-01-01", Role.ADMIN),
        new AdminUser(2, "alice", "hash", "UTC", "2026-01-02", Role.USER)
    ));

    List<UsersService.UserSummary> out = ctrl.list();
    assertThat(out).extracting(UsersService.UserSummary::username)
        .containsExactly("bruce", "alice");
    assertThat(out.get(0).role()).isEqualTo(Role.ADMIN);
  }

  @Test
  void every_mutating_endpoint_calls_requireAdmin_first() {
    // If a future refactor forgets requireAdmin() on any endpoint,
    // the corresponding assertion here screams. Belt-and-braces
    // guard so the Phase-D task-spec rule can't silently regress.
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.USER));

    assertThatThrownBy(() -> ctrl.create(new UsersController.CreateReq(
        "alice", "reallystrong-2026", "user", "UTC")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> ctrl.update(1L,
        new UsersController.UpdateReq("admin", null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> ctrl.delete(1L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> ctrl.get(1L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));

    // Every mutation MUST NOT reach the service under a non-admin.
    Mockito.verifyNoInteractions(repo);
    Mockito.verifyNoInteractions(events);
  }

  // ─── create ────────────────────────────────────────────────────────────

  @Test
  void create_hashes_password_persists_and_emits_event() {
    stageAdmin();
    Mockito.when(repo.create(Mockito.eq("alice"), Mockito.eq("$2a$12$stub-hash"),
        Mockito.eq("UTC"), Mockito.eq(Role.USER))).thenReturn(2L);
    // findById() is implemented as findAll + filter; stage the follow-up read.
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(2, "alice", "$2a$12$stub-hash", "UTC", "2026-08-03", Role.USER)
    ));

    var response = ctrl.create(new UsersController.CreateReq(
        "alice", "reallystrong-2026", "user", null));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().user().username()).isEqualTo("alice");
    assertThat(response.getBody().user().role()).isEqualTo(Role.USER);
    // Caller supplied a password, so nothing is echoed back: we never
    // return a secret the client already holds.
    assertThat(response.getBody().generatedPassword()).isNull();

    // Password char[] handed to AuthService, hashed, then persisted.
    Mockito.verify(auth).hash(Mockito.any());
    Mockito.verify(repo).create("alice", "$2a$12$stub-hash", "UTC", Role.USER);
    // UserChangedEvent published so AutheliaService re-projects.
    ArgumentCaptor<UserChangedEvent> ev = ArgumentCaptor.forClass(UserChangedEvent.class);
    Mockito.verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo(UserChangedEvent.CREATE);
    // Audit row records the create.
    Mockito.verify(audit).record(Mockito.any(), Mockito.eq("users.create"),
        Mockito.eq("user:2"), Mockito.contains("\"role\":\"user\""));
  }

  @Test
  void create_rejects_bad_username_shape() {
    stageAdmin();
    assertThatThrownBy(() -> ctrl.create(new UsersController.CreateReq(
        "UPPERCASE-bad", "reallystrong-2026", "user", null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
    Mockito.verifyNoInteractions(repo);
  }

  @Test
  void create_rejects_weak_password_under_12_chars() {
    stageAdmin();
    assertThatThrownBy(() -> ctrl.create(new UsersController.CreateReq(
        "alice", "short", "user", null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void create_rejects_unknown_role() {
    stageAdmin();
    assertThatThrownBy(() -> ctrl.create(new UsersController.CreateReq(
        "alice", "reallystrong-2026", "superuser", null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void create_translates_duplicate_key_to_409() {
    stageAdmin();
    Mockito.when(repo.create(Mockito.anyString(), Mockito.anyString(),
        Mockito.anyString(), Mockito.any())).thenThrow(new DuplicateKeyException("boom"));

    assertThatThrownBy(() -> ctrl.create(new UsersController.CreateReq(
        "alice", "reallystrong-2026", "user", null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
  }

  // ─── update role ───────────────────────────────────────────────────────

  @Test
  void update_role_flips_and_emits_event() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(2, "alice", "hash", "UTC", "2026-01-02", Role.USER)
    ));

    ctrl.update(2L, new UsersController.UpdateReq("admin", null));

    Mockito.verify(repo).updateRole(2L, Role.ADMIN);
    ArgumentCaptor<UserChangedEvent> ev = ArgumentCaptor.forClass(UserChangedEvent.class);
    Mockito.verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo(UserChangedEvent.ROLE_CHANGE);
    Mockito.verify(audit).record(Mockito.any(), Mockito.eq("users.role-change"),
        Mockito.eq("user:2"), Mockito.contains("\"from\":\"user\",\"to\":\"admin\""));
  }

  @Test
  void update_role_last_admin_demote_returns_422() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(1, "bruce", "hash", "UTC", "2026-01-01", Role.ADMIN)
    ));
    Mockito.when(repo.countByRole(Role.ADMIN)).thenReturn(1L);

    assertThatThrownBy(() -> ctrl.update(1L, new UsersController.UpdateReq("user", null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    Mockito.verify(repo, Mockito.never()).updateRole(Mockito.anyLong(), Mockito.any());
    Mockito.verify(events, Mockito.never()).publishEvent(Mockito.any());
  }

  @Test
  void update_role_returns_404_for_unknown_id() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of());
    assertThatThrownBy(() -> ctrl.update(999L, new UsersController.UpdateReq("admin", null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
  }

  // ─── delete ────────────────────────────────────────────────────────────

  @Test
  void delete_wipes_row_and_emits_event() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(1, "bruce", "hash", "UTC", "2026-01-01", Role.ADMIN),
        new AdminUser(2, "alice", "hash", "UTC", "2026-01-02", Role.USER)
    ));

    var response = ctrl.delete(2L);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    Mockito.verify(repo).deleteById(2L);
    ArgumentCaptor<UserChangedEvent> ev = ArgumentCaptor.forClass(UserChangedEvent.class);
    Mockito.verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo(UserChangedEvent.DELETE);
  }

  @Test
  void delete_last_admin_returns_422() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(1, "bruce", "hash", "UTC", "2026-01-01", Role.ADMIN)
    ));
    Mockito.when(repo.countByRole(Role.ADMIN)).thenReturn(1L);

    assertThatThrownBy(() -> ctrl.delete(1L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    Mockito.verify(repo, Mockito.never()).deleteById(Mockito.anyLong());
    Mockito.verify(events, Mockito.never()).publishEvent(Mockito.any());
  }

  @Test
  void delete_returns_404_for_unknown_id() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of());
    assertThatThrownBy(() -> ctrl.delete(999L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
  }

  // ─── password rotation ─────────────────────────────────────────────────

  @Test
  void update_password_rotates_hash_and_emits_event() {
    stageAdmin();
    Mockito.when(repo.findAll()).thenReturn(List.of(
        new AdminUser(2, "alice", "old-hash", "UTC", "2026-01-02", Role.USER)
    ));

    ctrl.update(2L, new UsersController.UpdateReq(null, "reallystrong-2026-new"));

    Mockito.verify(auth).hash(Mockito.any());
    Mockito.verify(repo).updatePasswordHash(2L, "$2a$12$stub-hash");
    ArgumentCaptor<UserChangedEvent> ev = ArgumentCaptor.forClass(UserChangedEvent.class);
    Mockito.verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo(UserChangedEvent.PASSWORD_ROTATE);
    // Audit row for password rotation MUST NOT carry the new password.
    ArgumentCaptor<String> diff = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit).record(Mockito.any(), Mockito.eq("users.password-rotate"),
        Mockito.eq("user:2"), diff.capture());
    // diff is null for password rotates — never surface even a hash.
    assertThat(diff.getValue()).isNull();
  }

  // ─── helpers ───────────────────────────────────────────────────────────

  private void stageAdmin() {
    Mockito.when(currentUser.currentRole()).thenReturn(Optional.of(Role.ADMIN));
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
  }
}
