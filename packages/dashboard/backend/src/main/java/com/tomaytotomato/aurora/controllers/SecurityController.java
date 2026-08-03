package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.security.SecurityFindingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B4 (v0.3): read-only surface for the security-rule engine. Backend
 * only for now \u2014 the SecurityPosture view still renders its "M4
 * scanner will land next release" empty state until the frontend wires
 * a real card list against this endpoint in a follow-up iter.
 *
 * <p>Auth: falls under {@code SecurityConfig.anyRequest().authenticated()}.
 * Findings leak container names, image tags, and the shape of an
 * admin's password hash \u2014 not a public surface.
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

  private final SecurityFindingsService findings;

  public SecurityController(SecurityFindingsService findings) {
    this.findings = findings;
  }

  @GetMapping("/findings")
  public List<SecurityFinding> findings() {
    return findings.allFindings();
  }
}
