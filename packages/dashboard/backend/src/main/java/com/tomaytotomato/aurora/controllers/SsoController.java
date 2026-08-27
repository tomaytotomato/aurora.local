package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.SsoEnrollmentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  /**
   * Recent Authelia notifications, newest first.
   *
   * <p>Reads the same filesystem-notifier file the wizard's SSO step
   * reads, but surfaces the whole log rather than just the pending
   * registration URL. This is what powers the Authelia service-detail
   * panel on {@code /apps/core}: the operator wants the most recent
   * one-time code Authelia has just emitted (for a password reset, an
   * authenticator rebind, an admin change) without shelling into the
   * container.
   *
   * <p><b>Authenticated on purpose.</b> Every entry can carry an OTP or
   * a link that, if opened by a stranger, binds an authenticator to an
   * account. Serving it anonymously would hand any LAN client a
   * standing account-takeover primitive; the counterpart on the wizard
   * side ({@code GET /api/onboarding/sso}) can only expose one URL and
   * only while onboarding is running.
   *
   * @param limit how many entries to return, capped at 20 so a runaway
   *              caller cannot force us to hold a large file body in
   *              memory just to return it verbatim. The Authelia
   *              detail panel asks for 5.
   */
  @GetMapping("/notifications")
  public List<SsoEnrollmentService.Notification> notifications(
      @RequestParam(name = "limit", defaultValue = "5") int limit
  ) {
    // Both ends clamped: negative or zero limits become 5 (the panel's
    // default), unbounded requests are capped to 20. The whole
    // notification file is comfortably below 1 MB in practice, but a
    // request-driven cap is cheaper than trusting that forever.
    int bounded = limit <= 0 ? 5 : Math.min(limit, 20);
    return sso.notifications(bounded);
  }
}
