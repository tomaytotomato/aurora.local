package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.StatusProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Iter-2 service status surface. Returns per-package live probes for the
 * Done page checklist (and, in iter-3, the dashboard home).
 *
 * <p>Endpoint: {@code GET /api/services/status}. Polled every 5s by the
 * frontend while {@code /onboarding/done} is mounted. Backing service caches
 * per-package results for 3s so two clients don't amplify the fan-out.
 */
@RestController
@RequestMapping("/api/services")
public class StatusController {

  private final StatusProbeService probes;

  public StatusController(StatusProbeService probes) {
    this.probes = probes;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return probes.snapshot();
  }
}
