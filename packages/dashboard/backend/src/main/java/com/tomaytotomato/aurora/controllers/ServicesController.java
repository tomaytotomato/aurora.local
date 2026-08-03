package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.services.LaunchService;
import com.tomaytotomato.aurora.services.PackagesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Post-onboarding per-service actions. Authenticated (session cookie);
 * no onboarding guard — deliberately the sibling of
 * {@code POST /api/onboarding/launch} for use after {@code complete=true}.
 *
 * <p>Iter-dash-1: added {@code POST /api/services/{package}/start} so the
 * dashboard-home Packages card no longer hits {@code /api/onboarding/launch}
 * (which correctly 409s once onboarding is complete). Delegates into the
 * same {@link LaunchService} + SSE contract used by the wizard so the
 * frontend {@code LaunchProgress} component can be reused unchanged.
 *
 * <p>Read-only status lives at {@code GET /api/services/status} (see
 * {@link StatusController}). This controller is mutating, so it stays
 * behind the auth wall in {@link com.tomaytotomato.aurora.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/services")
public class ServicesController {

  // Package name shape: same rule as PackageNameValidator.SHAPE — prevents
  // path traversal and shell metacharacters before the name ever reaches
  // LaunchService's ProcessBuilder argv.
  private static final Pattern NAME_SHAPE = Pattern.compile("^[a-z][a-z0-9-]*$");

  private final PackagesService packages;
  private final LaunchService launcher;

  public ServicesController(PackagesService packages, LaunchService launcher) {
    this.packages = packages;
    this.launcher = launcher;
  }

  /**
   * Kick off a launch for a single package. Returns 202 with the job id;
   * the frontend can subscribe to the existing
   * {@code GET /api/onboarding/launch/{id}/stream} SSE endpoint (job id is
   * the same shared launch-job model).
   *
   * <ul>
   *   <li>400 — package name malformed.</li>
   *   <li>404 — package not on disk.</li>
   *   <li>409 — a launch job is already running.</li>
   *   <li>422 — package is present but not enabled in .state.yml
   *       (guardrail: don't quietly enable + start something the user
   *       didn't opt into).</li>
   *   <li>202 — {@code {job_id, packages, started_at}}.</li>
   * </ul>
   */
  @PostMapping("/{name}/start")
  public ResponseEntity<Map<String, Object>> start(@PathVariable("name") String name) {
    if (name == null || !NAME_SHAPE.matcher(name).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "package name is malformed");
    }
    Optional<Package> found = packages.find(name);
    if (found.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "no such package on this box");
    }
    if (!found.get().enabled()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "package is not enabled; add it via Settings first");
    }

    LaunchService.Job job;
    try {
      job = launcher.startLaunch(List.of(name));
    } catch (LaunchService.LaunchInProgressException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "launch already running: " + e.activeJobId);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("job_id", job.id);
    body.put("packages", job.packages);
    body.put("started_at", job.startedAt.toString());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
  }
}
