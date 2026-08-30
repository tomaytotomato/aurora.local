package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.AdguardSessionBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/apps/adguard} \u2014 the Caddy-facing side of the AdGuard
 * SSO story.
 *
 * <p>The single endpoint here is called by Caddy on every request to
 * {@code adguard.$DOMAIN} <em>after</em> Authelia has verified the user.
 * Caddy uses a second {@code forward_auth} block that hits
 * {@link #sessionCookie()} and copies the returned
 * {@code X-Adguard-Cookie} response header onto the outgoing request as
 * a {@code Cookie} header. AdGuard, which sits behind Caddy, treats
 * the request as an already-authenticated session and never shows its
 * own login screen. The operator sees one login (Authelia) end-to-end.
 *
 * <p><b>Public endpoint, but not the way it looks.</b> This route is
 * NOT gated by Aurora's own admin-auth: Caddy can't send an Aurora
 * session cookie on a forward-auth sub-request. Access control lives
 * at the Caddy layer: the {@code adguard.$DOMAIN} vhost only calls
 * this endpoint AFTER the outer {@code import authelia} block has
 * already accepted the request, so an unauthenticated LAN client
 * cannot reach it via that path. Direct calls to Aurora bypass Caddy
 * entirely, so this endpoint additionally lives on the LAN-only :8090
 * surface \u2014 which is fine because the returned cookie is only
 * useful for logging into AdGuard, which the same LAN client can
 * already reach on :3000 directly.
 *
 * <p><b>503 on failure.</b> When the broker cannot log in \u2014 wrong
 * password, AdGuard down, container not restarted after the broker
 * user was added \u2014 the endpoint returns 503. Caddy's
 * {@code forward_auth} treats 5xx as "deny", so an operator hitting
 * {@code adguard.$DOMAIN} will see Caddy's error page rather than the
 * AdGuard login screen: which is the honest state to render (something
 * is broken between Aurora and AdGuard) rather than falling through
 * to a second login the doctrine explicitly wanted to remove.
 */
@RestController
@RequestMapping("/api/apps/adguard")
public class AdguardSessionController {

  private static final Logger log = LoggerFactory.getLogger(AdguardSessionController.class);

  private final AdguardSessionBroker broker;

  public AdguardSessionController(AdguardSessionBroker broker) {
    this.broker = broker;
  }

  /**
   * Return a valid AdGuard session cookie in the {@code X-Adguard-Cookie}
   * response header, so Caddy's {@code copy_headers X-Adguard-Cookie>Cookie}
   * can graft it onto the outgoing request to AdGuard. Body is empty on
   * purpose \u2014 Caddy discards forward-auth response bodies.
   */
  @GetMapping("/session-cookie")
  public ResponseEntity<Void> sessionCookie() {
    return broker.currentSessionCookie()
        .map(cookie -> ResponseEntity.noContent()
            .header("X-Adguard-Cookie", cookie)
            .header("Cache-Control", "no-store")
            .<Void>build())
        .orElseGet(() -> {
          log.warn("adguard session broker: no cookie available; returning 503");
          return ResponseEntity.status(503).build();
        });
  }
}
