package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * iter-30 (v0.3 followup): read-only audit-event feed. Backs a future
 * settings-side viewer + supports `grep`-style filters over the CLI
 * (via curl) so an operator can answer "who dismissed this?".
 *
 * <p>Endpoint: {@code GET /api/audit/events} with optional query params
 * {@code action}, {@code userId}, {@code since}, {@code until}, {@code limit}.
 * Auth: SecurityConfig default {@code .anyRequest().authenticated()} —
 * audit rows expose acting-admin ids and diff blobs that may carry
 * dismissal reasons.
 *
 * <p>Response shape: array of {@code {id, ts, user_id?, action, target?,
 * diff_json?}} sorted newest first (already ordered by the repo).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

  /**
   * Action-shape guard. Action names emitted today are dot-scoped
   * ({@code security.dismiss}, {@code onboarding.launch.finish}); the
   * regex allows the same shape as an optional prefix.
   */
  private static final Pattern ACTION_SHAPE = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");

  static final int DEFAULT_LIMIT = 100;

  private final AuditEventRepo repo;

  public AuditController(AuditEventRepo repo) {
    this.repo = repo;
  }

  @GetMapping("/events")
  public List<Map<String, Object>> list(
      @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "userId", required = false) Long userId,
      @RequestParam(name = "since", required = false) String since,
      @RequestParam(name = "until", required = false) String until,
      @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit
  ) {
    if (action != null && !action.isEmpty() && !ACTION_SHAPE.matcher(action).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "action must match ^[a-z][a-z0-9._-]{0,63}$");
    }
    if (userId != null && userId < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "userId must be >= 0");
    }
    Instant sinceInstant = parseIso(since, "since");
    Instant untilInstant = parseIso(until, "until");
    if (sinceInstant != null && untilInstant != null && !sinceInstant.isBefore(untilInstant)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "since must be strictly before until");
    }
    if (limit < 1 || limit > AuditEventRepo.MAX_LIMIT) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "limit must be in [1, " + AuditEventRepo.MAX_LIMIT + "]");
    }
    return repo.query(action, userId, sinceInstant, untilInstant, limit);
  }

  private static Instant parseIso(String raw, String field) {
    if (raw == null || raw.isEmpty()) return null;
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          field + " must be an ISO-8601 UTC timestamp (e.g. 2026-08-01T00:00:00Z)");
    }
  }
}
