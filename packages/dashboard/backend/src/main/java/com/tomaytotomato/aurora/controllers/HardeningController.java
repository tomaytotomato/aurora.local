package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.HardeningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code /api/security/hardening} — where the three outstanding hardening
 * decisions stand.
 *
 * <p>Its own controller rather than another method on
 * {@link SecurityController}: that one is about findings the rule engine
 * emits, this is about the state of the repository, and they only share a
 * URL prefix.
 *
 * <p>Read-only by design. Pinning rewrites compose files, sops needs a
 * key, and putting a proxy in front of the socket changes how the
 * dashboard talks to Docker — each is an Ansible run or a script, and a
 * dashboard that quietly rewrites your compose files is one you cannot
 * reason about.
 */
@RestController
@RequestMapping("/api/security/hardening")
public class HardeningController {

  private final HardeningService hardening;

  public HardeningController(HardeningService hardening) {
    this.hardening = hardening;
  }

  @GetMapping
  public Map<String, Object> state() {
    return hardening.state();
  }
}
