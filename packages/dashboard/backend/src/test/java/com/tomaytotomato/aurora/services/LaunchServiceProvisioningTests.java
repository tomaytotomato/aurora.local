package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wizard's own launch path has to prepare a package the same way
 * {@code scripts/up.sh} does.
 *
 * <p>Both cases here are regressions found by installing a box, not by
 * reading code. AdGuard provisioning was added, tested, and then silently
 * disabled in production because {@code @Autowired} sat on a
 * backwards-compatible constructor that passed {@code null} for the
 * provisioner — so the first-run wizard brought up an AdGuard with no
 * config and the box had no DNS, which is the exact failure the
 * provisioning existed to prevent.
 */
class LaunchServiceProvisioningTests {

  private static AuroraProperties props(Path repo) {
    return new AuroraProperties(repo.toString(), "/proc", null, null);
  }

  @Test
  void springWiresTheConstructorThatCarriesTheProvisioner() {
    Constructor<?> annotated = null;
    for (Constructor<?> c : LaunchService.class.getConstructors()) {
      if (c.isAnnotationPresent(Autowired.class)) annotated = c;
    }

    assertThat(annotated)
        .as("exactly one constructor must be @Autowired")
        .isNotNull();
    assertThat(annotated.getParameterTypes())
        .as("the wired constructor must receive AdguardProvisionService; "
            + "a shim that drops it disables provisioning in production only")
        .contains(AdguardProvisionService.class);
  }

  @Test
  void aLaunchIncludingPrivacyProvisionsAdguardBeforeTheContainersStart(@TempDir Path repo)
      throws Exception {
    stageFakeUpSh(repo);
    var adguard = mock(AdguardProvisionService.class);
    when(adguard.provisionIfAbsent()).thenReturn(true);

    var svc = new LaunchService(props(repo), mock(AuditEventRepo.class), null, null,
        new ProcessCommandRunner(), null, adguard);

    svc.startLaunch(List.of("core", "privacy"));

    // Before the containers: AdGuard reads its config at start, so a write
    // after `up` would take effect only on the next restart.
    verify(adguard, timeout(2000)).provisionIfAbsent();
  }

  @Test
  void aLaunchWithoutPrivacyLeavesAdguardAlone(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo);
    var adguard = mock(AdguardProvisionService.class);

    var svc = new LaunchService(props(repo), mock(AuditEventRepo.class), null, null,
        new ProcessCommandRunner(), null, adguard);

    svc.startLaunch(List.of("core"));
    Thread.sleep(300);

    verify(adguard, never()).provisionIfAbsent();
  }

  private static void stageFakeUpSh(Path repo) throws Exception {
    Path scripts = repo.resolve("scripts");
    Files.createDirectories(scripts);
    Path up = scripts.resolve("up.sh");
    Files.writeString(up, "#!/usr/bin/env bash\necho ok\nexit 0\n");
    up.toFile().setExecutable(true);
  }
}
