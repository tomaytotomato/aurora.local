package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.LaunchService;
import com.tomaytotomato.aurora.services.OnboardingService;
import com.tomaytotomato.aurora.services.PackagesService;
import com.tomaytotomato.aurora.services.StateFileService;
import com.tomaytotomato.aurora.services.SystemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * First-run wizard endpoints.
 *
 * <p>Shape (v0.2):
 * <ul>
 *   <li>{@code GET  /api/onboarding}            \u2014 full draft, hydration source</li>
 *   <li>{@code PATCH /api/onboarding}           \u2014 partial update of draft fields</li>
 *   <li>{@code POST /api/onboarding/admin}      \u2014 one-shot bootstrap of the initial admin</li>
 *   <li>{@code POST /api/onboarding/complete}   \u2014 commit</li>
 *   <li>{@code GET  /api/onboarding/env}        \u2014 host facts (kept separate; used by welcome)</li>
 *   <li>{@code GET  /api/onboarding/status}     \u2014 <b>deprecated</b>; slim view of the summary
 *       kept for one release so an already-loaded SPA build with the old shape keeps working</li>
 *   <li>{@code POST /api/onboarding/{domain,packages}} \u2014 <b>deprecated</b>; delegate to PATCH</li>
 * </ul>
 *
 * <p>All endpoints under /api/onboarding/** are declared public in
 * {@link com.tomaytotomato.aurora.config.SecurityConfig}. Mutating endpoints
 * additionally require {@code !isBootstrapMode() && !isComplete()} \u2014 enforced
 * by {@link OnboardingService#guardMidOnboarding()}.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

  private final OnboardingService onboarding;
  private final SystemService system;
  private final LaunchService launcher;
  private final StateFileService stateFiles;

  /**
   * TD5: when true, exposes {@code POST /api/onboarding/reset}. Bound from
   * {@code aurora.e2e-mode} which reads env {@code AURORA_E2E}. Defaults
   * to false in prod — the endpoint returns 404 so its existence stays
   * hidden.
   */
  @Value("${aurora.e2e-mode:false}")
  private boolean e2eMode;

  public OnboardingController(OnboardingService onboarding, SystemService system,
                              LaunchService launcher, StateFileService stateFiles) {
    this.onboarding = onboarding;
    this.system = system;
    this.launcher = launcher;
    this.stateFiles = stateFiles;
  }

  // --- canonical shape ------------------------------------------------

  @GetMapping
  public Map<String, Object> get() {
    return onboarding.summary();
  }

  @PatchMapping
  public ResponseEntity<Map<String, Object>> patch(@RequestBody PatchReq req) {
    try {
      var draft = new OnboardingService.PatchDraft(
          req.domain(),
          req.effectiveEnabledPackages(),
          req.dnsMode(),
          req.step()
      );
      return ResponseEntity.ok(onboarding.patch(draft));
    } catch (com.tomaytotomato.aurora.services.PackageNameValidator.InvalidPackageNamesException e) {
      var body = new java.util.LinkedHashMap<String, Object>();
      body.put("error", "invalid_package_names");
      body.put("message", e.getMessage());
      body.put("invalid", e.invalid);
      body.put("unknown", e.unknown);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  // --- one-shot bootstrap ---------------------------------------------

  @PostMapping("/admin")
  public ResponseEntity<Map<String, Object>> createAdmin(@Valid @RequestBody CreateAdminReq req) {
    long id;
    try {
      id = onboarding.createInitialAdmin(req.username(), req.password(), req.tz());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.ok(Map.of("id", id, "username", req.username()));
  }

  @PostMapping("/complete")
  public ResponseEntity<Map<String, Object>> complete() {
    try {
      onboarding.guardMidOnboarding();
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    onboarding.markComplete();
    return ResponseEntity.ok(Map.of("complete", true));
  }

  /**
   * TD5 (2026-08-02): E2E-only. Wipes admin users, onboarding.* settings,
   * and {@code .state.yml} so a Playwright suite can start every spec
   * from a pristine bootstrap. Gated on {@code aurora.e2e-mode}
   * ({@code AURORA_E2E=1}); returns 404 in prod so the endpoint's
   * existence isn't discoverable by scanning.
   *
   * <p>Not covered by {@code guardMidOnboarding()} — the whole point is
   * to blow away any state, including the {@code complete=true} that
   * would otherwise gate it. That's why the operational gate lives at
   * the env-var level rather than in-band with the wizard flow.
   */
  @PostMapping("/reset")
  public ResponseEntity<Map<String, Object>> reset() {
    if (!e2eMode) {
      // 404 (not 401/403) intentionally: hide the endpoint's existence
      // from a scanner that shouldn't know it can exist at all.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    onboarding.reset();
    return ResponseEntity.ok(Map.of("reset", true));
  }

  // --- read-only helpers ----------------------------------------------

  /** Public system facts for the welcome screen. Anything hazardous stays
   *  on the authed /api/system endpoint. */
  @GetMapping("/env")
  public Map<String, Object> env() {
    return system.env();
  }

  /**
   * Preview of what "Install" would do: enabled packages, aggregated ports,
   * probable vhosts, and light warnings. Read-only; safe pre-auth during
   * bootstrap.
   */
  @GetMapping("/plan")
  public Map<String, Object> plan(
      @org.springframework.web.bind.annotation.RequestParam(name = "enabled", required = false)
      String enabledCsv) {
    // enabled=core,ai,media — optional preview override so the SPA can
    // evaluate warnings for a hypothetical selection without PATCHing.
    List<String> override = null;
    if (enabledCsv != null && !enabledCsv.isBlank()) {
      override = new java.util.ArrayList<>();
      for (String s : enabledCsv.split(",")) {
        String t = s.trim();
        if (!t.isEmpty()) override.add(t);
      }
    }
    return onboarding.plan(override);
  }

  /**
   * Apply the wizard draft. v0.1 semantics: persist config, report which
   * packages still need {@code scripts/up.sh} on the host. Does not commit
   * onboarding — the client hits {@code /complete} after showing the Done
   * screen so the operator can read the summary first.
   */
  @PostMapping("/install")
  public ResponseEntity<Map<String, Object>> install() {
    try {
      return ResponseEntity.ok(onboarding.install());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  // --- launch (iter-1) ------------------------------------------------

  /**
   * Kick off {@code scripts/up.sh} on behalf of the operator so the wizard
   * can finish without an SSH step. Reads the enabled packages from
   * {@code .state.yml} (never from the request body — closes any RCE surface
   * via caller-supplied package names).
   *
   * <p>Guarded by {@link OnboardingService#guardMidOnboarding()} — same guard
   * as {@code /install}. Returns 202 with the job id, or 409 if a launch is
   * already running.
   */
  @PostMapping("/launch")
  public ResponseEntity<Map<String, Object>> launch() {
    try {
      onboarding.guardMidOnboarding();
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    var state = stateFiles.readState();
    List<String> enabled = state.enabled() == null ? List.of() : state.enabled();
    if (enabled.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "no packages are enabled in .state.yml");
    }
    LaunchService.Job job;
    try {
      job = launcher.startLaunch(enabled);
    } catch (LaunchService.LaunchInProgressException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "launch already running: " + e.activeJobId);
    }
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("job_id", job.id);
    body.put("packages", job.packages);
    body.put("started_at", job.startedAt.toString());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
  }

  @GetMapping("/launch/{id}")
  public Map<String, Object> launchStatus(@PathVariable("id") String id) {
    LaunchService.Job job = launcher.get(id);
    if (job == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such job");
    }
    return job.toStatusMap();
  }

  @GetMapping(value = "/launch/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter launchStream(@PathVariable("id") String id) {
    LaunchService.Job job = launcher.get(id);
    if (job == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such job");
    }
    SseEmitter emitter = new SseEmitter(0L); // never time out
    launcher.subscribe(id, emitter);
    return emitter;
  }

  /**
   * Deprecated. Returns the slim view (complete, bootstrap_mode, step)
   * that the pre-v0.2 SPA build expects. New callers should use
   * {@code GET /api/onboarding}.
   */
  @GetMapping("/status")
  public Map<String, Object> status() {
    var s = onboarding.summary();
    return Map.of(
        "complete", s.get("complete"),
        "bootstrap_mode", s.get("bootstrap_mode"),
        "step", s.get("step")
    );
  }

  // --- deprecated field routes (delegate to PATCH) --------------------

  @PostMapping("/domain")
  public ResponseEntity<Map<String, Object>> setDomain(@Valid @RequestBody SetDomainReq req) {
    return patch(new PatchReq(req.domain(), null, null, null, null, null));
  }

  @PostMapping("/packages")
  public ResponseEntity<Map<String, Object>> setPackages(@RequestBody SetPackagesReq req) {
    return patch(new PatchReq(null, null, req.enabled(), req.names(), null, null));
  }

  // --- request DTOs ---------------------------------------------------

  public record CreateAdminReq(
      @NotBlank String username,
      @NotBlank @Size(min = 12, max = 256) String password,
      String tz
  ) {}

  public record SetDomainReq(@NotBlank String domain) {}

  /** Accepts either {enabled:[...]} or {names:[...]} for frontend compat. */
  public record SetPackagesReq(List<String> enabled, List<String> names) {}

  /**
   * Partial-update body. All fields optional; unset fields are no-ops.
   * Accepts both snake_case ({@code enabled_packages}, {@code dns_mode}) and
   * legacy shapes ({@code enabled}, {@code names}) so a stale SPA build and
   * this endpoint can co-exist through one release.
   */
  public record PatchReq(
      String domain,
      @com.fasterxml.jackson.annotation.JsonProperty("enabled_packages") List<String> enabledPackages,
      List<String> enabled,
      List<String> names,
      @com.fasterxml.jackson.annotation.JsonProperty("dns_mode") String dnsMode,
      String step
  ) {
    public List<String> effectiveEnabledPackages() {
      if (enabledPackages != null) return enabledPackages;
      if (enabled != null) return enabled;
      if (names != null) return names;
      return null; // signals "leave untouched"
    }
  }
}
