package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /api/jobs} — every long-running operation and its log.
 *
 * <p>See {@link JobService} for why this is shared rather than
 * per-feature. The onboarding launch keeps its own endpoint under
 * {@code /api/onboarding/launch} because it carries per-package state this
 * shape has no room for.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobsController {

  /**
   * SSE timeout. Long, because a first pull of a large stack legitimately
   * runs for many minutes and the heartbeat keeps the connection warm.
   * Not infinite: a leaked emitter should eventually be reclaimed.
   */
  private static final long STREAM_TIMEOUT_MS = 60 * 60 * 1000L;

  private final JobService jobs;

  public JobsController(JobService jobs) {
    this.jobs = jobs;
  }

  @GetMapping
  public List<Map<String, Object>> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "kind", required = false) String kind
  ) {
    return jobs.list(parseState(state), parseKind(kind)).stream()
        .map(JobService.Job::toSummary)
        .toList();
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable("id") String id) {
    return jobs.find(id)
        .map(JobService.Job::toStatus)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such job"));
  }

  /**
   * Live log. Replays everything produced so far, then streams; a job that
   * has already finished replays its whole log and closes at once, which
   * is what makes a log readable after the fact.
   */
  @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable("id") String id) {
    if (jobs.find(id).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such job");
    }
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    jobs.subscribe(id, emitter);
    return emitter;
  }

  /**
   * An unrecognised filter is a client bug, so it is a 400 rather than
   * being quietly ignored — a caller that typos {@code state=finished}
   * should be told, not handed every job.
   */
  private JobService.State parseState(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return JobService.State.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown state: " + raw);
    }
  }

  private JobService.Kind parseKind(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return JobService.Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown kind: " + raw);
    }
  }
}
