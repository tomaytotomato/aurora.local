package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Disk;
import com.tomaytotomato.aurora.domain.DiskSmart;
import com.tomaytotomato.aurora.domain.Parity;
import com.tomaytotomato.aurora.domain.Pool;
import com.tomaytotomato.aurora.services.DisksService;
import com.tomaytotomato.aurora.services.NetworkStorageService;
import com.tomaytotomato.aurora.services.NetworkMountService;
import com.tomaytotomato.aurora.services.SmbShareService;
import com.tomaytotomato.aurora.services.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * {@code /api/disks} — every physical disk, the mergerfs pool, and
 * SnapRAID parity. Read-only except for the two SnapRAID actions.
 *
 * <p>Every GET here is a file read, not a privileged command: the
 * dashboard container never runs {@code smartctl}, {@code snapraid} or
 * {@code mergerfs} itself. See {@link DisksService} and
 * {@code docs/DISKS_PAGE_DESIGN.md}.
 */
@RestController
@RequestMapping("/api/disks")
public class DisksController {

  private final DisksService disks;
  private final JobService jobs;
  private final NetworkStorageService networkStorage;
  private final SmbShareService smbShares;
  private final NetworkMountService mounts;

  public DisksController(DisksService disks, JobService jobs,
                         NetworkStorageService networkStorage,
                         SmbShareService smbShares,
                         NetworkMountService mounts) {
    this.disks = disks;
    this.jobs = jobs;
    this.networkStorage = networkStorage;
    this.smbShares = smbShares;
    this.mounts = mounts;
  }

  /**
   * Storage on the LAN that is not this box: a NAS, another server, a
   * spare machine sharing a folder.
   *
   * <p>Lives under {@code /api/disks} because to the owner it is the same
   * question — "where can my files go?" — even though the plumbing is
   * completely different from a physical disk.
   *
   * <p>Listening, not scanning: see {@link NetworkStorageService}. Returns
   * an empty list on a network with nothing on it, on a box with no mDNS
   * stack, and on any failure; every one of those is honestly "found
   * nothing" rather than an error to put in front of someone.
   */
  @GetMapping("/network")
  public List<com.tomaytotomato.aurora.domain.NetworkStorageDevice> network() {
    return networkStorage.discover();
  }

  /**
   * What folders a device is offering.
   *
   * <p>POST rather than GET because credentials are in the body: a share
   * listing with a password in the query string would end up in access
   * logs and browser history.
   *
   * <p>Never 401s on a refused login — a wrong NAS password is not an
   * Aurora authentication failure, and returning 401 would log the operator
   * out of the dashboard. The refusal is the payload.
   */
  @PostMapping("/network/shares")
  public SmbShareService.Result shares(@RequestBody ShareRequest req) {
    if (req == null || req.address() == null || req.address().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "address required");
    }
    return smbShares.list(req.address(), req.username(), req.password());
  }

  /** Credentials are optional: most NAS boxes allow a guest listing. */
  public record ShareRequest(String address, String username, String password) {}

  /** Folders on network devices that this box has attached. */
  @GetMapping("/network/mounts")
  public List<NetworkMountService.Mount> mounts() {
    return mounts.list();
  }

  /**
   * Attach a folder so apps on this box can use it.
   *
   * <p>Returns 200 with {@code ok:false} for the things the owner can fix —
   * wrong password, wrong folder name, device asleep — rather than a 4xx.
   * Those are answers to the question they asked, not errors in the
   * request, and a 401 in particular would log them out of the dashboard.
   */
  @PostMapping("/network/mounts")
  public NetworkMountService.AttachResult attach(@RequestBody AttachRequest req) {
    if (req == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
    }
    return mounts.attach(req.label(), req.address(), req.share(),
        req.username(), req.password(), req.readOnly());
  }

  @DeleteMapping("/network/mounts/{id}")
  public ResponseEntity<Void> detach(@PathVariable String id) {
    return mounts.detach(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  public record AttachRequest(String label, String address, String share,
                              String username, String password, boolean readOnly) {}

  @GetMapping
  public List<Disk> list() {
    return disks.list();
  }

  @GetMapping("/pool")
  public Pool pool() {
    return disks.pool();
  }

  @GetMapping("/parity")
  public Parity parity() {
    return disks.parity();
  }

  /**
   * Re-runs the same guarded nightly runner a scheduled sync would, so an
   * impatient click gets the same deletion-threshold protection as 3:30am.
   */
  @PostMapping("/parity/sync")
  public ResponseEntity<Map<String, Object>> sync() {
    JobService.Job job = jobs.submitCommand(JobService.Kind.PARITY_SYNC, null, null, disks.syncArgv());
    return ResponseEntity.accepted().body(Map.of("jobId", job.id));
  }

  @PostMapping("/parity/scrub")
  public ResponseEntity<Map<String, Object>> scrub() {
    JobService.Job job = jobs.submitCommand(JobService.Kind.PARITY_SCRUB, null, null, disks.scrubArgv());
    return ResponseEntity.accepted().body(Map.of("jobId", job.id));
  }

  @GetMapping("/{id}/smart")
  public DiskSmart smart(@PathVariable("id") String id) {
    return disks.smart(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such disk"));
  }
}
