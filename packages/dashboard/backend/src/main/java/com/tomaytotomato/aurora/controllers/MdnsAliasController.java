package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.MdnsAliasService;
import com.tomaytotomato.aurora.services.MdnsAliasService.AliasView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-08-03 v0.3.x productionize: read + reconcile the mDNS aliases
 * managed by {@link MdnsAliasService}. Backs the Settings-page
 * "LAN discovery" card + supports a manual "publish now" button when
 * the drift-guard reconcile loop feels too slow.
 *
 * <p>Auth: default {@code SecurityConfig.anyRequest().authenticated()}
 * — the alias list carries the box's LAN IP and per-package vhost
 * names, both of which are internal-signal.
 */
@RestController
@RequestMapping("/api/mdns")
public class MdnsAliasController {

  private final MdnsAliasService svc;

  public MdnsAliasController(MdnsAliasService svc) {
    this.svc = svc;
  }

  /**
   * Current alias set. Cheap read — does NOT respawn crashed publishes.
   * Use {@link #reconcile()} to force a republish.
   */
  @GetMapping("/aliases")
  public Map<String, Object> list() {
    return payload(svc.aliases());
  }

  /** Idempotent republish — spawns missing, kills orphans, restarts crashed. */
  @PostMapping("/reconcile")
  public Map<String, Object> reconcile() {
    return payload(svc.reconcile());
  }

  private Map<String, Object> payload(List<AliasView> aliases) {
    long up = aliases.stream().filter(a -> "up".equals(a.state())).count();
    long failed = aliases.stream().filter(a -> "failed".equals(a.state())).count();
    Map<String, Object> out = new HashMap<>();
    out.put("aliases", aliases);
    out.put("total", aliases.size());
    out.put("up", up);
    out.put("failed", failed);
    return out;
  }
}
