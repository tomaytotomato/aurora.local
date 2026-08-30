package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Copying this box's downloads onto a NAS — storage arrangement 2 of 3
 * ("keep a copy on the NAS").
 */
class StorageMirrorServiceTests {

  private JobService jobs;
  private SettingsRepo settings;
  private Map<String, String> store;
  private StorageMirrorService svc;

  @BeforeEach
  void setUp() {
    jobs = mock(JobService.class);
    settings = mock(SettingsRepo.class);
    store = new HashMap<>();
    when(settings.get(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
    doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
        .when(settings).put(anyString(), anyString());
    svc = new StorageMirrorService(jobs, settings, mock(AuditEventRepo.class));
  }

  @SuppressWarnings("unchecked")
  private List<String> capturedArgv() {
    ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
    verify(jobs).submitCommand(any(), anyString(), any(), argv.capture());
    return argv.getValue();
  }

  @Test
  void copiesInsideAContainerSoNothingOnTheHostNeedsPrivilege() {
    svc.start("/home/bruce/media", "aurora_nas_x", false, "aurora:test");

    String cmd = String.join(" ", capturedArgv());
    assertThat(cmd).startsWith("docker run --rm");
    assertThat(cmd).contains("/home/bruce/media:/from");
    assertThat(cmd).contains("aurora_nas_x:/to");
  }

  @Test
  void readsTheSourceReadOnly_aCopyJobShouldNotWriteToTheLibrary() {
    svc.start("/home/bruce/media", "aurora_nas_x", false, "aurora:test");

    assertThat(String.join(" ", capturedArgv())).contains("/home/bruce/media:/from:ro");
  }

  @Test
  void doesNotDeleteAnythingUnlessAskedTo() {
    // Deleting files on the NAS because they are gone from here is exactly
    // what someone wants for a backup, and exactly what destroys an archive
    // the first time the local disk is wiped and a sync runs before anyone
    // notices.
    svc.start("/home/bruce/media", "aurora_nas_x", false, "aurora:test");

    assertThat(capturedArgv()).doesNotContain("--delete");
  }

  @Test
  void deletesOnlyWhenExplicitlyAsked() {
    svc.start("/home/bruce/media", "aurora_nas_x", true, "aurora:test");

    assertThat(capturedArgv()).contains("--delete");
  }

  @Test
  void resumesAnInterruptedCopyRatherThanStartingTheFileAgain() {
    // A first copy of a media library is measured in hours; losing a
    // half-transferred 40 GB file to a restart is not acceptable.
    svc.start("/home/bruce/media", "aurora_nas_x", false, "aurora:test");

    assertThat(capturedArgv()).contains("--partial");
  }

  @Test
  void runsAsAJobSoTheOwnerCanWatchItRatherThanGuess() {
    svc.start("/home/bruce/media", "aurora_nas_x", false, "aurora:test");

    verify(jobs).submitCommand(eq(JobService.Kind.BACKUP), eq("storage-mirror"),
        eq((Path) null), any());
    // One honest progress line, not a torrent of filenames.
    assertThat(String.join(" ", capturedArgv())).contains("--info=progress2");
  }

  @Test
  void reportsTheLastRunHonestlyIncludingNeverHavingRunOne() {
    assertThat(svc.lastRun().get("outcome")).isEmpty();

    svc.recordOutcome("2026-08-29T11:00:00Z", true, "42 files");
    assertThat(svc.lastRun()).containsEntry("outcome", "ok")
        .containsEntry("at", "2026-08-29T11:00:00Z")
        .containsEntry("detail", "42 files");

    svc.recordOutcome("2026-08-29T12:00:00Z", false, "NAS went away");
    assertThat(svc.lastRun()).containsEntry("outcome", "failed");
  }
}
