package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * iter-29: audit attribution on LaunchService. Verifies that
 * onboarding.launch.start/finish audit rows carry the acting admin's id
 * when a CurrentUserService is wired (prod path) and null otherwise
 * (test / bootstrap path).
 */
class LaunchServiceAuditAttributionTests {

  private static AuroraProperties props(Path repo) {
    return new AuroraProperties(
        repo.toString(),
        "/host/proc",
        List.of(),
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
  }

  private static void stageFakeUpSh(Path repo, String body) throws IOException {
    Path scripts = repo.resolve("scripts");
    Files.createDirectories(scripts);
    Path up = scripts.resolve("up.sh");
    Files.writeString(up, "#!/usr/bin/env bash\n" + body);
    up.toFile().setExecutable(true);
  }

  private static void awaitTerminal(LaunchService.Job job) throws InterruptedException {
    for (int i = 0; i < 200 && job.state == LaunchService.State.RUNNING; i++) {
      Thread.sleep(50);
    }
  }

  @Test
  void audit_records_use_current_user_id_when_authenticated(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo, "exit 0\n");
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    CurrentUserService currentUser = Mockito.mock(CurrentUserService.class);
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.of(42L));

    LaunchService svc = new LaunchService(props(repo), audit, null, currentUser);
    LaunchService.Job job = svc.startLaunch(List.of("core"));
    awaitTerminal(job);

    // start + finish both attribute user 42.
    Mockito.verify(audit).record(eq(42L), eq("onboarding.launch.start"), anyString(), any());
    Mockito.verify(audit).record(eq(42L), eq("onboarding.launch.finish"), anyString(), any());
  }

  @Test
  void audit_records_null_user_when_no_current_user_service(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo, "exit 0\n");
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    // Test-only constructor path: currentUser stays null.
    LaunchService svc = new LaunchService(props(repo), audit);
    LaunchService.Job job = svc.startLaunch(List.of("core"));
    awaitTerminal(job);

    Mockito.verify(audit).record(eq(null), eq("onboarding.launch.start"), anyString(), any());
    Mockito.verify(audit).record(eq(null), eq("onboarding.launch.finish"), anyString(), any());
  }

  @Test
  void audit_records_null_user_when_unauthenticated(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo, "exit 0\n");
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    CurrentUserService currentUser = Mockito.mock(CurrentUserService.class);
    Mockito.when(currentUser.currentUserId()).thenReturn(Optional.empty());

    LaunchService svc = new LaunchService(props(repo), audit, null, currentUser);
    LaunchService.Job job = svc.startLaunch(List.of("core"));
    awaitTerminal(job);

    Mockito.verify(audit).record(eq(null), eq("onboarding.launch.start"), anyString(), any());
    Mockito.verify(audit).record(eq(null), eq("onboarding.launch.finish"), anyString(), any());
  }

  @Test
  void audit_records_null_user_when_current_user_throws(@TempDir Path repo) throws Exception {
    stageFakeUpSh(repo, "exit 0\n");
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    CurrentUserService currentUser = Mockito.mock(CurrentUserService.class);
    Mockito.when(currentUser.currentUserId()).thenThrow(new RuntimeException("db locked"));

    LaunchService svc = new LaunchService(props(repo), audit, null, currentUser);
    LaunchService.Job job = svc.startLaunch(List.of("core"));
    awaitTerminal(job);

    // Attribution failure must not knock the audit call \u2014 the id
    // falls back to null and the record still fires.
    Mockito.verify(audit).record(eq(null), eq("onboarding.launch.start"), anyString(), any());
    Mockito.verify(audit).record(eq(null), eq("onboarding.launch.finish"), anyString(), any());
  }

  @Test
  void currentUserId_helper_returns_null_when_service_null() {
    LaunchService svc = new LaunchService(
        props(java.nio.file.Path.of("/tmp/no-repo")),
        Mockito.mock(AuditEventRepo.class));
    assertEquals(null, svc.currentUserId());
  }
}
