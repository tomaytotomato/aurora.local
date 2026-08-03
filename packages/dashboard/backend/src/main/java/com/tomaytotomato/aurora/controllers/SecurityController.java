package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SecurityDismissalRepo;
import com.tomaytotomato.aurora.security.SecurityFindingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * B4 (v0.3) read + mutate surface for the security-rule engine.
 *
 * <p>iter-13 shipped the read side. iter-23 (B4-followup) adds the
 * dismiss / restore mutations backing SecurityPosture's "not now"
 * affordance. Auth-only per SecurityConfig defaults; every mutation
 * emits an audit record via the finding id.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/security/findings?includeDismissed=false}</li>
 *   <li>{@code GET  /api/security/dismissals} \u2014 raw dismissal list.</li>
 *   <li>{@code POST /api/security/findings/{id}/dismiss} body
 *       {@code {days?, reason?}}; {@code days} omitted / 0 means
 *       permanent.</li>
 *   <li>{@code DELETE /api/security/findings/{id}/dismiss} \u2014 restore
 *       a previously dismissed finding.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

  /**
   * Finding-id shape guard. Matches the shape rules emit today
   * ({@code weak_admin_password:bruce},
   * {@code docker_socket_exposure:aurora-portainer},
   * {@code unpinned_image_tags:aurora-media-sonarr}) plus safe growth
   * room for future rules. Rejects control chars, spaces, and any URL-
   * hostile input before the id ever reaches SQL.
   */
  private static final Pattern ID_SHAPE = Pattern.compile("^[a-z][a-z0-9_-]{0,63}(:[A-Za-z0-9_.-]{1,63})?$");

  /** Snooze ceiling. 365 days is a year; anything longer should be permanent. */
  static final int MAX_SNOOZE_DAYS = 365;

  private final SecurityFindingsService findings;
  private final SecurityDismissalRepo dismissals;
  private final AuditEventRepo audit;

  public SecurityController(SecurityFindingsService findings,
                            SecurityDismissalRepo dismissals,
                            AuditEventRepo audit) {
    this.findings = findings;
    this.dismissals = dismissals;
    this.audit = audit;
  }

  @GetMapping("/findings")
  public List<SecurityFinding> findings(
      @RequestParam(name = "includeDismissed", defaultValue = "false") boolean includeDismissed
  ) {
    return findings.allFindings(includeDismissed);
  }

  @GetMapping("/dismissals")
  public List<Map<String, Object>> listDismissals() {
    return dismissals.listAll();
  }

  /**
   * Body: {@code {days?: number, reason?: string}}. {@code days} 1..365
   * schedules a snoozed dismissal; omitted / 0 / null → permanent.
   * Idempotent \u2014 dismissing an already-dismissed finding just refreshes
   * the row.
   */
  @PostMapping("/findings/{id}/dismiss")
  public ResponseEntity<Map<String, Object>> dismiss(
      @PathVariable("id") String id,
      @RequestBody(required = false) Map<String, Object> body
  ) {
    if (id == null || !ID_SHAPE.matcher(id).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "finding id is malformed");
    }
    Integer days = null;
    String reason = null;
    if (body != null) {
      Object rawDays = body.get("days");
      if (rawDays instanceof Number n) days = n.intValue();
      else if (rawDays instanceof String s) {
        try { days = Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
      }
      Object rawReason = body.get("reason");
      if (rawReason instanceof String s) reason = s.length() > 512 ? s.substring(0, 512) : s;
    }
    if (days != null && (days < 1 || days > MAX_SNOOZE_DAYS)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "days must be null (permanent) or in [1, " + MAX_SNOOZE_DAYS + "]");
    }
    Instant expiresAt = days == null ? null : Instant.now().plus(Duration.ofDays(days));
    dismissals.dismiss(id, expiresAt, reason);
    // iter-27: audit trail so an operator can grep the audit log for
    // who suppressed what and when. Body is a compact JSON blob rather
    // than a full SecurityFinding — the finding text is fixed by the
    // rule engine, not user-provided, so recording the id + expiry is
    // enough to reconstruct context.
    String diff = "{\"expires_at\":" + (expiresAt == null ? "null" : "\"" + expiresAt + "\"")
        + (reason == null ? "" : ",\"reason\":\"" + jsonEscape(reason) + "\"")
        + "}";
    audit.record(null, "security.dismiss", "finding:" + id, diff);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("id", id);
    resp.put("expires_at", expiresAt == null ? null : expiresAt.toString());
    if (reason != null) resp.put("reason", reason);
    return ResponseEntity.ok(resp);
  }

  /**
   * Restore a dismissed finding. 200 with {@code {restored: true}} when
   * a row was removed, {@code {restored: false}} when no such row (idempotent).
   */
  @DeleteMapping("/findings/{id}/dismiss")
  public Map<String, Object> restore(@PathVariable("id") String id) {
    if (id == null || !ID_SHAPE.matcher(id).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "finding id is malformed");
    }
    boolean restored = dismissals.restore(id);
    if (restored) {
      // iter-27: only audit successful restores so a wave of DELETE
      // requests against ghost ids doesn't spam the audit table.
      audit.record(null, "security.restore", "finding:" + id, null);
    }
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("id", id);
    resp.put("restored", restored);
    return resp;
  }

  /**
   * Minimal JSON string-value escaper for the diff_json blob. Handles
   * the couple of chars an operator-supplied reason can carry.
   * Callers must wrap the result in double quotes.
   */
  static String jsonEscape(String s) {
    if (s == null) return "";
    StringBuilder b = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\': b.append("\\\\"); break;
        case '"':  b.append("\\\""); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
      }
    }
    return b.toString();
  }
}
