package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * iter-28: {@link CurrentUserService} \u2014 SecurityContext \u2192 admin id
 * lookup for audit trails. Uses the real SecurityContextHolder because
 * that's the surface Spring integrates against; test hooks clean the
 * context between tests.
 */
class CurrentUserServiceTests {

  @BeforeEach @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static void setPrincipal(String name) {
    var auth = new UsernamePasswordAuthenticationToken(
        name, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @Test
  void currentUsername_returns_authenticated_principal() {
    AdminUserRepo repo = Mockito.mock(AdminUserRepo.class);
    setPrincipal("bruce");
    assertEquals(Optional.of("bruce"), new CurrentUserService(repo).currentUsername());
  }

  @Test
  void currentUsername_empty_when_unauthenticated() {
    AdminUserRepo repo = Mockito.mock(AdminUserRepo.class);
    // No context set.
    assertFalse(new CurrentUserService(repo).currentUsername().isPresent());
  }

  @Test
  void currentUsername_empty_for_anonymous_marker() {
    AdminUserRepo repo = Mockito.mock(AdminUserRepo.class);
    var anon = new AnonymousAuthenticationToken(
        "key", "anonymousUser",
        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    SecurityContextHolder.getContext().setAuthentication(anon);
    assertFalse(new CurrentUserService(repo).currentUsername().isPresent());
  }

  @Test
  void currentUserId_resolves_via_admin_repo() {
    AdminUserRepo repo = Mockito.mock(AdminUserRepo.class);
    Mockito.when(repo.findByUsername("bruce")).thenReturn(Optional.of(
        new AdminUser(42L, "bruce", "$argon2id$…", "UTC", "2026-01-01T00:00:00Z")));
    setPrincipal("bruce");
    assertEquals(Optional.of(42L), new CurrentUserService(repo).currentUserId());
  }

  @Test
  void currentUserId_empty_when_admin_row_absent() {
    AdminUserRepo repo = Mockito.mock(AdminUserRepo.class);
    Mockito.when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
    setPrincipal("ghost");
    assertFalse(new CurrentUserService(repo).currentUserId().isPresent());
  }

  @Test
  void currentUserId_empty_when_unauthenticated() {
    AdminUserRepo repo = Mockito.mock(AdminUserRepo.class);
    // No context set → repo never queried.
    assertFalse(new CurrentUserService(repo).currentUserId().isPresent());
    Mockito.verifyNoInteractions(repo);
  }
}
