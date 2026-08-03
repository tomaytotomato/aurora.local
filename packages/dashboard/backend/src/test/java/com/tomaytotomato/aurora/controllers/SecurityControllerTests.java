package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.SecurityFinding;
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
    return MockMvcBuilders.standaloneSetup(new SecurityController(svc, repo)).build();
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
}
