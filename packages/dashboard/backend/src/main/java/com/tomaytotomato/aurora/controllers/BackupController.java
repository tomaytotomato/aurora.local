package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.BackupSource;
import com.tomaytotomato.aurora.domain.BackupStatus;
import com.tomaytotomato.aurora.services.BackupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /api/backup} — the read half of the backup page.
 *
 * <p>Answers "is my data safe" without making anything up: see
 * {@link BackupService} for why sizes are nullable and why a declared but
 * never-snapshotted path is still listed.
 *
 * <p>The write half (back up now, restore, source toggle, policy) is not
 * here yet.
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

  private final BackupService backup;

  public BackupController(BackupService backup) {
    this.backup = backup;
  }

  @GetMapping("/status")
  public BackupStatus status() {
    return backup.status();
  }

  @GetMapping("/sources")
  public List<BackupSource> sources() {
    return backup.sources();
  }
}
