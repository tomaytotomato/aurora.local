package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SecurityDismissalRepo;
import com.tomaytotomato.aurora.security.SecurityFindingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B4 (v0.3): {@code /api/security/**} contract. Standalone MockMvc;
 * SecurityFindingsService + SecurityDismissalRepo mocked.
 *
 * <p>iter-13 pinned the read side; iter-23 adds the dismiss/restore
 * mutations backing SecurityPosture's "not now" affordance.
 */
class SecurityControllerTests {

  private static MockMvc mvc(SecurityFindingsService svc, SecurityDismissalRepo repo) {
    return MockMvcBuilders.standaloneSetup(
        new SecurityController(svc, repo, Mockito.mock(AuditEventRepo.class))
    ).build();
  }

  private static MockMvc mvc(SecurityFindingsService svc, SecurityDismissalRepo repo, AuditEventRepo audit) {
    return MockMvcBuilders.standaloneSetup(new SecurityController(svc, repo, audit)).build();
  }

  @Test
  void returns_findings_list_with_all_fields() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    when(svc.allFindings(false)).thenReturn(List.of(
        new SecurityFinding("weak_admin_password:bruce", SecurityFinding.HIGH,
            "Admin password uses weak protection parameters",
            "Rotate the admin password on the Settings page.",
            "/settings#account")));

    mvc(svc, repo).perform(get("/api/security/findings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("weak_admin_password:bruce"))
        .andExpect(jsonPath("$[0].severity").value("high"))
        .andExpect(jsonPath("$[0].title").value("Admin password uses weak protection parameters"))
        .andExpect(jsonPath("$[0].description").value("Rotate the admin password on the Settings page."))
        .andExpect(jsonPath("$[0].remediationUrl").value("/settings#account"));
  }

  @Test
  void includeDismissed_true_forwarded_to_service() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    when(svc.allFindings(true)).thenReturn(List.of());
    mvc(svc, repo).perform(get("/api/security/findings").param("includeDismissed", "true"))
        .andExpect(status().isOk());
    Mockito.verify(svc).allFindings(true);
    Mockito.verify(svc, Mockito.never()).allFindings(false);
  }

  @Test
  void empty_findings_yield_empty_array() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    when(svc.allFindings(false)).thenReturn(List.of());
    mvc(svc, repo).perform(get("/api/security/findings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // -- dismiss ---------------------------------------------------------

  @Test
  void dismiss_snoozes_for_N_days() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    mvc(svc, repo).perform(post("/api/security/findings/weak_admin_password:bruce/dismiss")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\": 7, \"reason\": \"rotating friday\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("weak_admin_password:bruce"))
        .andExpect(jsonPath("$.expires_at").exists())
        .andExpect(jsonPath("$.reason").value("rotating friday"));

    ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(repo).dismiss(eq("weak_admin_password:bruce"), when.capture(),
        eq("rotating friday"));
    // Expiry should be ~7 days out; allow a 2-hour drift for test wall time.
    long deltaHours = java.time.Duration.between(Instant.now(), when.getValue()).toHours();
    org.junit.jupiter.api.Assertions.assertTrue(deltaHours >= 166 && deltaHours <= 168,
        "expected ~168h in the future, got " + deltaHours);
  }

  @Test
  void dismiss_without_days_is_permanent() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    mvc(svc, repo).perform(post("/api/security/findings/id-x/dismiss")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expires_at").doesNotExist());
    Mockito.verify(repo).dismiss(eq("id-x"), eq(null), eq(null));
  }

  @Test
  void dismiss_without_body_is_permanent() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    mvc(svc, repo).perform(post("/api/security/findings/id-x/dismiss"))
        .andExpect(status().isOk());
    Mockito.verify(repo).dismiss(eq("id-x"), eq(null), eq(null));
  }

  @Test
  void dismiss_rejects_out_of_range_days() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    for (int bad : new int[] { -1, 0, 366, 10_000 }) {
      mvc(svc, repo).perform(post("/api/security/findings/id/dismiss")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"days\": " + bad + "}"))
          .andExpect(status().isBadRequest());
    }
    Mockito.verify(repo, Mockito.never()).dismiss(any(), any(), any());
  }

  @Test
  void dismiss_rejects_malformed_id() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    for (String bad : new String[] {
        "UPPER",
        "1leading",
    }) {
      mvc(svc, repo).perform(post("/api/security/findings/" + bad + "/dismiss"))
          .andExpect(status().isBadRequest());
    }
    Mockito.verify(repo, Mockito.never()).dismiss(any(), any(), any());
  }

  @Test
  void dismiss_accepts_days_as_string() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    mvc(svc, repo).perform(post("/api/security/findings/id/dismiss")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\": \"3\"}"))
        .andExpect(status().isOk());
    Mockito.verify(repo).dismiss(eq("id"), any(Instant.class), eq(null));
  }

  @Test
  void dismiss_truncates_over_512_char_reason() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    String longReason = "x".repeat(1000);
    mvc(svc, repo).perform(post("/api/security/findings/id/dismiss")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\": \"" + longReason + "\"}"))
        .andExpect(status().isOk());
    Mockito.verify(repo).dismiss(eq("id"), eq(null),
        Mockito.argThat(s -> s != null && s.length() == 512));
  }

  // -- restore ---------------------------------------------------------

  @Test
  void restore_returns_restored_true_on_delete() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    when(repo.restore("id-x")).thenReturn(true);
    mvc(svc, repo).perform(delete("/api/security/findings/id-x/dismiss"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restored").value(true))
        .andExpect(jsonPath("$.id").value("id-x"));
  }

  @Test
  void restore_returns_restored_false_on_no_row() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    when(repo.restore("ghost")).thenReturn(false);
    mvc(svc, repo).perform(delete("/api/security/findings/ghost/dismiss"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restored").value(false));
  }

  @Test
  void restore_rejects_malformed_id() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    mvc(svc, repo).perform(delete("/api/security/findings/UPPER/dismiss"))
        .andExpect(status().isBadRequest());
    Mockito.verify(repo, Mockito.never()).restore(any());
  }

  // -- list dismissals -------------------------------------------------

  @Test
  void listDismissals_delegates_to_repo() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    when(repo.listAll()).thenReturn(List.of(Map.of(
        "finding_id", "weak_admin_password:bruce",
        "dismissed_at", "2026-08-01T00:00:00Z",
        "expires_at", "2026-08-08T00:00:00Z")));
    mvc(svc, repo).perform(get("/api/security/dismissals"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].finding_id").value("weak_admin_password:bruce"));
  }

  // -- audit trail (iter-27) -------------------------------------------

  @Test
  void dismiss_writes_audit_event() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    mvc(svc, repo, audit).perform(post("/api/security/findings/weak_admin_password:bruce/dismiss")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\": 7, \"reason\": \"rotating friday\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> diff = ArgumentCaptor.forClass(String.class);
    Mockito.verify(audit).record(
        eq(null),
        eq("security.dismiss"),
        eq("finding:weak_admin_password:bruce"),
        diff.capture());
    org.junit.jupiter.api.Assertions.assertTrue(diff.getValue().contains("expires_at"));
    org.junit.jupiter.api.Assertions.assertTrue(diff.getValue().contains("rotating friday"));
  }

  @Test
  void dismiss_permanent_writes_audit_event_with_null_expiry() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    mvc(svc, repo, audit).perform(post("/api/security/findings/id-x/dismiss")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    Mockito.verify(audit).record(
        eq(null),
        eq("security.dismiss"),
        eq("finding:id-x"),
        Mockito.argThat((String s) -> s != null && s.contains("\"expires_at\":null")));
  }

  @Test
  void restore_writes_audit_event_only_when_row_was_removed() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    when(repo.restore("id-x")).thenReturn(true);
    when(repo.restore("ghost")).thenReturn(false);

    mvc(svc, repo, audit).perform(delete("/api/security/findings/id-x/dismiss"))
        .andExpect(status().isOk());
    mvc(svc, repo, audit).perform(delete("/api/security/findings/ghost/dismiss"))
        .andExpect(status().isOk());

    Mockito.verify(audit, Mockito.times(1)).record(
        eq(null), eq("security.restore"), eq("finding:id-x"), eq(null));
    Mockito.verify(audit, Mockito.never()).record(
        any(), eq("security.restore"), eq("finding:ghost"), any());
  }

  @Test
  void dismiss_does_not_audit_when_id_is_malformed() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    SecurityDismissalRepo repo = Mockito.mock(SecurityDismissalRepo.class);
    AuditEventRepo audit = Mockito.mock(AuditEventRepo.class);
    mvc(svc, repo, audit).perform(post("/api/security/findings/UPPER/dismiss"))
        .andExpect(status().isBadRequest());
    Mockito.verifyNoInteractions(audit);
  }

  @Test
  void jsonEscape_covers_control_chars_and_quotes() {
    org.junit.jupiter.api.Assertions.assertEquals("plain", SecurityController.jsonEscape("plain"));
    org.junit.jupiter.api.Assertions.assertEquals("a\\\"b", SecurityController.jsonEscape("a\"b"));
    org.junit.jupiter.api.Assertions.assertEquals("a\\\\b", SecurityController.jsonEscape("a\\b"));
    org.junit.jupiter.api.Assertions.assertEquals("a\\nb", SecurityController.jsonEscape("a\nb"));
    org.junit.jupiter.api.Assertions.assertEquals("a\\u0001b", SecurityController.jsonEscape("a\u0001b"));
    org.junit.jupiter.api.Assertions.assertEquals("", SecurityController.jsonEscape(null));
  }
}
