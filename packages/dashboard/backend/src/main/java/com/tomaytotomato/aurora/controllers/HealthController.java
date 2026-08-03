package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.DockerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Friendlier /api/health than actuator: reports subsystem status the SPA can
 * render on the "system check" screen without needing management-endpoint auth.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

  private final JdbcTemplate jdbc;
  private final DockerService docker;

  public HealthController(JdbcTemplate jdbc, DockerService docker) {
    this.jdbc = jdbc;
    this.docker = docker;
  }

  @GetMapping
  public Map<String, Object> get() {
    Map<String, Object> out = new HashMap<>();
    out.put("status", "ok");

    // DB round-trip
    boolean db = false;
    try {
      Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
      db = one != null && one == 1;
    } catch (Exception ignore) { /* db false */ }
    out.put("db", db);

    // Docker version probe
    out.put("docker", docker.version().orElse(null));

    if (!db) out.put("status", "degraded");
    return out;
  }
}
