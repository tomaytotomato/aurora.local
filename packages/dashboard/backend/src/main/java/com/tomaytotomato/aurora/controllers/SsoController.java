package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.SsoEnrollmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Second-factor enrollment state, after onboarding has finished.
 *
 * <p>Counterpart to {@code GET /api/onboarding/sso}, which serves the
 * same data during the wizard while no session exists yet. This one sits
 * under the default {@code authenticated()} rule in
 * {@link com.tomaytotomato.aurora.config.SecurityConfig}, so it is the
 * variant allowed to return a pending registration link: by this point
 * the box has real users, and a link that lets the holder bind an
 * authenticator to an account must not be readable by an anonymous LAN
 * client.
 *
 * <p>Used by Settings to show whether the signed-in operator has a
 * passkey, and to re-surface an enrollment link if they started
 * registration and did not finish.
 */
@RestController
@RequestMapping("/api/sso")
public class SsoController {

  private final SsoEnrollmentService sso;

  public SsoController(SsoEnrollmentService sso) {
    this.sso = sso;
  }

  @GetMapping("/status")
  public SsoEnrollmentService.EnrollmentStatus status() {
    return sso.status();
  }
}
