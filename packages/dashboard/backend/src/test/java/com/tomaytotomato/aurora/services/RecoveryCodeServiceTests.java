package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The only way back into a box whose password is gone.
 *
 * <p>Before this, the admin step promised "use the password recovery option
 * on this screen", the option opened an apology, and the real answer was
 * scripts/reset-admin-password.sh over SSH — a terminal, for the single most
 * likely thing to go wrong with a password-only login.
 */
class RecoveryCodeServiceTests {

  private Map<String, String> store;
  private SettingsRepo settings;
  private AdminUserRepo users;
  private AuthService auth;
  private RecoveryCodeService svc;

  @BeforeEach
  void setUp() {
    store = new HashMap<>();
    settings = mock(SettingsRepo.class);
    when(settings.get(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
    org.mockito.Mockito.doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
        .when(settings).put(anyString(), anyString());

    users = mock(AdminUserRepo.class);
    auth = new AuthService(users);
    svc = new RecoveryCodeService(settings, users, auth, mock(AuditEventRepo.class));

    when(users.findByUsername("sarah")).thenReturn(Optional.of(
        new AdminUser(1L, "sarah", "$2a$12$whatever", "UTC", "2026-08-28T00:00:00Z", Role.ADMIN)));
    when(users.findByUsername("nobody")).thenReturn(Optional.empty());
  }

  @Test
  void issuesAReadableCodeAndStoresOnlyItsHash() {
    assertThat(svc.isIssued()).isFalse();

    String code = svc.issue();

    assertThat(code).matches("[a-z]+(-[a-z]+){5}");
    assertThat(svc.isIssued()).isTrue();
    // The plaintext must not be anywhere in what we persisted.
    assertThat(store.values()).noneMatch(v -> v.contains(code));
  }

  @Test
  void redeemingSetsANewPasswordAndReturnsAFreshCode() {
    String code = svc.issue();

    Optional<String> next = svc.redeem("sarah", code, "a-new-strong-password");

    assertThat(next).isPresent();
    assertThat(next.get()).isNotEqualTo(code);
    verify(users).updatePasswordHash(anyLong(), anyString());
  }

  @Test
  void aSpentCodeStopsWorkingImmediately() {
    String code = svc.issue();
    svc.redeem("sarah", code, "a-new-strong-password");

    assertThat(svc.redeem("sarah", code, "another-strong-password")).isEmpty();
  }

  @Test
  void toleratesHowPeopleActuallyTypeIt() {
    String code = svc.issue();
    String asTyped = "  " + code.replace('-', ' ').toUpperCase() + ". ";

    assertThat(svc.redeem("sarah", asTyped, "a-new-strong-password")).isPresent();
  }

  @Test
  void aWrongUsernameAndAWrongCodeAreIndistinguishable() {
    String code = svc.issue();

    // Both empty, both leaving the password alone: nothing here tells an
    // attacker which usernames exist.
    assertThat(svc.redeem("nobody", code, "a-new-strong-password")).isEmpty();
    assertThat(svc.redeem("sarah", "wrong-words-entirely-here-now-ok", "a-new-strong-password")).isEmpty();
    verify(users, never()).updatePasswordHash(anyLong(), anyString());
  }

  @Test
  void refusesToSetAPasswordTooShortToBeWorthSetting() {
    String code = svc.issue();

    assertThatThrownBy(() -> svc.redeem("sarah", code, "short"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withNoCodeIssuedThereIsNothingToRedeem() {
    assertThat(svc.redeem("sarah", "amber-brook-cedar-dawn-ember-fable", "a-new-strong-password"))
        .isEmpty();
  }

  @Test
  void normalisesTheShapesAnotesAppWillProduce() {
    assertThat(RecoveryCodeService.normalise("Amber Brook  Cedar-Dawn ember Fable."))
        .isEqualTo("amber-brook-cedar-dawn-ember-fable");
  }

  @Test
  void codesAreNotAllTheSame() {
    var seen = new java.util.HashSet<String>();
    for (int i = 0; i < 20; i++) seen.add(svc.issue());
    assertThat(seen).hasSizeGreaterThan(15);
    assertThat(List.copyOf(seen)).allMatch(c -> c.split("-").length == 6);
  }
}
