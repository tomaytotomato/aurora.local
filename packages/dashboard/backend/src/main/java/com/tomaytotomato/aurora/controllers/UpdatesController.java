package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.JobService;
import com.tomaytotomato.aurora.services.UpdatesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * {@code /api/updates} — what is behind, and a way to go and look.
 *
 * <p>Read-only. Applying an update goes through the existing
 * {@code POST /packages/{name}/upgrade}; there is deliberately no second
 * update verb on the wire.
 */
@RestController
@RequestMapping("/api/updates")
public class UpdatesController {

  private final UpdatesService updates;
  private final JobService jobs;

  public UpdatesController(UpdatesService updates, JobService jobs) {
    this.updates = updates;
    this.jobs = jobs;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return updates.list();
  }

  @GetMapping("/{name}")
  public Map<String, Object> get(@PathVariable("name") String name) {
    return updates.find(name).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "no such package"));
  }

  /**
   * Re-query every registry. A job rather than a blocking call because
   * asking nine registries takes long enough that the operator should see
   * it happening rather than watch a spinner.
   */
  @PostMapping("/check")
  public ResponseEntity<Map<String, Object>> check() {
    JobService.Job job = jobs.submit(JobService.Kind.UPDATE_CHECK, null,
        j -> updates.checkAll(jobs, j));
    return ResponseEntity.accepted().body(Map.of("jobId", job.id));
  }
}
