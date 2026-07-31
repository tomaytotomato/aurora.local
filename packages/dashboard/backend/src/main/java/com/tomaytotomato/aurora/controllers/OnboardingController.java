package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.OnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * First-run wizard endpoints.
 *
 * <p>All endpoints under /api/onboarding/** are declared public in
 * {@link com.tomaytotomato.aurora.config.SecurityConfig}. Any endpoint that
 * mutates state MUST additionally check {@code OnboardingService.isBootstrapMode()}
 * or {@code !OnboardingService.isComplete()} to prevent re-onboarding after
 * install.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

  private final OnboardingService onboarding;

  public OnboardingController(OnboardingService onboarding) {
    this.onboarding = onboarding;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return Map.of(
        "complete", onboarding.isComplete(),
        "bootstrap_mode", onboarding.isBootstrapMode(),
        "step", onboarding.currentStep()
    );
  }

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

  @PostMapping("/domain")
  public ResponseEntity<Map<String, Object>> setDomain(@Valid @RequestBody SetDomainReq req) {
    guardPostAdmin();
    onboarding.setDomain(req.domain());
    return ResponseEntity.ok(Map.of("domain", req.domain()));
  }

  @PostMapping("/packages")
  public ResponseEntity<Map<String, Object>> setPackages(@RequestBody SetPackagesReq req) {
    guardPostAdmin();
    onboarding.setEnabledPackages(req.enabled());
    return ResponseEntity.ok(Map.of("enabled", req.enabled()));
  }

  @PostMapping("/complete")
  public ResponseEntity<Map<String, Object>> complete() {
    guardPostAdmin();
    onboarding.markComplete();
    return ResponseEntity.ok(Map.of("complete", true));
  }

  private void guardPostAdmin() {
    // After the initial admin exists, mutating the domain/packages via the
    // public onboarding routes is only allowed until onboarding completes.
    // (Normal package/domain edits go through authenticated endpoints in M2.)
    if (onboarding.isBootstrapMode()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "create an admin user first via POST /api/onboarding/admin");
    }
    if (onboarding.isComplete()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "onboarding already complete; use authenticated endpoints");
    }
  }

  // --- request DTOs ---

  public record CreateAdminReq(
      @NotBlank String username,
      @NotBlank @Size(min = 12, max = 256) String password,
      String tz
  ) {}

  public record SetDomainReq(@NotBlank String domain) {}

  public record SetPackagesReq(List<String> enabled) {}
}
