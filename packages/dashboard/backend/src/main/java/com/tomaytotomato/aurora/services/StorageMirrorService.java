package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Copies what this box has downloaded onto a NAS.
 *
 * <p>This is the middle of the three storage arrangements Aurora supports:
 *
 * <ol>
 *   <li><b>Keep it here.</b> Downloads land on this box's own disk and stay
 *       there. No NAS involved.</li>
 *   <li><b>Keep a copy on the NAS.</b> Downloads land here — fast local
 *       disk, which is what a download client and a torrent seeder want —
 *       and are copied onto the NAS afterwards. This class.</li>
 *   <li><b>Put it straight on the NAS.</b> Downloads are written to the NAS
 *       as they arrive; this box keeps nothing.</li>
 * </ol>
 *
 * <p><b>Why this one needs no new privilege.</b> The copy runs inside a
 * one-shot container with the local folder bind-mounted and the NAS
 * attached as a docker volume. Nothing on the host mounts anything, and
 * nothing runs as root outside a container.
 *
 * <p><b>Copy, not mirror, by default.</b> Mirroring deletes files on the
 * NAS that are no longer on this box — which is exactly what someone wants
 * for a backup, and exactly what destroys an archive when the local disk is
 * wiped and a sync runs before anyone notices. Deletion is available, off
 * by default, and named plainly in the UI rather than hidden behind the
 * word "sync".
 */
@Service
public class StorageMirrorService {

  private static final Logger log = LoggerFactory.getLogger(StorageMirrorService.class);

  static final String KEY_LAST_RUN = "storage.mirror.last_run";
  static final String KEY_LAST_OUTCOME = "storage.mirror.last_outcome";
  static final String KEY_LAST_DETAIL = "storage.mirror.last_detail";

  private final JobService jobs;
  private final SettingsRepo settings;
  private final AuditEventRepo audit;

  public StorageMirrorService(JobService jobs, SettingsRepo settings, AuditEventRepo audit) {
    this.jobs = jobs;
    this.settings = settings;
    this.audit = audit;
  }

  /**
   * Start a copy from {@code sourcePath} on this box to {@code volume} on
   * the NAS. Returns the job so the UI can stream it, because a first copy
   * of a media library is measured in hours and a spinner with no output is
   * indistinguishable from a hang.
   *
   * @param delete remove files on the NAS that are gone from here. Off
   *               unless the owner explicitly asked for it.
   */
  public JobService.Job start(String sourcePath, String volume, boolean delete, String image) {
    List<String> argv = new ArrayList<>(List.of(
        "docker", "run", "--rm",
        // Read-only source: a copy job has no business writing to the
        // library it is copying from.
        "-v", sourcePath + ":/from:ro",
        "-v", volume + ":/to",
        "--entrypoint", "rsync", image,
        // -a preserves times and permissions, --partial keeps a half-copied
        // large file so an interrupted run resumes instead of restarting,
        // --info=progress2 gives one honest progress line rather than a
        // filename torrent.
        "-a", "--partial", "--info=progress2", "--no-owner", "--no-group"));
    if (delete) argv.add("--delete");
    argv.add("/from/");
    argv.add("/to/");

    audit.record(null, "storage.mirror.start", volume,
        "{\"delete\":" + delete + ",\"source\":\"" + sourcePath + "\"}");
    log.info("mirroring {} -> volume {} (delete={})", sourcePath, volume, delete);
    return jobs.submitCommand(JobService.Kind.BACKUP, "storage-mirror", null, argv);
  }

  /** What the last copy did, for a UI that must not invent a status. */
  public Map<String, String> lastRun() {
    return Map.of(
        "at", settings.get(KEY_LAST_RUN).orElse(""),
        "outcome", settings.get(KEY_LAST_OUTCOME).orElse(""),
        "detail", settings.get(KEY_LAST_DETAIL).orElse(""));
  }

  /** Record the outcome. Called when the job finishes, not when it starts. */
  public void recordOutcome(String isoTimestamp, boolean ok, String detail) {
    settings.put(KEY_LAST_RUN, isoTimestamp);
    settings.put(KEY_LAST_OUTCOME, ok ? "ok" : "failed");
    settings.put(KEY_LAST_DETAIL, detail == null ? "" : detail);
  }
}
