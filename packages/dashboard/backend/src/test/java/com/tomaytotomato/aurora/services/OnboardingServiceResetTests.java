package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link OnboardingService#reset()} (TD5, 2026-08-02).
 *
 * <p>Contract:
 * <ul>
 *   <li>Wipes admin users → onboarding settings → state file, in that
 *       order (admin cookies invalidated first so a stale request in
 *       flight loses auth before it can observe torn state).</li>
 *   <li>Deletes each onboarding.* key explicitly (complete, step,
 *       dns_mode). Leaves audit + other tenant-scoped settings alone.</li>
 *   <li>Records a single audit event so an accidental prod call is
 *       traceable.</li>
 *   <li>Idempotent — calling twice re-runs the same deletes without
 *       throwing, because every underlying repo op is DELETE-shaped
 *       (zero rows is fine) and {@link StateFileService#deleteState()}
 *       uses {@code Files.deleteIfExists}.</li>
 * </ul>
 */
class OnboardingServiceResetTests {

  private OnboardingService svc(AdminUserRepo users, SettingsRepo settings,
                                StateFileService stateFiles, AuditEventRepo audit) {
    // Minimal constructor set — matches the primary ctor that takes the
    // (users, audit, settings, auth, stateFiles, packages, system, props)
    // shape. auth/packages/system/props are not read by reset() so mocks
    // are enough. See OnboardingService constructor in main.
    return new OnboardingService(
        users, audit, settings,
        mock(AuthService.class),
        stateFiles,
        mock(PackagesService.class),
        mock(SystemService.class),
        new com.tomaytotomato.aurora.config.AuroraProperties(
            "/tmp/nonexistent-repo", "/proc",
            java.util.List.of(),
            new com.tomaytotomato.aurora.config.AuroraProperties.Docker("unix:///dev/null")));
  }

  @Test
  void reset_wipesAdminSettingsAndStateFile_inSafeOrder() {
    AdminUserRepo users = mock(AdminUserRepo.class);
    SettingsRepo settings = mock(SettingsRepo.class);
    StateFileService stateFiles = mock(StateFileService.class);
    AuditEventRepo audit = mock(AuditEventRepo.class);

    svc(users, settings, stateFiles, audit).reset();

    // Order matters: admin wipe first (invalidates auth), then settings,
    // then state file. An observer mid-request sees "not authenticated"
    // before it sees a nonsense wizard step.
    InOrder inOrder = Mockito.inOrder(users, settings, stateFiles, audit);
    inOrder.verify(users).deleteAll();
    inOrder.verify(settings).delete("onboarding.complete");
    inOrder.verify(settings).delete("onboarding.step");
    inOrder.verify(settings).delete("onboarding.dns_mode");
    inOrder.verify(stateFiles).deleteState();
    inOrder.verify(audit).record(null, "onboarding.reset", null, null);
  }

  @Test
  void reset_isIdempotent() {
    AdminUserRepo users = mock(AdminUserRepo.class);
    SettingsRepo settings = mock(SettingsRepo.class);
    StateFileService stateFiles = mock(StateFileService.class);
    AuditEventRepo audit = mock(AuditEventRepo.class);
    OnboardingService s = svc(users, settings, stateFiles, audit);

    s.reset();
    s.reset();

    // Two full pass-throughs; each verb hit exactly twice.
    verify(users, times(2)).deleteAll();
    verify(settings, times(2)).delete("onboarding.complete");
    verify(settings, times(2)).delete("onboarding.step");
    verify(settings, times(2)).delete("onboarding.dns_mode");
    verify(stateFiles, times(2)).deleteState();
    verify(audit, times(2)).record(null, "onboarding.reset", null, null);
  }

  @Test
  void reset_touchesOnlyOnboardingSettings_notOtherKeys() {
    AdminUserRepo users = mock(AdminUserRepo.class);
    SettingsRepo settings = mock(SettingsRepo.class);
    StateFileService stateFiles = mock(StateFileService.class);
    AuditEventRepo audit = mock(AuditEventRepo.class);

    svc(users, settings, stateFiles, audit).reset();

    // Explicitly assert the whitelist. If a future maintainer adds a
    // fourth onboarding.* key, this test will catch a missed deletion —
    // add the new key here and to reset() together.
    verify(settings).delete("onboarding.complete");
    verify(settings).delete("onboarding.step");
    verify(settings).delete("onboarding.dns_mode");
    Mockito.verifyNoMoreInteractions(settings);
  }
}
