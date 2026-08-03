package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * iter-28 (v0.3 followup): resolve the currently authenticated admin's
 * database id for audit-trail attribution.
 *
 * <p>Design: {@link SecurityContextHolder} carries the username set by
 * {@link com.tomaytotomato.aurora.controllers.AuthController#login} as
 * the authentication principal name. The audit_event table records
 * {@code user_id} (long), so we look up the admin row by username. Kept
 * as a bean rather than a static helper so tests can mock the whole
 * lookup without wrestling with the SecurityContext.
 *
 * <p>Fails closed: unauthenticated / anonymous requests return empty
 * rather than raising. Callers still record the audit event with a null
 * {@code user_id} (matches the pre-attribution behaviour).
 */
@Service
public class CurrentUserService {

  private final AdminUserRepo admins;

  public CurrentUserService(AdminUserRepo admins) {
    this.admins = admins;
  }

  /** Resolved {@code admin_user.id} for the current request, or empty. */
  public Optional<Long> currentUserId() {
    return currentUsername().flatMap(name ->
        admins.findByUsername(name).map(com.tomaytotomato.aurora.domain.AdminUser::id));
  }

  /**
   * Principal username from the SecurityContext. Empty when the request
   * is unauthenticated or the principal is Spring's anonymous marker.
   */
  public Optional<String> currentUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) return Optional.empty();
    String name = auth.getName();
    if (name == null || name.isBlank() || "anonymousUser".equals(name)) return Optional.empty();
    return Optional.of(name);
  }
}
