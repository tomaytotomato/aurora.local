package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.OnboardingService;
import com.tomaytotomato.aurora.services.SystemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

  public OnboardingController(OnboardingService onboarding, SystemService system) {
    this.onboarding = onboarding;
    this.system = system;
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
